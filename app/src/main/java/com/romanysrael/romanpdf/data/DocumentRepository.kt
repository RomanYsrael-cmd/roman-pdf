package com.romanysrael.romanpdf.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.romanysrael.romanpdf.core.ThumbnailStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

class DocumentRepository(
    private val context: Context,
    private val database: AppDatabase
) {
    private val resolver: ContentResolver = context.contentResolver

    suspend fun importUri(uri: Uri, mimeType: String?, kind: String): Long = withContext(Dispatchers.IO) {
        tryPersistPermission(uri)
        val actualMime = mimeType ?: resolver.getType(uri) ?: "application/octet-stream"
        val title = queryDisplayName(uri) ?: DocumentFile.fromSingleUri(context, uri)?.name ?: "Untitled"
        val pageCount = if (kind == DocumentKinds.PDF) inspectPdfPageCount(uri) else 1
        val document = DocumentEntity(
            title = title.substringBeforeLast('.').ifBlank { title },
            uri = uri.toString(),
            mimeType = actualMime,
            kind = kind,
            pageCount = pageCount.coerceAtLeast(1)
        )
        val insertedId = database.documentDao().insert(document)
        if (insertedId != -1L) insertedId
        else database.documentDao().getByUri(document.uri)?.id ?: error("The file is already in the library")
    }

    suspend fun getDocument(id: Long): DocumentEntity? = withContext(Dispatchers.IO) {
        database.documentDao().getById(id)
    }

    suspend fun updateLastPage(id: Long, page: Int) {
        database.documentDao().updateLastPage(id, page.coerceAtLeast(0))
    }

    suspend fun deleteDocument(document: DocumentEntity) = withContext(Dispatchers.IO) {
        database.strokeDao().deleteForDocument(document.id)
        database.noteDao().deleteForDocument(document.id)
        database.searchDao().deleteForDocument(document.id.toString())
        database.documentDao().delete(document)
        ThumbnailStore(context).delete(document.id)
    }

    suspend fun saveNote(note: NoteEntity): Long = withContext(Dispatchers.IO) {
        val id = database.noteDao().insert(note)
        rebuildNoteIndex(note.documentId)
        id
    }

    suspend fun rebuildNoteIndex(documentId: Long) = withContext(Dispatchers.IO) {
        val search = database.searchDao()
        search.deleteForSource(documentId.toString(), SearchSources.NOTE)
        database.noteDao().getForDocument(documentId).forEach { note ->
            if (note.content.isNotBlank()) {
                search.insert(
                    SearchEntryFts(
                        documentId = documentId.toString(),
                        pageIndex = (note.pageIndex ?: -1).toString(),
                        sourceType = SearchSources.NOTE,
                        content = note.content
                    )
                )
            }
        }
    }

    suspend fun saveStroke(stroke: Stroke): Stroke = withContext(Dispatchers.IO) {
        val row = stroke.toEntity()
        val id = database.strokeDao().insert(row)
        stroke.copy(id = id)
    }

    suspend fun deleteStroke(strokeId: Long) = withContext(Dispatchers.IO) {
        database.strokeDao().deleteById(strokeId)
    }

    suspend fun restoreStrokes(strokes: List<Stroke>) = withContext(Dispatchers.IO) {
        database.strokeDao().insertAll(strokes.map { it.toEntity() })
    }

    suspend fun strokesForPage(documentId: Long, pageIndex: Int): List<Stroke> = withContext(Dispatchers.IO) {
        database.strokeDao().getForPage(documentId, pageIndex).map(StrokeEntity::toModel)
    }

    suspend fun allStrokes(documentId: Long): List<Stroke> = withContext(Dispatchers.IO) {
        database.strokeDao().getForDocument(documentId).map(StrokeEntity::toModel)
    }

    suspend fun markStrokeRecognized(strokeId: Long, text: String?) = withContext(Dispatchers.IO) {
        database.strokeDao().setRecognizedText(strokeId, text)
    }

    suspend fun rebuildHandwritingIndex(documentId: Long) = withContext(Dispatchers.IO) {
        val search = database.searchDao()
        search.deleteForSource(documentId.toString(), SearchSources.HANDWRITING)
        database.strokeDao().getForDocument(documentId)
            .mapNotNull { it.recognizedText?.trim()?.takeIf(String::isNotBlank)?.let { text -> it to text } }
            .groupBy { it.first.pageIndex }
            .forEach { (pageIndex, rows) ->
                search.insert(
                    SearchEntryFts(
                        documentId = documentId.toString(),
                        pageIndex = pageIndex.toString(),
                        sourceType = SearchSources.HANDWRITING,
                        content = rows.joinToString(" ") { it.second }.take(MAX_INDEXED_PAGE_CHARS)
                    )
                )
            }
    }

    suspend fun indexDocument(documentId: Long, progress: suspend (Int, Int) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        val document = database.documentDao().getById(documentId) ?: return@withContext
        if (document.kind != DocumentKinds.PDF || document.textIndexComplete) return@withContext

        try {
            PDFBoxResourceLoader.init(context.applicationContext)
            val search = database.searchDao()
            search.deleteForSource(documentId.toString(), SearchSources.PDF_TEXT)
            extractPdfText(document) { page, pageCount, text ->
                ensureActive()
                if (text.isNotBlank()) {
                    search.insert(
                        SearchEntryFts(
                            documentId = documentId.toString(),
                            pageIndex = page.toString(),
                            sourceType = SearchSources.PDF_TEXT,
                            content = text.take(MAX_INDEXED_PAGE_CHARS)
                        )
                    )
                }
                progress(page + 1, pageCount)
            }
            database.documentDao().setTextIndexState(documentId, complete = true, failed = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            database.documentDao().setTextIndexState(documentId, complete = false, failed = true)
        }
    }

    suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val matchQuery = SearchQuery.toMatchQuery(query)
        if (matchQuery.isBlank()) emptyList() else database.searchDao().search(matchQuery)
    }

    private suspend fun extractPdfText(
        document: DocumentEntity,
        onPage: suspend (page: Int, pageCount: Int, text: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        resolver.openInputStream(Uri.parse(document.uri))?.use { input ->
            PDDocument.load(input).use { pdf ->
                val pageCount = pdf.numberOfPages
                val stripper = PDFTextStripper()
                for (page in 0 until pageCount) {
                    ensureActive()
                    stripper.startPage = page + 1
                    stripper.endPage = page + 1
                    onPage(page, pageCount, stripper.getText(pdf).trim())
                }
            }
        } ?: throw IOException("Cannot read ${document.title}")
    }

    private fun inspectPdfPageCount(uri: Uri): Int = runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            android.graphics.pdf.PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
        } ?: 1
    }.getOrDefault(1)

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun tryPersistPermission(uri: Uri) {
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    companion object {
        private const val MAX_INDEXED_PAGE_CHARS = 100_000
    }
}

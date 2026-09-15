package com.romanysrael.romanpdf.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.romanysrael.romanpdf.core.ThumbnailStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class DocumentRepository(
    private val context: Context,
    private val database: AppDatabase
) {
    private val resolver: ContentResolver = context.contentResolver

    suspend fun importUri(
        uri: Uri,
        mimeType: String?,
        kind: String,
        titleOverride: String? = null,
        copyIfPermissionUnavailable: Boolean = false,
        sourceKeyOverride: String? = null
    ): Long = withContext(Dispatchers.IO) {
        val sourceKey = sourceKeyOverride ?: uri.toString()
        database.documentDao().getBySourceKey(sourceKey)?.let { return@withContext it.id }
        database.documentDao().getByUri(uri.toString())?.let { return@withContext it.id }

        val actualMime = mimeType ?: resolver.getType(uri) ?: "application/octet-stream"
        val hasPersistedPermission = tryPersistPermission(uri)
        val storedUri = if (copyIfPermissionUnavailable && !hasPersistedPermission && uri.scheme in setOf("content", "file")) {
            materializeUri(uri, actualMime, sourceKey)
        } else {
            uri
        }
        val displayName = queryDisplayName(uri)
            ?: DocumentFile.fromSingleUri(context, uri)?.name
            ?: uri.lastPathSegment?.let(Uri::decode)?.takeUnless { it.isBlank() }
            ?: "Untitled"
        val title = DocumentNameAllocator.allocate(
            titleOverride?.trim().takeUnless { it.isNullOrBlank() } ?: displayName,
            database.documentDao().getAll().map(DocumentEntity::title)
        )
        val pageCount = if (kind == DocumentKinds.PDF) inspectPdfPageCount(storedUri) else 1
        val document = DocumentEntity(
            title = title,
            uri = storedUri.toString(),
            mimeType = actualMime,
            kind = kind,
            pageCount = pageCount.coerceAtLeast(1),
            pageSources = if (kind == DocumentKinds.IMAGE) PageSourceCodec.encode(listOf(storedUri.toString())) else "",
            sourceKey = sourceKey
        )
        val insertedId = database.documentDao().insert(document)
        if (insertedId != -1L) insertedId
        else database.documentDao().getBySourceKey(sourceKey)?.id
            ?: database.documentDao().getByUri(document.uri)?.id
            ?: error("The file is already in the library")
    }

    suspend fun importExternalPdf(uri: Uri, mimeType: String? = null): Long = importUri(
        uri = uri,
        mimeType = mimeType ?: "application/pdf",
        kind = DocumentKinds.PDF,
        copyIfPermissionUnavailable = true
    )

    suspend fun suggestImageDocumentTitle(uris: List<Uri>): String = withContext(Dispatchers.IO) {
        val firstName = uris.firstOrNull()?.let { queryDisplayName(it) }
            ?: "Image Document"
        DocumentNameAllocator.suggestedImageTitle(firstName, uris.size)
    }

    suspend fun importImageUris(uris: List<Uri>, titleOverride: String? = null): Long = withContext(Dispatchers.IO) {
        val selected = uris.distinctBy(Uri::toString)
        require(selected.isNotEmpty()) { "Select at least one image" }

        val storedUris = selected.map { imageUri ->
            val mimeType = resolver.getType(imageUri) ?: "image/*"
            val persisted = tryPersistPermission(imageUri)
            if (!persisted && imageUri.scheme == "content") {
                materializeUri(imageUri, mimeType, imageUri.toString())
            } else {
                imageUri
            }
        }
        val firstName = selected.firstOrNull()?.let { queryDisplayName(it) } ?: "Image Document"
        val requestedTitle = titleOverride?.trim().takeUnless { it.isNullOrBlank() }
            ?: DocumentNameAllocator.suggestedImageTitle(firstName, storedUris.size)
        val title = DocumentNameAllocator.allocate(
            requestedTitle,
            database.documentDao().getAll().map(DocumentEntity::title)
        )
        val documentKey = "romanpdf:image-document:${UUID.randomUUID()}"
        val document = DocumentEntity(
            title = title,
            uri = documentKey,
            mimeType = "image/*",
            kind = DocumentKinds.IMAGE,
            pageCount = storedUris.size,
            pageSources = PageSourceCodec.encode(storedUris.map(Uri::toString)),
            sourceKey = documentKey
        )
        database.documentDao().insert(document).takeIf { it != -1L }
            ?: error("Unable to add the image document")
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
            openInputStream(Uri.parse(document.uri))?.use { input ->
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
        openFileDescriptor(uri)?.use { descriptor ->
            android.graphics.pdf.PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
        } ?: 1
    }.getOrDefault(1)

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun tryPersistPermission(uri: Uri): Boolean {
        if (uri.scheme != "content") return false
        return runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            true
        }.getOrDefault(false)
    }

    private fun materializeUri(uri: Uri, mimeType: String, sourceKey: String): Uri {
        val directory = File(context.filesDir, "source_cache").apply { mkdirs() }
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('.', "bin")?.takeIf { it.length in 1..8 }
            ?: "bin"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        val target = File(directory, "$digest.$extension")
        if (!target.exists()) {
            val temporary = File(directory, ".$digest.tmp")
            val input = openInputStream(uri) ?: throw IOException("Cannot read $uri")
            input.use { source ->
                FileOutputStream(temporary).use { output -> source.copyTo(output) }
            }
            if (!temporary.renameTo(target) && !target.exists()) {
                temporary.delete()
                throw IOException("Cannot cache $uri")
            }
        }
        return Uri.fromFile(target)
    }

    private fun openInputStream(uri: Uri) = if (uri.scheme == "file") {
        uri.path?.let(::File)?.inputStream()
    } else {
        resolver.openInputStream(uri)
    }

    private fun openFileDescriptor(uri: Uri) = if (uri.scheme == "file") {
        uri.path?.let { android.os.ParcelFileDescriptor.open(File(it), android.os.ParcelFileDescriptor.MODE_READ_ONLY) }
    } else {
        resolver.openFileDescriptor(uri, "r")
    }

    companion object {
        private const val MAX_INDEXED_PAGE_CHARS = 100_000
    }
}

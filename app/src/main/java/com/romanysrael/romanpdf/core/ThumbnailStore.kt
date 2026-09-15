package com.romanysrael.romanpdf.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.romanysrael.romanpdf.data.DocumentEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThumbnailStore(private val context: Context) {
    private val directory: File by lazy {
        File(context.filesDir, "thumbnails").apply { mkdirs() }
    }

    fun fileFor(documentId: Long, pageIndex: Int = 0): File = File(directory, "${documentId}_$pageIndex.webp")

    suspend fun getOrCreate(document: DocumentEntity, renderer: DocumentRenderEngine, pageIndex: Int = 0): Bitmap? = withContext(Dispatchers.IO) {
        val file = fileFor(document.id, pageIndex)
        BitmapFactory.decodeFile(file.absolutePath)?.let { return@withContext it }
        val bitmap = renderer.renderPage(pageIndex, 220, 280) ?: return@withContext null
        runCatching {
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            file.outputStream().use { output -> bitmap.compress(format, 76, output) }
        }
        bitmap
    }

    fun delete(documentId: Long) {
        directory.listFiles()?.filter { it.name.startsWith("${documentId}_") }?.forEach(File::delete)
    }
}

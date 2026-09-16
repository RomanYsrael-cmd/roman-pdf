package com.romanysrael.romanpdf.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.romanysrael.romanpdf.data.DocumentEntity
import com.romanysrael.romanpdf.data.DocumentKinds
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
        decodeThumbnail(file, document)?.let { return@withContext it }
        val bitmap = renderer.renderPage(pageIndex, THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT) ?: return@withContext null
        val stored = runCatching {
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
            file.outputStream().use { output -> bitmap.compress(format, 76, output) }
            decodeThumbnail(file, document)
        }.getOrNull()
        if (stored != null) {
            bitmap.recycle()
            stored
        } else {
            bitmap
        }
    }

    private fun decodeThumbnail(file: File, document: DocumentEntity): Bitmap? {
        if (!file.exists()) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = if (document.kind == DocumentKinds.PDF) {
                Bitmap.Config.RGB_565
            } else {
                Bitmap.Config.ARGB_8888
            }
            inScaled = false
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    fun delete(documentId: Long) {
        directory.listFiles()?.filter { it.name.startsWith("${documentId}_") }?.forEach(File::delete)
    }

    private companion object {
        const val THUMBNAIL_WIDTH = 192
        const val THUMBNAIL_HEIGHT = 256
    }
}

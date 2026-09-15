package com.romanysrael.romanpdf.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Size
import com.romanysrael.romanpdf.data.DocumentEntity
import com.romanysrael.romanpdf.data.DocumentKinds
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** One small, serialized rendering gate keeps PdfRenderer and its file descriptor safe. */
class DocumentRenderEngine(
    private val context: Context,
    private val document: DocumentEntity
) : Closeable {
    private val lock = Any()
    private var descriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    fun pageCount(): Int = synchronized(lock) {
        if (document.kind == DocumentKinds.IMAGE) 1 else ensurePdfRenderer().pageCount
    }

    fun pageSize(pageIndex: Int): Size = synchronized(lock) {
        if (document.kind == DocumentKinds.IMAGE) imageBounds()
        else {
            val page = ensurePdfRenderer().openPage(pageIndex)
            try {
                Size(page.width, page.height)
            } finally {
                page.close()
            }
        }
    }

    /** Renders only the requested page at a display-sized working resolution. */
    fun renderPage(pageIndex: Int, targetWidth: Int, targetHeight: Int, forPrint: Boolean = false): Bitmap? = synchronized(lock) {
        if (document.kind == DocumentKinds.IMAGE) return@synchronized decodeImage(targetWidth, targetHeight)

        val page = ensurePdfRenderer().openPage(pageIndex)
        try {
            val scale = min(
                targetWidth.coerceAtLeast(1).toFloat() / page.width,
                targetHeight.coerceAtLeast(1).toFloat() / page.height
            ).coerceAtMost(3f)
            val width = max(1, ceil(page.width * scale).toInt())
            val height = max(1, ceil(page.height * scale).toInt())
            val safeScale = safeScaleForMemory(width, height)
            val bitmap = Bitmap.createBitmap(
                max(1, (width * safeScale).toInt()),
                max(1, (height * safeScale).toInt()),
                Bitmap.Config.ARGB_8888
            )
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                null,
                if (forPrint) PdfRenderer.Page.RENDER_MODE_FOR_PRINT else PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )
            bitmap
        } finally {
            page.close()
        }
    }

    private fun ensurePdfRenderer(): PdfRenderer {
        pdfRenderer?.let { return it }
        val pfd = context.contentResolver.openFileDescriptor(Uri.parse(document.uri), "r")
            ?: error("Unable to open ${document.title}")
        descriptor = pfd
        return PdfRenderer(pfd).also { pdfRenderer = it }
    }

    private fun imageBounds(): Size = context.contentResolver.openInputStream(Uri.parse(document.uri)).use { input ->
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(input, null, options)
        Size(options.outWidth.coerceAtLeast(1), options.outHeight.coerceAtLeast(1))
    }

    private fun decodeImage(targetWidth: Int, targetHeight: Int): Bitmap? {
        val uri = Uri.parse(document.uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val wantedWidth = targetWidth.coerceAtLeast(1)
        val wantedHeight = targetHeight.coerceAtLeast(1)
        while (bounds.outWidth / sample > wantedWidth * 2 || bounds.outHeight / sample > wantedHeight * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private fun safeScaleForMemory(width: Int, height: Int): Float {
        val bytes = width.toLong() * height.toLong() * 4L
        return if (bytes <= MAX_BITMAP_BYTES) 1f else kotlin.math.sqrt(MAX_BITMAP_BYTES.toFloat() / bytes)
    }

    override fun close() {
        synchronized(lock) {
            try {
                pdfRenderer?.close()
            } finally {
                pdfRenderer = null
                descriptor?.close()
                descriptor = null
            }
        }
    }

    companion object {
        private const val MAX_BITMAP_BYTES = 24L * 1024L * 1024L
    }
}

package com.romanysrael.romanpdf.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import com.romanysrael.romanpdf.data.InkPoint
import com.romanysrael.romanpdf.data.Stroke
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ExportManager {
    suspend fun exportPageImage(
        context: Context,
        document: com.romanysrael.romanpdf.data.DocumentEntity,
        renderer: DocumentRenderEngine,
        pageIndex: Int,
        strokes: List<Stroke>,
        format: ImageFormat = ImageFormat.PNG
    ): File = withContext(Dispatchers.IO) {
        val size = renderer.pageSize(pageIndex)
        val maxDimension = 1500f
        val scale = minOf(1f, maxDimension / maxOf(size.width, size.height).toFloat())
        val bitmap = renderer.renderPage(
            pageIndex,
            (size.width * scale).toInt().coerceAtLeast(1),
            (size.height * scale).toInt().coerceAtLeast(1),
            forPrint = true
        ) ?: error("Unable to render page")
        val output = File(exportDirectory(context), "${safeName(document.title)}_page_${pageIndex + 1}.${format.extension}")
        try {
            val canvas = Canvas(bitmap)
            drawStrokes(canvas, bitmap.width.toFloat(), bitmap.height.toFloat(), strokes)
            FileOutputStream(output).use { stream ->
                check(bitmap.compress(format.compression, 92, stream)) { "Unable to encode page image" }
            }
        } finally {
            bitmap.recycle()
        }
        output
    }

    suspend fun exportAnnotatedPdf(
        context: Context,
        document: com.romanysrael.romanpdf.data.DocumentEntity,
        renderer: DocumentRenderEngine,
        strokesForPage: suspend (Int) -> List<Stroke>,
        onPage: suspend (Int, Int) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val output = File(exportDirectory(context), "${safeName(document.title)}_annotated.pdf")
        val pdf = PdfDocument()
        try {
            val count = renderer.pageCount()
            for (pageIndex in 0 until count) {
                val size = renderer.pageSize(pageIndex)
                val pageWidth = if (size.width >= size.height) 792 else 612
                val pageHeight = if (size.width >= size.height) 612 else 792
                val bitmap = renderer.renderPage(pageIndex, pageWidth * 2, pageHeight * 2, forPrint = true)
                    ?: continue
                try {
                    val info = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
                    val page = pdf.startPage(info)
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()), null)
                    drawStrokes(page.canvas, pageWidth.toFloat(), pageHeight.toFloat(), strokesForPage(pageIndex))
                    pdf.finishPage(page)
                } finally {
                    bitmap.recycle()
                }
                onPage(pageIndex + 1, count)
            }
            FileOutputStream(output).use(pdf::writeTo)
        } finally {
            pdf.close()
        }
        output
    }

    private fun drawStrokes(canvas: Canvas, width: Float, height: Float, strokes: List<Stroke>) {
        for (stroke in strokes) {
            val points = stroke.points
            if (points.isEmpty()) continue
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (stroke.tool == "HIGHLIGHT") {
                    (stroke.color and 0x00FFFFFF) or 0x66000000
                } else stroke.color
                style = Paint.Style.STROKE
                strokeWidth = stroke.width * minOf(width, height)
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            val path = pathFor(points, width, height)
            canvas.drawPath(path, paint)
        }
    }

    private fun pathFor(points: List<InkPoint>, width: Float, height: Float): Path = Path().apply {
        moveTo(points.first().x * width, points.first().y * height)
        for (index in 1 until points.size) {
            lineTo(points[index].x * width, points[index].y * height)
        }
    }

    private fun exportDirectory(context: Context): File = File(context.filesDir, "exports").apply { mkdirs() }

    private fun safeName(title: String): String = title
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "roman_pdf" }

    enum class ImageFormat(val extension: String, val compression: Bitmap.CompressFormat) {
        PNG("png", Bitmap.CompressFormat.PNG),
        JPEG("jpg", Bitmap.CompressFormat.JPEG)
    }
}

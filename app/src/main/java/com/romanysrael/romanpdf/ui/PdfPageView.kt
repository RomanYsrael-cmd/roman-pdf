package com.romanysrael.romanpdf.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.os.SystemClock
import com.romanysrael.romanpdf.core.DocumentRenderEngine
import com.romanysrael.romanpdf.data.InkPoint
import com.romanysrael.romanpdf.data.Stroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

object AnnotationTools {
    const val NONE = "NONE"
    const val PEN = "PEN"
    const val HIGHLIGHT = "HIGHLIGHT"
    const val ERASER = "ERASER"
}

enum class TapZone { LEFT, CENTER, RIGHT }

/** A page-sized canvas with a tiny bitmap working set and normalized vector ink. */
class PdfPageView(context: Context) : View(context) {
    var onPageTap: ((pageIndex: Int, zone: TapZone) -> Unit)? = null
    var onStrokeCommitted: ((Stroke) -> Unit)? = null
    var onStrokesErased: ((pageIndex: Int, ids: List<Long>) -> Unit)? = null

    private var renderer: DocumentRenderEngine? = null
    private var renderScope: CoroutineScope? = null
    private var renderJob: Job? = null
    private var boundPageIndex = -1
    private var bitmap: android.graphics.Bitmap? = null
    private val pageRect = RectF()
    private var strokes: List<Stroke> = emptyList()
    private var activePoints = ArrayList<InkPoint>(64)
    private var activeStroke: Stroke? = null
    private var activePath = Path()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var tool = AnnotationTools.NONE
    private var inkColor = Color.rgb(34, 74, 150)
    private var inkWidth = 0.0045f
    private var scaleFactor = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var eraserHits = LinkedHashSet<Long>()
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var pendingTap: Runnable? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            moved = true
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = scaleFactor
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(1f, 3f)
            val focusX = detector.focusX
            val focusY = detector.focusY
            val contentX = (focusX - offsetX) / oldScale
            val contentY = (focusY - offsetY) / oldScale
            offsetX = focusX - contentX * scaleFactor
            offsetY = focusY - contentY * scaleFactor
            if (scaleFactor <= 1.01f) {
                scaleFactor = 1f
                offsetX = 0f
                offsetY = 0f
            }
            boundPan()
            invalidate()
            return true
        }
    })

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isFocusable = true
        contentDescription = "PDF page"
    }

    fun bind(
        pageIndex: Int,
        renderEngine: DocumentRenderEngine,
        scope: CoroutineScope,
        pageStrokes: List<Stroke>
    ) {
        renderJob?.cancel()
        recycleBitmap()
        boundPageIndex = pageIndex
        renderer = renderEngine
        renderScope = scope
        strokes = pageStrokes
        cancelPendingTap()
        resetZoom()
        if (width > 0 && height > 0) requestRender()
        invalidate()
    }

    fun unbind() {
        renderJob?.cancel()
        renderJob = null
        cancelPendingTap()
        recycleBitmap()
        renderer = null
        renderScope = null
        boundPageIndex = -1
        strokes = emptyList()
        activePoints.clear()
        activeStroke = null
        activePath.reset()
    }

    fun setStrokes(newStrokes: List<Stroke>) {
        strokes = newStrokes
        invalidate()
    }

    fun setTool(newTool: String) {
        tool = newTool
        if (newTool != AnnotationTools.NONE) cancelPendingTap()
        if (newTool == AnnotationTools.NONE) resetZoom()
        invalidate()
    }

    fun setInkWidth(width: Float) {
        inkWidth = width.coerceIn(0.002f, 0.02f)
    }

    fun setInkColor(color: Int) {
        inkColor = color
        invalidate()
    }

    fun currentTool(): String = tool

    fun resetZoom() {
        scaleFactor = 1f
        offsetX = 0f
        offsetY = 0f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestRender()
    }

    private fun requestRender() {
        val page = boundPageIndex
        val currentRenderer = renderer ?: return
        val scope = renderScope ?: return
        if (page < 0 || width <= 0 || height <= 0) return
        renderJob?.cancel()
        val targetWidth = (width - 24).coerceAtLeast(240)
        val targetHeight = (height - 24).coerceAtLeast(240)
        renderJob = scope.launch(Dispatchers.IO) {
            val rendered = runCatching {
                currentRenderer.renderPage(page, targetWidth, targetHeight)
            }.getOrNull()
            if (!isActive) {
                rendered?.recycle()
                return@launch
            }
            try {
                withContext(Dispatchers.Main) {
                    if (boundPageIndex == page && renderer === currentRenderer && rendered != null) {
                        recycleBitmap()
                        bitmap = rendered
                        invalidate()
                    } else {
                        rendered?.recycle()
                    }
                }
            } catch (cancelled: CancellationException) {
                rendered?.recycle()
                throw cancelled
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(16, 19, 24))
        val pageBitmap = bitmap ?: return
        pageRect.set(fitRect(pageBitmap.width, pageBitmap.height))
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scaleFactor, scaleFactor)
        canvas.drawBitmap(pageBitmap, null, pageRect, bitmapPaint)
        strokes.forEach { drawStroke(canvas, it, pageRect) }
        activeStroke?.let { drawStroke(canvas, it, pageRect) }
        canvas.restore()
    }

    private fun fitRect(bitmapWidth: Int, bitmapHeight: Int): RectF {
        val inset = 12f
        val availableWidth = (width - inset * 2).coerceAtLeast(1f)
        val availableHeight = (height - inset * 2).coerceAtLeast(1f)
        val fit = min(availableWidth / bitmapWidth, availableHeight / bitmapHeight)
        val drawWidth = bitmapWidth * fit
        val drawHeight = bitmapHeight * fit
        return RectF(
            (width - drawWidth) / 2f,
            (height - drawHeight) / 2f,
            (width + drawWidth) / 2f,
            (height + drawHeight) / 2f
        )
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke, rect: RectF) {
        if (stroke.points.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (stroke.tool == AnnotationTools.HIGHLIGHT) {
                Color.argb(92, Color.red(stroke.color), Color.green(stroke.color), Color.blue(stroke.color))
            } else stroke.color
            style = Paint.Style.STROKE
            strokeWidth = max(2f, stroke.width * min(rect.width(), rect.height()))
            if (stroke.tool == AnnotationTools.HIGHLIGHT) strokeWidth *= 2.7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        val first = stroke.points.first()
        path.moveTo(rect.left + first.x * rect.width(), rect.top + first.y * rect.height())
        for (index in 1 until stroke.points.size) {
            val point = stroke.points[index]
            path.lineTo(rect.left + point.x * rect.width(), rect.top + point.y * rect.height())
        }
        if (stroke.points.size == 1) {
            val x = rect.left + first.x * rect.width()
            val y = rect.top + first.y * rect.height()
            canvas.drawCircle(x, y, paint.strokeWidth / 2f, Paint(paint).apply { style = Paint.Style.FILL })
        } else {
            canvas.drawPath(path, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                eraserHits.clear()
                if (tool == AnnotationTools.PEN || tool == AnnotationTools.HIGHLIGHT) {
                    activePoints.clear()
                    activeStroke = Stroke(
                        documentId = 0L,
                        pageIndex = boundPageIndex,
                        tool = tool,
                        color = inkColor,
                        width = if (tool == AnnotationTools.HIGHLIGHT) 0.014f else inkWidth,
                        points = activePoints
                    )
                    activePath.reset()
                    toNormalized(event.x, event.y)?.let { point ->
                        activePoints.add(point)
                        activePath.moveTo(event.x, event.y)
                    }
                } else if (tool == AnnotationTools.ERASER) {
                    toNormalized(event.x, event.y)?.let(::findHits)
                }
                // Hold the first few pixels locally so a second tap can be recognized as a double tap.
                // Reading-mode swipes release the parent again once movement is unambiguous.
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || scaleFactor > 1.01f) {
                    if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        offsetX += event.x - lastX
                        offsetY += event.y - lastY
                        boundPan()
                    }
                    moved = true
                    lastX = event.x
                    lastY = event.y
                    invalidate()
                    return true
                }
                if (tool == AnnotationTools.PEN || tool == AnnotationTools.HIGHLIGHT) {
                    toNormalized(event.x, event.y)?.let { point ->
                        if (activePoints.lastOrNull()?.let { distance(it, point) } ?: 1f > 0.001f) {
                            activePoints.add(point)
                            activePath.lineTo(event.x, event.y)
                            moved = true
                            invalidate()
                        }
                    }
                } else if (tool == AnnotationTools.ERASER) {
                    toNormalized(event.x, event.y)?.let { point ->
                        findHits(point)
                        moved = true
                    }
                } else if (distance(downX, downY, event.x, event.y) > 18f) {
                    moved = true
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                lastX = event.x
                lastY = event.y
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (tool == AnnotationTools.PEN || tool == AnnotationTools.HIGHLIGHT) {
                        if (activePoints.size >= 2) {
                            onStrokeCommitted?.invoke(
                                Stroke(
                                    documentId = 0L,
                                    pageIndex = boundPageIndex,
                                    tool = tool,
                                    color = inkColor,
                                    width = if (tool == AnnotationTools.HIGHLIGHT) 0.014f else inkWidth,
                                    points = activePoints.toList()
                                )
                            )
                        }
                        activePoints.clear()
                        activeStroke = null
                        activePath.reset()
                        invalidate()
                    } else if (tool == AnnotationTools.ERASER) {
                        if (eraserHits.isNotEmpty()) onStrokesErased?.invoke(boundPageIndex, eraserHits.toList())
                        eraserHits.clear()
                    } else if (!moved) {
                        val now = SystemClock.uptimeMillis()
                        val isDoubleTap = now - lastTapAt in 1..360 &&
                            distance(lastTapX, lastTapY, event.x, event.y) < 72f
                        if (isDoubleTap) {
                            cancelPendingTap()
                            performClick()
                            if (scaleFactor > 1.05f) resetZoom() else zoomAt(event.x, event.y)
                            lastTapAt = 0L
                            return true
                        }
                        val zone = when {
                            event.x < width * 0.28f -> TapZone.LEFT
                            event.x > width * 0.72f -> TapZone.RIGHT
                            else -> TapZone.CENTER
                        }
                        scheduleTap(now, zone, dispatch = scaleFactor <= 1.01f)
                    }
                } else {
                    cancelPendingTap()
                    activePoints.clear()
                    activeStroke = null
                    activePath.reset()
                    eraserHits.clear()
                    invalidate()
                }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun scheduleTap(timestamp: Long, zone: TapZone, dispatch: Boolean) {
        cancelPendingTap()
        lastTapAt = timestamp
        lastTapX = downX
        lastTapY = downY
        val page = boundPageIndex
        val callback = Runnable {
            if (lastTapAt == timestamp) {
                lastTapAt = 0L
                pendingTap = null
                performClick()
                if (dispatch) onPageTap?.invoke(page, zone)
            }
        }
        pendingTap = callback
        postDelayed(callback, DOUBLE_TAP_DELAY_MS)
    }

    private fun cancelPendingTap() {
        pendingTap?.let(::removeCallbacks)
        pendingTap = null
    }

    private fun zoomAt(x: Float, y: Float) {
        val targetScale = 2.15f
        offsetX = x - ((x - offsetX) / scaleFactor) * targetScale
        offsetY = y - ((y - offsetY) / scaleFactor) * targetScale
        scaleFactor = targetScale
        boundPan()
        invalidate()
    }

    private fun toNormalized(screenX: Float, screenY: Float): InkPoint? {
        val baseX = (screenX - offsetX) / scaleFactor
        val baseY = (screenY - offsetY) / scaleFactor
        if (!pageRect.contains(baseX, baseY)) return null
        return InkPoint(
            x = ((baseX - pageRect.left) / pageRect.width()).coerceIn(0f, 1f),
            y = ((baseY - pageRect.top) / pageRect.height()).coerceIn(0f, 1f),
            pressure = 1f,
            time = System.currentTimeMillis()
        )
    }

    private fun findHits(point: InkPoint) {
        val threshold = 0.026f
        strokes.forEach { stroke ->
            if (stroke.points.any { distance(it, point) <= threshold + stroke.width * 1.5f }) {
                eraserHits.add(stroke.id)
            }
        }
    }

    private fun boundPan() {
        if (scaleFactor <= 1.01f) {
            offsetX = 0f
            offsetY = 0f
            return
        }
        val maxX = width * 0.55f
        val maxY = height * 0.55f
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    private fun distance(a: InkPoint, b: InkPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun recycleBitmap() {
        bitmap?.recycle()
        bitmap = null
    }

    override fun onDetachedFromWindow() {
        renderJob?.cancel()
        cancelPendingTap()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DOUBLE_TAP_DELAY_MS = 300L
    }
}

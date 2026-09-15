package com.romanysrael.romanpdf.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.romanysrael.romanpdf.core.DocumentRenderEngine
import com.romanysrael.romanpdf.data.InkPoint
import com.romanysrael.romanpdf.data.Stroke
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object AnnotationTools {
    const val NONE = "NONE"
    const val PEN = "PEN"
    const val HIGHLIGHT = "HIGHLIGHT"
    const val ERASER = "ERASER"
}

enum class TapZone { LEFT, CENTER, RIGHT }

/** A page-sized canvas with a bounded bitmap working set and normalized vector ink. */
class PdfPageView(context: Context) : View(context) {
    var onPageTap: ((pageIndex: Int, zone: TapZone) -> Unit)? = null
    var onStrokeCommitted: ((Stroke) -> Unit)? = null
    var onStrokesErased: ((pageIndex: Int, ids: List<Long>) -> Unit)? = null

    private var renderer: DocumentRenderEngine? = null
    private var renderScope: CoroutineScope? = null
    private var renderJob: Job? = null
    private var boundPageIndex = -1
    private var bitmap: Bitmap? = null
    private val pageRect = RectF()
    private var strokes: List<Stroke> = emptyList()
    private var activePoints = ArrayList<InkPoint>(64)
    private var activeStroke: Stroke? = null
    private var activePath = Path()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var mode = ReaderMode.VIEW
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
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var multiTouch = false
    private var ignoreSinglePointerUntilDown = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
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
        cancelActiveAnnotation()
        multiTouch = false
        ignoreSinglePointerUntilDown = false
        resetZoom()
        if (width > 0 && height > 0) requestRender()
        invalidate()
    }

    fun unbind() {
        renderJob?.cancel()
        renderJob = null
        cancelPendingTap()
        cancelActiveAnnotation()
        recycleBitmap()
        renderer = null
        renderScope = null
        boundPageIndex = -1
        strokes = emptyList()
        multiTouch = false
        ignoreSinglePointerUntilDown = false
    }

    fun setStrokes(newStrokes: List<Stroke>) {
        strokes = newStrokes
        invalidate()
    }

    fun setMode(newMode: ReaderMode) {
        mode = newMode
        if (newMode == ReaderMode.VIEW) {
            cancelActiveAnnotation()
            eraserHits.clear()
        }
        invalidate()
    }

    fun setTool(newTool: String) {
        tool = if (mode == ReaderMode.EDIT) newTool else AnnotationTools.NONE
        if (tool != AnnotationTools.NONE) cancelPendingTap()
        cancelActiveAnnotation()
        eraserHits.clear()
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
        resetZoom()
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
                ensurePageRect()
                activePointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                moved = false
                multiTouch = false
                ignoreSinglePointerUntilDown = false
                eraserHits.clear()
                parent?.requestDisallowInterceptTouchEvent(true)
                if (ReaderInteractionPolicy.canDraw(mode, tool)) {
                    startAnnotation(event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    moved = true
                    multiTouch = true
                    cancelPendingTap()
                    if (ReaderInteractionPolicy.shouldCancelAnnotationForSecondPointer(mode)) {
                        cancelActiveAnnotation()
                        eraserHits.clear()
                    }
                    val focus = focusOf(event)
                    lastFocusX = focus.first
                    lastFocusY = focus.second
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || multiTouch) {
                    moved = true
                    multiTouch = true
                    val focus = focusOf(event)
                    offsetX += focus.first - lastFocusX
                    offsetY += focus.second - lastFocusY
                    lastFocusX = focus.first
                    lastFocusY = focus.second
                    boundPan()
                    invalidate()
                    return true
                }
                if (ignoreSinglePointerUntilDown) return true

                val pointerIndex = event.findPointerIndex(activePointerId).takeIf { it >= 0 } ?: 0
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                if (ReaderInteractionPolicy.canDraw(mode, tool)) {
                    if (tool == AnnotationTools.PEN || tool == AnnotationTools.HIGHLIGHT) {
                        toNormalized(x, y)?.let { point ->
                            if (activePoints.lastOrNull()?.let { distance(it, point) } ?: 1f > 0.0008f) {
                                activePoints.add(point)
                                activePath.lineTo(x, y)
                                moved = true
                                invalidate()
                            }
                        }
                    } else if (tool == AnnotationTools.ERASER) {
                        toNormalized(x, y)?.let { point ->
                            findHits(point)
                            moved = true
                        }
                    }
                } else if (scaleFactor > 1.01f) {
                    offsetX += x - lastX
                    offsetY += y - lastY
                    boundPan()
                    moved = true
                    invalidate()
                } else if (distance(downX, downY, x, y) > 18f) {
                    moved = true
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                lastX = x
                lastY = y
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount >= 2) {
                    moved = true
                    multiTouch = true
                    ignoreSinglePointerUntilDown = true
                    cancelActiveAnnotation()
                    eraserHits.clear()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (multiTouch || ignoreSinglePointerUntilDown) {
                    finishMultiTouch()
                    return true
                }
                if (ReaderInteractionPolicy.canDraw(mode, tool)) {
                    val now = SystemClock.uptimeMillis()
                    if (!moved && isDoubleTap(now, event.x, event.y)) {
                        cancelPendingTap()
                        cancelActiveAnnotation()
                        eraserHits.clear()
                        resetZoom()
                        lastTapAt = 0L
                    } else {
                        finishAnnotation()
                        if (!moved) rememberEditTap(event.x, event.y)
                    }
                } else if (!moved) {
                    handleTap(event.x, event.y)
                } else {
                    cancelPendingTap()
                }
                activePointerId = MotionEvent.INVALID_POINTER_ID
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                cancelPendingTap()
                cancelActiveAnnotation()
                eraserHits.clear()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                multiTouch = false
                ignoreSinglePointerUntilDown = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun startAnnotation(x: Float, y: Float) {
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
            toNormalized(x, y)?.let { point ->
                activePoints.add(point)
                activePath.moveTo(x, y)
            }
        } else if (tool == AnnotationTools.ERASER) {
            toNormalized(x, y)?.let(::findHits)
        }
    }

    private fun finishAnnotation() {
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
        }
    }

    private fun handleTap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val zone = when {
            x < width * 0.28f -> TapZone.LEFT
            x > width * 0.72f -> TapZone.RIGHT
            else -> TapZone.CENTER
        }
        val isDoubleTap = zone == TapZone.CENTER && isDoubleTap(now, x, y)
        if (isDoubleTap) {
            cancelPendingTap()
            performClick()
            if (mode == ReaderMode.VIEW && scaleFactor <= 1.05f) zoomAt(x, y) else resetZoom()
            lastTapAt = 0L
            return
        }
        lastTapAt = now
        lastTapX = x
        lastTapY = y
        if (mode == ReaderMode.EDIT) return

        if (zone != TapZone.CENTER && scaleFactor <= 1.01f) {
            cancelPendingTap()
            lastTapAt = 0L
            performClick()
            onPageTap?.invoke(boundPageIndex, zone)
        } else {
            scheduleTap(now, zone, x, y, dispatch = scaleFactor <= 1.01f)
        }
    }

    private fun rememberEditTap(x: Float, y: Float) {
        lastTapAt = SystemClock.uptimeMillis()
        lastTapX = x
        lastTapY = y
    }

    private fun isDoubleTap(now: Long, x: Float, y: Float): Boolean =
        now - lastTapAt in 1..DOUBLE_TAP_TIMEOUT_MS && distance(lastTapX, lastTapY, x, y) < DOUBLE_TAP_DISTANCE

    private fun scheduleTap(timestamp: Long, zone: TapZone, x: Float, y: Float, dispatch: Boolean) {
        cancelPendingTap()
        lastTapAt = timestamp
        lastTapX = x
        lastTapY = y
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
        ensurePageRect()
        val point = PageTransform(
            left = pageRect.left,
            top = pageRect.top,
            width = pageRect.width(),
            height = pageRect.height(),
            scale = scaleFactor,
            offsetX = offsetX,
            offsetY = offsetY
        ).screenToNormalized(screenX, screenY) ?: return null
        return InkPoint(
            x = point.x,
            y = point.y,
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
        ensurePageRect()
        val maxX = max(24f, (pageRect.width() * scaleFactor - width) / 2f + 24f)
        val maxY = max(24f, (pageRect.height() * scaleFactor - height) / 2f + 24f)
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    private fun focusOf(event: MotionEvent): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        val count = event.pointerCount.coerceAtLeast(1)
        for (index in 0 until count) {
            x += event.getX(index)
            y += event.getY(index)
        }
        return (x / count) to (y / count)
    }

    private fun finishMultiTouch() {
        cancelPendingTap()
        cancelActiveAnnotation()
        eraserHits.clear()
        activePointerId = MotionEvent.INVALID_POINTER_ID
        multiTouch = false
        ignoreSinglePointerUntilDown = false
        invalidate()
    }

    private fun cancelActiveAnnotation() {
        activePoints.clear()
        activeStroke = null
        activePath.reset()
        invalidate()
    }

    private fun ensurePageRect() {
        bitmap?.let { pageRect.set(fitRect(it.width, it.height)) }
    }

    private fun distance(a: InkPoint, b: InkPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    private fun recycleBitmap() {
        bitmap?.recycle()
        bitmap = null
    }

    override fun onDetachedFromWindow() {
        renderJob?.cancel()
        cancelPendingTap()
        cancelActiveAnnotation()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DOUBLE_TAP_DELAY_MS = 280L
        const val DOUBLE_TAP_TIMEOUT_MS = 360L
        const val DOUBLE_TAP_DISTANCE = 72f
    }
}

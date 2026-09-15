package com.romanysrael.romanpdf.ui

enum class ReaderMode {
    VIEW,
    EDIT
}

enum class NavigationSource {
    SWIPE,
    TAP,
    SEARCH,
    THUMBNAIL,
    PAGE_JUMP
}

object ReaderInteractionPolicy {
    fun canDraw(mode: ReaderMode, tool: String): Boolean =
        mode == ReaderMode.EDIT && tool != AnnotationTools.NONE

    fun usesImmediatePositioning(source: NavigationSource): Boolean =
        source != NavigationSource.SWIPE

    fun shouldCancelAnnotationForSecondPointer(mode: ReaderMode): Boolean = mode == ReaderMode.EDIT
}

object ReaderRenderPolicy {
    /** The adjacent working set is intentionally bounded to keep the reader responsive on tablets. */
    fun prefetchPages(pageIndex: Int, pageCount: Int): List<Int> = listOf(pageIndex - 1, pageIndex + 1)
        .filter { it in 0 until pageCount }

    fun shouldRetainDisplayedBitmap(hasDisplayedBitmap: Boolean, hasReplacement: Boolean): Boolean =
        hasDisplayedBitmap && !hasReplacement
}

data class NormalizedPagePoint(val x: Float, val y: Float)

data class ScreenPagePoint(val x: Float, val y: Float)

/** Inverse/forward mapping for the page rectangle after scale and translation. */
data class PageTransform(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float
) {
    fun screenToNormalized(screenX: Float, screenY: Float): NormalizedPagePoint? {
        val safeScale = scale.coerceAtLeast(0.0001f)
        val baseX = (screenX - offsetX) / safeScale
        val baseY = (screenY - offsetY) / safeScale
        if (baseX < left || baseX > left + width || baseY < top || baseY > top + height) return null
        return NormalizedPagePoint(
            x = ((baseX - left) / width.coerceAtLeast(0.0001f)).coerceIn(0f, 1f),
            y = ((baseY - top) / height.coerceAtLeast(0.0001f)).coerceIn(0f, 1f)
        )
    }

    fun normalizedToScreen(x: Float, y: Float): ScreenPagePoint = ScreenPagePoint(
        x = offsetX + (left + x.coerceIn(0f, 1f) * width) * scale,
        y = offsetY + (top + y.coerceIn(0f, 1f) * height) * scale
    )
}

enum class LibraryLayout {
    LIST,
    GRID;

    companion object {
        fun fromStored(value: String?): LibraryLayout =
            if (value.equals(GRID.name, ignoreCase = true)) GRID else LIST
    }
}

object LibraryLayoutPolicy {
    const val PREFERENCE_KEY = "library_layout"

    fun spanCount(widthPx: Int, density: Float): Int {
        val minimumTileWidth = (210f * density).coerceAtLeast(1f)
        return (widthPx / minimumTileWidth).toInt().coerceIn(2, 6)
    }
}

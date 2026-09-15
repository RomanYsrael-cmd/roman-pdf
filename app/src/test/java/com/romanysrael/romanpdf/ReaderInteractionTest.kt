package com.romanysrael.romanpdf

import com.romanysrael.romanpdf.ui.AnnotationTools
import com.romanysrael.romanpdf.ui.NavigationSource
import com.romanysrael.romanpdf.ui.PageTransform
import com.romanysrael.romanpdf.ui.ReaderInteractionPolicy
import com.romanysrael.romanpdf.ui.ReaderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderInteractionTest {
    @Test
    fun normalizedCoordinatesRoundTripThroughZoomAndPan() {
        val transform = PageTransform(
            left = 20f,
            top = 30f,
            width = 760f,
            height = 1000f,
            scale = 2.2f,
            offsetX = -180f,
            offsetY = 64f
        )
        val screen = transform.normalizedToScreen(0.72f, 0.31f)
        val normalized = transform.screenToNormalized(screen.x, screen.y)

        assertNotNull(normalized)
        assertEquals(0.72f, normalized!!.x, 0.0001f)
        assertEquals(0.31f, normalized.y, 0.0001f)
    }

    @Test
    fun modePolicySeparatesSingleFingerDrawingFromViewPanning() {
        assertFalse(ReaderInteractionPolicy.canDraw(ReaderMode.VIEW, AnnotationTools.PEN))
        assertTrue(ReaderInteractionPolicy.canDraw(ReaderMode.EDIT, AnnotationTools.PEN))
        assertTrue(ReaderInteractionPolicy.canDraw(ReaderMode.EDIT, AnnotationTools.ERASER))
        assertTrue(ReaderInteractionPolicy.shouldCancelAnnotationForSecondPointer(ReaderMode.EDIT))
    }

    @Test
    fun tapNavigationIsImmediateButSwipeRemainsAnimated() {
        assertTrue(ReaderInteractionPolicy.usesImmediatePositioning(NavigationSource.TAP))
        assertTrue(ReaderInteractionPolicy.usesImmediatePositioning(NavigationSource.SEARCH))
        assertFalse(ReaderInteractionPolicy.usesImmediatePositioning(NavigationSource.SWIPE))
    }
}

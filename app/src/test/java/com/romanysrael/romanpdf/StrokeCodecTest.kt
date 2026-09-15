package com.romanysrael.romanpdf

import com.romanysrael.romanpdf.data.InkPoint
import com.romanysrael.romanpdf.data.Stroke
import com.romanysrael.romanpdf.data.StrokeCodec
import com.romanysrael.romanpdf.data.toEntity
import com.romanysrael.romanpdf.data.toModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeCodecTest {
    @Test
    fun normalizedPointsRoundTrip() {
        val points = listOf(
            InkPoint(0.1f, 0.2f, 0.7f, 10L),
            InkPoint(0.8f, 0.9f, 1f, 20L)
        )
        val decoded = StrokeCodec.decode(StrokeCodec.encode(points))
        assertEquals(points.size, decoded.size)
        assertEquals(points[0].x, decoded[0].x, 0.00001f)
        assertEquals(points[0].y, decoded[0].y, 0.00001f)
        assertEquals(points[0].pressure, decoded[0].pressure, 0.00001f)
        assertEquals(points[1].time, decoded[1].time)
    }

    @Test
    fun malformedPointRowsAreIgnored() {
        val decoded = StrokeCodec.decode("bad;0.5,0.6,1,3")
        assertEquals(1, decoded.size)
        assertTrue(decoded.single().x in 0.49f..0.51f)
    }

    @Test
    fun storedStrokeRetainsToolAndColor() {
        val stroke = Stroke(
            documentId = 7L,
            pageIndex = 2,
            tool = "HIGHLIGHT",
            color = 0xFFFF9800.toInt(),
            width = 0.014f,
            points = listOf(InkPoint(0.2f, 0.3f), InkPoint(0.7f, 0.3f))
        )

        val restored = stroke.toEntity().toModel()

        assertEquals(stroke.tool, restored.tool)
        assertEquals(stroke.color, restored.color)
        assertEquals(stroke.points.size, restored.points.size)
    }
}

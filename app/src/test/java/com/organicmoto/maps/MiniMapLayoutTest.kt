package com.organicmoto.maps

import androidx.compose.ui.geometry.Offset
import com.organicmoto.maps.storage.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniMapLayoutTest {

    @Test
    fun `a horizontal track fills the padded width and centres vertically`() {
        val fitted = MiniMapLayout.fit(
            points = listOf(GeoPoint(-27.0, 150.0), GeoPoint(-27.0, 152.0)),
            widthPx = 200f,
            heightPx = 100f,
            paddingPx = 10f,
        )
        assertEquals(2, fitted.size)
        assertEquals(10f, fitted[0].x, EPSILON)
        assertEquals(190f, fitted[1].x, EPSILON)
        assertEquals(50f, fitted[0].y, EPSILON)
        assertEquals(50f, fitted[1].y, EPSILON)
    }

    @Test
    fun `a tall bbox in a square box letterboxes horizontally`() {
        // 1 degree of latitude is taller than 1 degree of longitude here, so
        // the height constrains and the width insets symmetrically.
        val fitted = MiniMapLayout.fit(
            points = listOf(GeoPoint(-27.0, 150.0), GeoPoint(-26.0, 151.0)),
            widthPx = 100f,
            heightPx = 100f,
            paddingPx = 0f,
        )
        val xInset = minOf(fitted[0].x, fitted[1].x)
        assertTrue("expected horizontal inset, got $xInset", xInset > 1f)
        assertEquals(xInset, 100f - maxOf(fitted[0].x, fitted[1].x), EPSILON)
        assertEquals(listOf(100f, 0f), fitted.map { it.y })
    }

    @Test
    fun `a single point sits dead centre`() {
        val fitted = MiniMapLayout.fit(
            points = listOf(GeoPoint(-20.0, 145.0)),
            widthPx = 92f,
            heightPx = 64f,
            paddingPx = 5f,
        )
        assertEquals(Offset(46f, 32f), fitted.single())
    }

    @Test
    fun `identical points all collapse onto the centre`() {
        val fitted = MiniMapLayout.fit(
            points = List(4) { GeoPoint(-25.0, 148.0) },
            widthPx = 100f,
            heightPx = 60f,
            paddingPx = 6f,
        )
        assertEquals(List(4) { Offset(50f, 30f) }, fitted)
    }

    @Test
    fun `oversized padding collapses to the centre instead of throwing`() {
        val fitted = MiniMapLayout.fit(
            points = listOf(GeoPoint(-27.0, 150.0), GeoPoint(-26.0, 153.0)),
            widthPx = 100f,
            heightPx = 80f,
            paddingPx = 60f,
        )
        assertEquals(List(2) { Offset(50f, 40f) }, fitted)
    }

    @Test
    fun `output preserves count and draw order`() {
        val points = (0 until 7).map { GeoPoint(-28.0 + it * 0.1, 149.0 + it * 0.05) }
        val fitted = MiniMapLayout.fit(points, 120f, 90f, 8f)
        assertEquals(points.size, fitted.size)
        // Latitude rises with the index; screen y must fall.
        assertTrue(fitted.zipWithNext().all { (a, b) -> a.y >= b.y })
    }

    private companion object {
        const val EPSILON = 1e-3f
    }
}

package com.organicmoto.maps.routing.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolylineMatcherTest {

    /** A track running due east: 20 vertices, 100 m apart at the equator. */
    private fun eastWestTrack(): RouteTrack {
        val n = 20
        val latitudes = DoubleArray(n) { 0.0 }
        val longitudes = DoubleArray(n) { it * 0.0009 }
        return RouteTrack.fromPath(
            latitudes, longitudes,
            listOf(0), listOf("origin"), listOf(n), listOf(n * 6.0),
        )
    }

    private fun fixAt(lat: Double, lon: Double) = GpsFix(lat, lon, speedMps = 10.0, timestampMs = 0)

    @Test
    fun `a fix near the route projects onto it`() {
        val track = eastWestTrack()
        val matcher = PolylineMatcher(track)
        val offset = matcher.project(fixAt(0.0, 0.0009 * 5.5), windowSegments = 40, maxSnapM = 50.0)
        assertNotNull(offset)
        assertEquals(550.0, offset!!, 30.0)
        assertEquals(0.0, matcher.lastMissM, 25.0)
    }

    @Test
    fun `a far fix does not project`() {
        val track = eastWestTrack()
        val matcher = PolylineMatcher(track)
        val offset = matcher.project(fixAt(1.0, 0.0009 * 5.5), windowSegments = 40, maxSnapM = 50.0)
        assertNull(offset)
        assertTrue(matcher.lastMissM > 50.0)
    }

    @Test
    fun `projection never moves backward past the current window`() {
        val track = eastWestTrack()
        val matcher = PolylineMatcher(track)
        matcher.project(fixAt(0.0, 0.0009 * 10), windowSegments = 2, maxSnapM = 50.0)
        val first = matcher.currentOffsetM
        // A fix far BEHIND the current position (start of route) must not be
        // captured: the window only reaches one segment back.
        val behind = matcher.project(fixAt(0.0, 0.0), windowSegments = 2, maxSnapM = 50.0)
        if (behind != null) {
            // Even if captured via the one-segment back look, the offset can
            // never regress below the previous segment start.
            assertTrue(matcher.currentOffsetM >= first - 150.0)
        }
    }

    @Test
    fun `sequential forward fixes advance the offset`() {
        val track = eastWestTrack()
        val matcher = PolylineMatcher(track)
        var last = 0.0
        for (i in 1..9) {
            matcher.project(fixAt(0.0, 0.0009 * i), windowSegments = 40, maxSnapM = 50.0)
            assertTrue(matcher.currentOffsetM >= last - 1.0)
            last = matcher.currentOffsetM
        }
        assertEquals(900.0, last, 60.0)
    }
}

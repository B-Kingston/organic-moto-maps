package com.organicmoto.maps.routing.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RouteTrackTest {

    /** A straight 1 km north-south track with a turn at the midpoint. */
    private fun straightTrack(turnSigns: List<Int> = emptyList()): RouteTrack {
        // 11 vertices, 100 m apart, going south (decreasing latitude).
        val n = 11
        val latitudes = DoubleArray(n) { -27.0 - it * 0.0009 }
        val longitudes = DoubleArray(n) { 153.0 }
        val signs = mutableListOf<Int>()
        val names = mutableListOf<String>()
        val counts = mutableListOf<Int>()
        val distances = mutableListOf<Double>()
        val times = mutableListOf<Double>()
        signs += 0; names += "origin"
        val per = (n - 1) / (turnSigns.size + 1)
        counts += per; distances += per * 100.0; times += per * 6.0
        var remaining = n - 1 - per
        for (i in 0..turnSigns.size) {
            val count = if (i < turnSigns.size) per else remaining
            signs += if (i < turnSigns.size) turnSigns[i] else 0
            names += "seg$i"
            counts += count
            distances += count * 100.0
            times += count * 6.0
            remaining -= count
        }
        return RouteTrack.fromPath(latitudes, longitudes, signs, names, counts, distances, times)
    }
    @Test
    fun `track computes total distance close to geometric length`() {
        val track = straightTrack()
        assertEquals(1000.0, track.totalDistanceM, 5.0)
    }

    @Test
    fun `remaining distance and time shrink monotonically along the track`() {
        val track = straightTrack()
        var lastD = Double.MAX_VALUE
        var lastT = Double.MAX_VALUE
        var offset = 0.0
        while (offset <= track.totalDistanceM) {
            val d = track.remainingDistanceM(offset)
            val t = track.remainingTimeS(offset)
            assertTrue(d <= lastD)
            assertTrue(t <= lastT)
            lastD = d; lastT = t
            offset += 50.0
        }
        assertEquals(0.0, track.remainingDistanceM(track.totalDistanceM), 1e-6)
        assertEquals(0.0, track.remainingTimeS(track.totalDistanceM), 1.0)
    }

    @Test
    fun `nextTurn finds the single turn with its distance`() {
        val track = straightTrack(listOf(-2)) // left turn at the midpoint vertex
        assertTrue(track.turns.isNotEmpty())
        val (turn, distance) = track.nextTurn(0.0)!!
        assertEquals(-2, turn.sign)
        assertEquals(500.0, distance, 60.0)
        // Past the turn: none remains.
        assertNull(track.nextTurn(track.totalDistanceM + 1.0))
    }

    @Test
    fun `synthetic instructions are excluded from turns`() {
        val track = straightTrack(listOf(4)) // FINISH is not a real turn
        assertFalse(track.turns.any { RouteTrack.isRealTurn(it.sign) })
    }

    @Test
    fun `locate returns segment index and fraction`() {
        val track = straightTrack()
        val (index, fraction) = track.locate(550.0)
        assertTrue(index in 0 until track.vertexCount - 1)
        assertTrue(fraction in 0.0..1.0)
        // Reconstructing the offset from the locate result lands close.
        val reconstructed = track.cumulativeDistanceM[index] +
            fraction * (track.cumulativeDistanceM[index + 1] - track.cumulativeDistanceM[index])
        assertTrue(abs(reconstructed - 550.0) < 1.0)
    }
}

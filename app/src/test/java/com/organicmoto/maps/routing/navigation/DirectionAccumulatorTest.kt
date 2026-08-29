package com.organicmoto.maps.routing.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionAccumulatorTest {

    private fun fix(lat: Double, lon: Double, t: Long) = GpsFix(lat, lon, timestampMs = t)

    @Test
    fun `single fix has no direction`() {
        val acc = DirectionAccumulator()
        acc.push(fix(0.0, 0.0, 0))
        assertTrue(acc.headingDeg().isNaN())
    }

    @Test
    fun `jitter below the minimum segment is ignored`() {
        val acc = DirectionAccumulator()
        acc.push(fix(0.0, 0.0, 0))
        // ~1 m of jitter: below MIN_VALID_SEGMENT_M.
        repeat(5) { acc.push(fix(0.000005, 0.000005, (it + 1) * 1000L)) }
        assertTrue(acc.headingDeg().isNaN())
    }

    @Test
    fun `eastward movement yields a 90 degree heading`() {
        val acc = DirectionAccumulator()
        acc.push(fix(0.0, 0.0, 0))
        // ~11 m steps east accumulate past the 70 m track length.
        for (i in 1..10) {
            acc.push(fix(0.0, i * 0.0001, i * 1000L))
        }
        assertEquals(90.0, acc.headingDeg(), 2.0)
    }

    @Test
    fun `a teleport resets the track`() {
        val acc = DirectionAccumulator()
        acc.push(fix(0.0, 0.0, 0))
        for (i in 1..10) acc.push(fix(0.0, i * 0.0001, i * 1000L))
        assertEquals(90.0, acc.headingDeg(), 2.0)
        // Jump ~1 degree east: far beyond MAX_VALID_SEGMENT_M.
        acc.push(fix(0.0, 1.0, 20_000L))
        assertTrue(acc.headingDeg().isNaN())
    }

    @Test
    fun `clear removes all state`() {
        val acc = DirectionAccumulator()
        acc.push(fix(0.0, 0.0, 0))
        acc.push(fix(0.0, 0.001, 1000))
        acc.clear()
        assertTrue(acc.headingDeg().isNaN())
    }
}

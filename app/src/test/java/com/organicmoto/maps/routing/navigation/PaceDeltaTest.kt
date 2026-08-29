package com.organicmoto.maps.routing.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract of the live pace delta: actual riding time minus the model time
 * budgeted for the distance covered, positive when slower than plan, EMA
 * smoothed, frozen while stationary, resilient to gaps.
 */
class PaceDeltaTest {

    /** Straight 2 km track, 21 vertices 100 m apart, model pace 100 m / 6 s. */
    private fun track(): RouteTrack {
        val n = 21
        val latitudes = DoubleArray(n) { -27.0 - it * 0.0009 }
        val longitudes = DoubleArray(n) { 153.0 }
        val signs = IntArray(n) { if (it == 0) 0 else 0 }.toList()
        val names = List(n) { "seg$it" }
        val counts = List(n) { if (it == 0) n - 1 else 0 }
        val distances = List(n) { if (it == 0) (n - 1) * 100.0 else 0.0 }
        val times = List(n) { if (it == 0) (n - 1) * 6.0 else 0.0 }
        return RouteTrack.fromPath(latitudes, longitudes, signs, names, counts, distances, times)
    }

    private fun fix(lat: Double, speed: Double, t: Long) =
        GpsFix(lat, 153.0, speedMps = speed, timestampMs = t)

    /** Feeds fixes riding exactly at model pace: delta must stay ~0. */
    @Test
    fun `riding at model pace keeps delta near zero`() {
        val session = NavigationSession()
        session.setRebuildListener { }
        session.startRoute(track())
        session.beginFollowing()
        // Model pace is 100 m per 6 s; lat step 0.0009 deg ~= 100 m.
        var t = 0L
        for (i in 0 until 40) {
            t += 6_000
            session.onFix(fix(-27.0 - i * 0.0009, speed = 100.0 / 6.0, t = t))
        }
        val delta = session.snapshot.value.paceDeltaS
        assertFalse(delta.isNaN())
        assertEquals(0.0, delta, 5.0)
    }

    /** Riding half model pace must produce a clearly positive delta. */
    @Test
    fun `riding slower than plan loses time`() {
        val session = NavigationSession()
        session.setRebuildListener { }
        session.startRoute(track())
        session.beginFollowing()
        var t = 0L
        for (i in 0 until 40) {
            t += 12_000 // double the budgeted time per 100 m
            session.onFix(fix(-27.0 - i * 0.0009, speed = 100.0 / 12.0, t = t))
        }
        val delta = session.snapshot.value.paceDeltaS
        assertFalse(delta.isNaN())
        assertTrue("expected lost time, was $delta", delta > 60.0)
    }

    /** Riding double model pace must produce a negative delta. */
    @Test
    fun `riding faster than plan gains time`() {
        val session = NavigationSession()
        session.setRebuildListener { }
        session.startRoute(track())
        session.beginFollowing()
        var t = 0L
        for (i in 0 until 40) {
            t += 3_000 // half the budgeted time per 100 m
            session.onFix(fix(-27.0 - i * 0.0009, speed = 100.0 / 3.0, t = t))
        }
        val delta = session.snapshot.value.paceDeltaS
        assertFalse(delta.isNaN())
        assertTrue("expected gained time, was $delta", delta < -30.0)
    }

    /** Stationary fixes freeze the delta instead of charging stop time. */
    @Test
    fun `stops do not charge lost time`() {
        val session = NavigationSession()
        session.setRebuildListener { }
        session.startRoute(track())
        session.beginFollowing()
        var t = 0L
        for (i in 0 until 30) {
            t += 6_000
            session.onFix(fix(-27.0 - i * 0.0009, speed = 100.0 / 6.0, t = t))
        }
        val before = session.snapshot.value.paceDeltaS
        assertFalse(before.isNaN())
        // Parked for 5 minutes: delta must not move.
        for (i in 0 until 50) {
            t += 6_000
            session.onFix(fix(-27.0 - 29 * 0.0009, speed = 0.0, t = t))
        }
        assertEquals(before, session.snapshot.value.paceDeltaS, 0.5)
    }

    /** A long gap (rebuild freeze, tunnel) must not corrupt the delta. */
    @Test
    fun `long gap resyncs without charging`() {
        val session = NavigationSession()
        session.setRebuildListener { }
        session.startRoute(track())
        session.beginFollowing()
        var t = 0L
        for (i in 0 until 30) {
            t += 6_000
            session.onFix(fix(-27.0 - i * 0.0009, speed = 100.0 / 6.0, t = t))
        }
        val before = session.snapshot.value.paceDeltaS
        t += 10 * 60_000 // ten-minute gap
        session.onFix(fix(-27.0 - 30 * 0.0009, speed = 100.0 / 6.0, t = t))
        assertEquals(before, session.snapshot.value.paceDeltaS, 0.5)
    }

    /** Fresh route start publishes NaN until enough distance accrues. */
    @Test
    fun `delta is NaN before minimum distance`() {
        val session = NavigationSession()
        session.setRebuildListener { }
        session.startRoute(track())
        session.beginFollowing()
        session.onFix(fix(-27.0, speed = 100.0 / 6.0, t = 1_000))
        session.onFix(fix(-27.0009, speed = 100.0 / 6.0, t = 7_000))
        assertTrue(session.snapshot.value.paceDeltaS.isNaN())
    }
}

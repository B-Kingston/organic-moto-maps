package com.organicmoto.maps.routing.navigation

import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationSessionTest {

    /** Track due east, 20 vertices 100 m apart, one right turn at vertex 10. */
    private fun track(): RouteTrack {
        val n = 20
        return RouteTrack.fromPath(
            DoubleArray(n) { 0.0 },
            DoubleArray(n) { it * 0.0009 },
            listOf(0, 2, 0),
            listOf("origin", "turn street", "finish"),
            listOf(10, 1, 9),
            listOf(60.0, 0.0, 54.0),
        )
    }

    private fun fix(lat: Double, lon: Double, speed: Double = 10.0, t: Long = 0) =
        GpsFix(lat, lon, speedMps = speed, timestampMs = t)

    private fun sessionWithTrack(): NavigationSession {
        val session = NavigationSession()
        session.startRoute(track())
        session.beginFollowing()
        return session
    }

    @Test
    fun `on-route fixes produce a snapshot with turn info and metrics`() {
        val session = sessionWithTrack()
        session.onFix(fix(0.0, 0.0009 * 5))
        val snap = session.snapshot.value
        assertEquals(NavigationState.OnRoute, snap.state)
        assertTrue(snap.remainingDistanceM in 1400.0..1510.0)
        assertTrue(snap.remainingTimeS > 0.0)
        assertTrue(snap.completionPercent >= 0)
    }

    @Test
    fun `next turn appears with its distance`() {
        val session = sessionWithTrack()
        session.onFix(fix(0.0, 0.0009 * 5))
        val turn = session.snapshot.value.turn
        assertTrue(turn != null)
        assertEquals(2, turn!!.sign)
        assertEquals(500.0, turn.distanceM, 60.0)
        assertEquals(turn, session.snapshot.value.upcomingTurns.first())
    }

    @Test
    fun `matched bearing follows the route segment`() {
        val session = sessionWithTrack()
        // Eastbound track: matched bearing must be ~90 degrees.
        session.onFix(fix(0.0, 0.0009 * 5, speed = 0.5))
        val bearing = session.snapshot.value.bearingDeg
        assertEquals(90.0, bearing, 1.0)
    }

    @Test
    fun `isolated off-route fixes do not trigger a rebuild`() {
        val session = sessionWithTrack()
        var rebuilds = 0
        session.setRebuildListener { rebuilds++ }
        // Wander far off but only a few times; slow speed grows the counter by 1.
        repeat(8) { i ->
            session.onFix(fix(0.01, 0.0009 * 5 + i * 0.0001, speed = 0.0, t = i * 1000L))
        }
        assertEquals(0, rebuilds)
        assertEquals(NavigationState.OnRoute, session.snapshot.value.state)
    }

    @Test
    fun `sustained off-route movement triggers exactly one rebuild`() {
        val session = sessionWithTrack()
        val requests = mutableListOf<RebuildRequest>()
        session.setRebuildListener { requests.add(it) }
        // Fast movement off-route: counter grows by 2 per fix -> 6 fixes.
        repeat(6) { i ->
            session.onFix(fix(0.01, 0.0009 * 5 + i * 0.0005, speed = 15.0, t = i * 1000L))
        }
        assertEquals(1, requests.size)
        assertEquals(NavigationState.NeedRebuild, session.snapshot.value.state)
    }

    @Test
    fun `applyRebuiltRoute returns the session to OnRoute and banks completion`() {
        val session = sessionWithTrack()
        val requests = mutableListOf<RebuildRequest>()
        session.setRebuildListener { requests.add(it) }
        repeat(6) { i ->
            session.onFix(fix(0.01, 0.0009 * 5 + i * 0.0005, speed = 15.0, t = i * 1000L))
        }
        assertTrue(requests.isNotEmpty())
        session.applyRebuiltRoute(track(), coveredDistanceM = 500.0)
        assertEquals(NavigationState.OnRoute, session.snapshot.value.state)
        session.onFix(fix(0.0, 0.0009 * 5))
        val snap = session.snapshot.value
        assertEquals(NavigationState.OnRoute, snap.state)
        assertTrue(snap.remainingDistanceM <= 1510.0)
    }

    @Test
    fun `reaching the finish finishes the session`() {
        val session = sessionWithTrack()
        session.onFix(fix(0.0, 0.0009 * 19))
        assertEquals(NavigationState.Finished, session.snapshot.value.state)
    }

    @Test
    fun `eta floors at the minimum`() {
        val session = NavigationSession(NavigationSession.Settings(minimumEtaS = 60.0))
        session.startRoute(track())
        session.beginFollowing()
        session.onFix(fix(0.0, 0.0009 * 19))
        assertTrue(session.snapshot.value.remainingTimeS >= 60.0)
    }

    @Test
    fun `stop resets to idle`() {
        val session = sessionWithTrack()
        session.onFix(fix(0.0, 0.0009 * 5))
        session.stop()
        assertEquals(NavigationState.Idle, session.snapshot.value.state)
        assertNull(session.snapshot.value.turn)
    }

    @Test
    fun `a new ride does not inherit completion from the previous ride`() {
        val session = sessionWithTrack()
        session.onFix(fix(0.0, 0.0009 * 10))
        assertTrue(session.snapshot.value.completionPercent >= 50)

        session.stop()
        session.startRoute(track())
        session.beginFollowing()
        session.onFix(fix(0.0, 0.0))

        assertEquals(0, session.snapshot.value.completionPercent)
    }

    @Test
    fun `rebuild banks covered distance once and enters rebuilding state`() {
        val session = sessionWithTrack()
        session.onFix(fix(0.0, 0.0009 * 5))
        repeat(6) { index ->
            session.onFix(fix(0.01, 0.0009 * 5 + index * 0.0005, speed = 15.0, t = index * 1_000L))
        }
        assertEquals(NavigationState.NeedRebuild, session.snapshot.value.state)

        val covered = session.currentRouteCoveredM()
        session.markRebuilding()
        assertEquals(NavigationState.Rebuilding, session.snapshot.value.state)
        session.applyRebuiltRoute(track(), covered)
        session.onFix(fix(0.0, 0.0009 * 5))

        assertEquals(40, session.snapshot.value.completionPercent)
    }
}

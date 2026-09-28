package com.organicmoto.maps.routing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.graphhopper.util.Instruction
import com.organicmoto.maps.routing.navigation.GpsFix
import com.organicmoto.maps.routing.navigation.NavigationSession
import com.organicmoto.maps.routing.navigation.NavigationState
import com.organicmoto.maps.routing.navigation.RouteTrackFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the full guidance engine against the REAL routing graph: routes a
 * corpus corridor, converts the winner to a RouteTrack, then replays a
 * synthetic ride along the route geometry and verifies the engine stays on
 * route, emits turn info, finishes, and reroutes when pushed off the road.
 */
@RunWith(AndroidJUnit4::class)
class NavigationSessionOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixesAlongTrack(track: com.organicmoto.maps.routing.navigation.RouteTrack): List<GpsFix> {
        val fixes = mutableListOf<GpsFix>()
        val n = track.vertexCount
        // Walk the route geometry vertex by vertex with sub-segment
        // interpolation so consecutive fixes are a few tens of metres apart.
        for (v in 0 until n - 1) {
            val steps = 3
            for (s in 0 until steps) {
                val t = s.toDouble() / steps
                fixes += GpsFix(
                    lat = track.latitudes[v] + t * (track.latitudes[v + 1] - track.latitudes[v]),
                    lon = track.longitudes[v] + t * (track.longitudes[v + 1] - track.longitudes[v]),
                    speedMps = 14.0,
                    timestampMs = fixes.size * 1000L,
                )
            }
        }
        fixes += GpsFix(
            lat = track.latitudes[n - 1],
            lon = track.longitudes[n - 1],
            speedMps = 14.0,
            timestampMs = fixes.size * 1000L,
        )
        return fixes
    }

    @Test(timeout = 300_000)
    fun ridingTheRoutedTrackStaysOnRouteAndFinishes() {
        val entry = ROUTE_CORPUS.first()
        val router = GraphHopperRouter(context)
        val path = router.route(entry.from, entry.to).routes.first()
        val track = RouteTrackFactory.fromPath(path)
        assertTrue("track must have geometry", track.vertexCount >= 2)

        // This committed corridor includes the rare sign types that prompted
        // the dedicated HUD symbols. Keep checking the real graph output so
        // their metadata is not covered only by synthetic instructions.
        assertTrue(
            "Brisbane to Mount Glorious should include a keep-left fork",
            track.turns.any { it.sign == Instruction.KEEP_LEFT },
        )
        assertTrue(
            "Brisbane to Mount Glorious should include a slight veer",
            track.turns.any {
                it.sign == Instruction.TURN_SLIGHT_RIGHT && it.turnAngleDeg?.isFinite() == true
            },
        )
        assertTrue(
            "Brisbane to Mount Glorious should include a roundabout with a numbered exit",
            track.turns.any {
                it.sign == Instruction.USE_ROUNDABOUT &&
                    (it.roundaboutExitNumber ?: 0) > 0 && it.roundaboutClockwise == true
            },
        )

        val session = NavigationSession()
        session.startRoute(track)
        session.beginFollowing()
        var sawTurns = false
        var sawRoundaboutExit = false
        var sawRoundaboutDirection = false
        var sawMeasuredSlightVeer = false
        for (fix in fixesAlongTrack(track)) {
            session.onFix(fix)
            val snap = session.snapshot.value
            if (snap.turn != null) sawTurns = true
            if (snap.turn?.sign == Instruction.USE_ROUNDABOUT &&
                (snap.turn.roundaboutExitNumber ?: 0) > 0
            ) {
                sawRoundaboutExit = true
            }
            if (snap.turn?.sign == Instruction.USE_ROUNDABOUT && snap.turn.roundaboutClockwise == true) {
                sawRoundaboutDirection = true
            }
            if (snap.turn?.sign == Instruction.TURN_SLIGHT_RIGHT &&
                snap.turn.turnAngleDeg?.isFinite() == true
            ) {
                sawMeasuredSlightVeer = true
            }
        }
        assertEquals("riding the route must finish", NavigationState.Finished, session.snapshot.value.state)
        assertEquals(100, session.snapshot.value.completionPercent)
        // A 40 km Brisbane->Mount Glorious ride passes real maneuvers.
        assertTrue("expected some turn info en route, saw none", sawTurns || track.turns.isEmpty())
        assertTrue("roundabout exit metadata must reach guidance snapshots", sawRoundaboutExit)
        assertTrue("roundabout circulation direction must reach guidance snapshots", sawRoundaboutDirection)
        assertTrue("slight-veer geometry must reach guidance snapshots", sawMeasuredSlightVeer)
    }

    @Test(timeout = 300_000)
    fun ridingAwayFromTheRouteTriggersRebuild() {
        val entry = ROUTE_CORPUS.first()
        val router = GraphHopperRouter(context)
        val path = router.route(entry.from, entry.to).routes.first()
        val track = RouteTrackFactory.fromPath(path)

        val session = NavigationSession()
        session.startRoute(track)
        session.beginFollowing()
        val requests = mutableListOf<com.organicmoto.maps.routing.navigation.RebuildRequest>()
        session.setRebuildListener { requests.add(it) }

        // Follow the first few fixes, then cut perpendicularly away from the
        // route (~0.01 deg ≈ 1.1 km off) at riding speed.
        val fixes = fixesAlongTrack(track)
        for (i in 0 until 6) session.onFix(fixes[i])
        var i = 6
        var driven = 0.0
        while (requests.isEmpty() && i < fixes.size) {
            val base = fixes[i]
            driven += 0.0002
            session.onFix(
                base.copy(lat = base.lat + driven.coerceAtMost(0.01)),
            )
            i++
        }
        assertEquals("sustained off-route riding must request a rebuild", 1, requests.size)
        assertEquals(NavigationState.NeedRebuild, session.snapshot.value.state)
        // The rebuild request points at a sensible last-good position.
        assertTrue(requests[0].lat.isFinite() && requests[0].lon.isFinite())
    }
}

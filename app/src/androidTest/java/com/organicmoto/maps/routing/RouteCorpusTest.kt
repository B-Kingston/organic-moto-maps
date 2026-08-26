package com.organicmoto.maps.routing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.organicmoto.maps.storage.GeoPoint
import com.organicmoto.maps.storage.PolylineCodec
import com.organicmoto.maps.storage.RouteSimilarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RouteCorpusTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 300_000)
    fun corpusRoutesAreValidAtEachDetent() {
        val router = GraphHopperRouter(context)
        ROUTE_CORPUS.take(10).forEach { entry ->
            listOf(0, 1, 2, 4).forEach { detent ->
                val result = router.route(entry.from, entry.to, detent.toDouble())
                assertTrue("${entry.name} returned no routes", result.routes.isNotEmpty())
                result.routes.forEach { path ->
                    assertTrue("${entry.name} has too few points", path.points.size() >= 2)
                    assertTrue("${entry.name} has no distance", path.distance > 0.0)
                    assertTrue("${entry.name} has no duration", path.time > 0L)
                    val points = path.points.toGeoPoints()
                    assertTrue(points.all { point ->
                        point.lat.isFinite() && point.lon.isFinite() &&
                            point.lat in -90.0..90.0 && point.lon in -180.0..180.0
                    })
                    assertTrue(RouteSimilarity.haversineMeters(points.first(), entry.from.toGeoPoint()) <= 5_000.0)
                    assertTrue(RouteSimilarity.haversineMeters(points.last(), entry.to.toGeoPoint()) <= 5_000.0)
                    assertRoundTrip(points)
                    assertTrue(path.distance / 1_000.0 in entry.baselineKm * 0.5..entry.baselineKm * 2.5)
                    assertTrue(path.time / 3_600_000.0 in entry.baselineHours * 0.4..entry.baselineHours * 4.0)
                }
            }
        }
    }

    @Test(timeout = 120_000)
    fun identicalEndpointsAreRejectedCleanly() {
        val entry = ROUTE_CORPUS[10]
        val failure = runCatching { GraphHopperRouter(context).route(entry.from, entry.to) }.exceptionOrNull()
        assertTrue("expected identical endpoints to fail, got $failure", failure is IllegalStateException)
        assertFalse(failure!!.message.orEmpty().contains("OutOfMemory"))
    }

    @Test(timeout = 120_000)
    fun outOfRegionIsRejectedCleanly() {
        val entry = ROUTE_CORPUS[11]
        val failure = runCatching { GraphHopperRouter(context).route(entry.from, entry.to) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message.orEmpty().contains("Routing failed"))
    }

    @Test(timeout = 300_000)
    fun positiveDetentsKeepRoutesDistinctWhenMultipleCandidatesExist() {
        val router = GraphHopperRouter(context)
        ROUTE_CORPUS.filter { it.alternativesExpected }.forEach { entry ->
            val routes = router.route(entry.from, entry.to, 2.0).routes
            if (routes.size >= 2) {
                for (i in routes.indices) {
                    assertTrue(routes[i].points.size() >= 2)
                    for (j in i + 1 until routes.size) {
                        val left = AlternativePolicy.cachedRoute(routes[i])
                        val right = AlternativePolicy.cachedRoute(routes[j])
                        assertTrue(
                            "${entry.name} alternatives share all roads",
                            AlternativePolicy.sharedRoadFraction(left.edgeDistances, right.edgeDistances) < 0.999,
                        )
                    }
                }
            }
        }
    }

    @Test(timeout = 300_000)
    fun detentResultsAreCachedAndDeterministic() {
        val entry = ROUTE_CORPUS.first()
        val router = GraphHopperRouter(context)
        val first = router.route(entry.from, entry.to, 0.0)
        val second = router.route(entry.from, entry.to, 0.0)
        assertEquals(first.routes.size, second.routes.size)
        first.routes.zip(second.routes).forEach { (left, right) ->
            assertEquals(left.distance, right.distance, 0.0)
            assertEquals(left.time, right.time)
        }
    }

    @Test(timeout = 300_000)
    fun savedGeometryMatchingSelectsClosestCandidate() {
        val entry = ROUTE_CORPUS.first()
        val routes = GraphHopperRouter(context).route(entry.from, entry.to, 2.0).routes
        if (routes.size > 1) {
            val expected = 1
            val stored = routes[expected].points.toGeoPoints()
            val candidates = routes.map { it.points.toGeoPoints() }
            assertEquals(expected, RouteSimilarity.bestMatchIndex(stored, candidates))
        }
    }

    @Ignore("Re-run after graph rebuilds to recalibrate corpus baselines")
    @Test(timeout = 300_000)
    fun calibrationProbe() {
        val router = GraphHopperRouter(context)
        ROUTE_CORPUS.take(10).forEach { entry ->
            val fastest = router.route(entry.from, entry.to, 0.0)
            val alternatives = router.route(entry.from, entry.to, 2.0)
            println(
                "${entry.name}: distance_meters=${fastest.routes.first().distance}, " +
                    "duration_ms=${fastest.routes.first().time}, routes=${fastest.routes.size}, " +
                    "alternatives=${alternatives.routes.size >= 2}",
            )
        }
    }

    private fun assertRoundTrip(points: List<GeoPoint>) {
        val decoded = PolylineCodec.decode(PolylineCodec.encode(points))
        assertTrue(decoded.size >= 2)
        points.zip(decoded).forEach { (original, restored) ->
            assertEquals(original.lat, restored.lat, 5.1e-6)
            assertEquals(original.lon, restored.lon, 5.1e-6)
        }
    }

    private fun com.graphhopper.util.shapes.GHPoint.toGeoPoint() = GeoPoint(lat, lon)

    private fun com.graphhopper.util.PointList.toGeoPoints(): List<GeoPoint> =
        List(size()) { index -> GeoPoint(getLat(index), getLon(index)) }
}

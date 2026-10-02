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
import kotlin.math.abs

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
                result.routes.forEachIndexed { routeIndex, path ->
                    assertTrue("${entry.name} has too few points", path.points.size() >= 2)
                    assertTrue("${entry.name} has no distance", path.distance > 0.0)
                    assertTrue("${entry.name} has no duration", path.time > 0L)
                    val roadClasses = path.pathDetails["road_class"].orEmpty()
                    assertTrue("${entry.name} must provide road classes for full-width ride highlighting", roadClasses.isNotEmpty())
                    assertEquals(0, roadClasses.first().first)
                    assertEquals(path.points.size() - 1, roadClasses.last().last)
                    assertTrue(roadClasses.all { it.last > it.first })
                    assertTrue(roadClasses.zipWithNext().all { (before, after) -> before.last == after.first })
                    val points = path.points.toGeoPoints()
                    assertTrue(points.all { point ->
                        point.lat.isFinite() && point.lon.isFinite() &&
                            point.lat in -90.0..90.0 && point.lon in -180.0..180.0
                    })
                    assertTrue(RouteSimilarity.haversineMeters(points.first(), entry.from.toGeoPoint()) <= 5_000.0)
                    assertTrue(RouteSimilarity.haversineMeters(points.last(), entry.to.toGeoPoint()) <= 5_000.0)
                    assertRoundTrip(points)
                    val distanceKm = path.distance / 1_000.0
                    assertTrue(
                        "${entry.name} detent $detent route $routeIndex distance ${distanceKm}km",
                        distanceKm in entry.baselineKm * 0.5..entry.baselineKm * 2.5,
                    )
                    val durationHours = path.time / 3_600_000.0
                    assertTrue(
                        "${entry.name} detent $detent route $routeIndex duration ${durationHours}h",
                        durationHours in entry.baselineHours * 0.4..entry.baselineHours * 4.0,
                    )
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

    @Test(timeout = 300_000)
    fun importedNambourLoopRoutesThroughEveryStop() {
        // Stops from the supplied Furkot Nambour GPX. The old importer reduced
        // this closed ride to one endpoint request and lost the loop.
        val stops = listOf(
            com.graphhopper.util.shapes.GHPoint(-26.625491, 152.958018), // Nambour
            com.graphhopper.util.shapes.GHPoint(-26.954705, 152.777724), // Woodford
            com.graphhopper.util.shapes.GHPoint(-27.195919, 152.824545), // Dayboro
            com.graphhopper.util.shapes.GHPoint(-27.039270, 152.860723), // Wamuran
            com.graphhopper.util.shapes.GHPoint(-26.625618, 152.958156), // return to Nambour
        )
        val path = GraphHopperRouter(context).route(
            from = stops.first(),
            to = stops.last(),
            viaPoints = stops.drop(1).dropLast(1),
        ).routes.single()
        val routed = path.points.toGeoPoints()

        assertTrue("imported loop should remain a full ride", path.distance > 100_000.0)
        stops.forEach { stop ->
            val nearest = routed.minOf { RouteSimilarity.haversineMeters(it, stop.toGeoPoint()) }
            assertTrue("route missed imported stop $stop by ${nearest}m", nearest < 1_000.0)
        }
    }

    /**
     * Lane guidance end to end on the shipped graph: the production router
     * requests the `moto_lanes` detail, the lane-aware import stored it, and
     * the track factory turns it into recommendations. Brisbane CBD to
     * Annerley crosses several mapped multi-lane approaches (Merivale Street
     * carries turn:lanes arrows), so a graph imported with the stock
     * GraphHopper command, or a router that drops the path detail, fails here.
     */
    @Test(timeout = 300_000)
    fun cbdRouteCarriesLaneGuidanceFromTheGraph() {
        val path = GraphHopperRouter(context).route(
            com.graphhopper.util.shapes.GHPoint(-27.4698, 153.0251),
            com.graphhopper.util.shapes.GHPoint(-27.5598, 153.0811),
        ).routes.first()
        assertTrue(
            "route must carry the moto_lanes path detail",
            path.pathDetails[MOTO_LANES_DETAIL].orEmpty().any { it.value is String },
        )
        val turns = com.organicmoto.maps.routing.navigation.RouteTrackFactory.fromPath(path).turns
        val laned = turns.mapNotNull { it.lanes }
        assertTrue("expected several multi-lane turn approaches, got ${laned.size}", laned.size >= 3)
        assertTrue("expected at least one turn:lanes-marked approach", laned.any { it.marked })
        laned.forEach { guidance ->
            assertTrue(guidance.lanes.size >= 2)
            assertTrue(guidance.leftHandTraffic)
            assertTrue(guidance.recommendedIndices.isNotEmpty())
        }
    }

    /**
     * Gold-baseline drift alert. Wide corpus bands only catch catastrophic
     * rerouting; a quiet road-preference change that adds +40% distance stays
     * green forever. These alert at ±15% against values measured on device
     * and committed above. The GOLD_PROBE lines make recalibration trivial:
     * run once, replace the golds with the printed numbers.
     */
    @Test(timeout = 900_000)
    fun fastestRoutesMatchCommittedGoldBaselines() {
        val router = GraphHopperRouter(context)
        val missing = mutableListOf<String>()
        // Every committed gold must be exercised. Entries without golds (the
        // deliberate degenerate cases in ROUTE_CORPUS) are skipped, so future
        // corpus additions stay covered without touching this loop.
        ROUTE_CORPUS.filter { it.goldDistanceMeters != null || it.goldDurationMillis != null }.forEach { entry ->
            val result = router.route(entry.from, entry.to, 0.0)
            val path = result.routes.first()
            println(
                "GOLD_PROBE ${entry.name}: distance_meters=${path.distance}, " +
                    "duration_ms=${path.time}, routes=${result.routes.size}",
            )
            val goldDistance = entry.goldDistanceMeters
            val goldDuration = entry.goldDurationMillis
            if (goldDistance == null || goldDuration == null) {
                missing += entry.name
                return@forEach
            }
            assertWithinAlertBand(entry.name, "distance", path.distance, goldDistance)
            assertWithinAlertBand(entry.name, "duration", path.time.toDouble(), goldDuration.toDouble())
        }
        assertTrue(
            "corpus entries still lack committed gold baselines: $missing. " +
                "Run this suite once on an emulator and fill RouteCorpusEntry golds from the GOLD_PROBE lines.",
            missing.isEmpty(),
        )
    }

    private fun assertWithinAlertBand(entry: String, label: String, actual: Double, gold: Double) {
        val relative = abs(actual - gold) / gold
        assertTrue(
            "$entry $label drifted from its gold baseline: actual=$actual gold=$gold " +
                "(%.1f%% > 15%%). If OSM data or weighting changed deliberately, ".format(relative * 100.0) +
                "re-run calibrationProbe and update ROUTE_CORPUS golds.",
            relative <= 0.15,
        )
    }

    @Ignore("Re-run after graph rebuilds to recalibrate corpus baselines")
    @Test(timeout = 300_000)
    fun calibrationProbe() {
        val router = GraphHopperRouter(context)
        ROUTE_CORPUS.take(10).forEach { entry ->
            val fastest = router.route(entry.from, entry.to, 0.0)
            val alternatives = router.route(entry.from, entry.to, 2.0)
            println(
                "GOLD_PROBE ${entry.name}: distance_meters=${fastest.routes.first().distance}, " +
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

package com.organicmoto.maps.fuzz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.organicmoto.maps.geocoding.GeocodeSearchController
import com.organicmoto.maps.geocoding.GeocoderIndex
import com.organicmoto.maps.routing.GraphHopperRouter
import com.organicmoto.maps.routing.ROUTE_CORPUS
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class PerfSmokeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 900_000)
    fun coldWarmRouteAndGeocoderStayWithinCeilings() = runBlocking {
        val entry = ROUTE_CORPUS.first()
        File(context.filesDir, "gh-cache").deleteRecursively()
        val router = GraphHopperRouter(context)
        var coldRouteMs = 0L
        val coldResult: com.organicmoto.maps.routing.RouteResult
        coldRouteMs = measureTimeMillis {
            coldResult = router.route(entry.from, entry.to, 0.0)
        }
        var warmRouteMs = 0L
        warmRouteMs = measureTimeMillis {
            router.route(entry.from, entry.to, 0.0)
        }
        val loadMs = measureTimeMillis { GeocoderIndex.load(context) }
        val controller = GeocodeSearchController(context)
        var queryMs = 0L
        queryMs = measureTimeMillis { controller.search("Brisbane") }
        assertTrue("cold route took ${coldRouteMs}ms", coldRouteMs <= 240_000L)
        assertTrue("warm route took ${warmRouteMs}ms", warmRouteMs <= 30_000L)
        assertTrue("geocoder load took ${loadMs}ms", loadMs <= 60_000L)
        assertTrue("geocoder query took ${queryMs}ms", queryMs <= 3_000L)
        assertTrue(coldResult.routes.isNotEmpty())
        File(context.filesDir, "perf.json").writeText(
            "{\"coldRouteMs\":$coldRouteMs,\"warmRouteMs\":$warmRouteMs," +
                "\"geocoderLoadMs\":$loadMs,\"queryMs\":$queryMs}\n",
        )
    }
}

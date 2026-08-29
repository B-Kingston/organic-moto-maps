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

    /** Intra-run self-consistency budget: second repeat must stay within. */
    private val repeatFactor = 3.0

    /**
     * The warm route and geocoder query run twice. Their second pass must stay
     * within [repeatFactor] of the first. The curve-weighted route runs once
     * here and uses the durable cross-run drift check in tools/test/run-all.sh.
     * AGP wipes the app package after each connected run, so on-device history
     * cannot survive.
     */
    @Test(timeout = 900_000)
    fun coldWarmRouteAndGeocoderStayWithinCeilings() = runBlocking {
        val entry = ROUTE_CORPUS.first()
        val curveEntry = ROUTE_CORPUS[1]
        File(context.filesDir, "gh-cache").deleteRecursively()
        val router = GraphHopperRouter(context)
        var coldResult: com.organicmoto.maps.routing.RouteResult
        val coldRouteMs = measureTimeMillis {
            coldResult = router.route(entry.from, entry.to, 0.0)
        }
        val warmRouteMs = measureTimeMillis {
            router.route(entry.from, entry.to, 0.0)
        }
        val warmRouteMsRepeat = measureTimeMillis {
            router.route(entry.from, entry.to, 0.0)
        }
        var curveResult: com.organicmoto.maps.routing.RouteResult
        val curveRouteMs = measureTimeMillis {
            curveResult = router.route(curveEntry.from, curveEntry.to, 1.0)
        }
        val loadMs = measureTimeMillis { GeocoderIndex.load(context) }
        val controller = GeocodeSearchController(context)
        val queryMs = measureTimeMillis { controller.search("Brisbane") }
        val queryMsRepeat = measureTimeMillis { controller.search("Brisbane") }

        assertTrue("cold route took ${coldRouteMs}ms", coldRouteMs <= 240_000L)
        assertTrue("warm route took ${warmRouteMs}ms", warmRouteMs <= 30_000L)
        assertTrue("curve-weighted route took ${curveRouteMs}ms", curveRouteMs <= 120_000L)
        assertTrue("geocoder load took ${loadMs}ms", loadMs <= 60_000L)
        assertTrue("geocoder query took ${queryMs}ms", queryMs <= 3_000L)
        assertTrue(coldResult.routes.isNotEmpty())
        assertTrue(curveResult.routes.isNotEmpty())

        assertSelfConsistent("warm route", warmRouteMs, warmRouteMsRepeat)
        assertSelfConsistent("geocoder query", queryMs, queryMsRepeat)

        File(context.filesDir, "perf.json").writeText(
            "{\"coldRouteMs\":$coldRouteMs,\"warmRouteMs\":$warmRouteMs," +
                "\"warmRouteMsRepeat\":$warmRouteMsRepeat,\"curveRouteMs\":$curveRouteMs," +
                "\"geocoderLoadMs\":$loadMs,\"queryMs\":$queryMs," +
                "\"queryMsRepeat\":$queryMsRepeat}\n",
        )
    }

    private fun assertSelfConsistent(label: String, firstMs: Long, secondMs: Long) {
        val budget = max(firstMs * repeatFactor, firstMs + 250.0)
        assertTrue(
            "$label regressed between repeats: ${firstMs}ms -> ${secondMs}ms " +
                "(allowed up to ${"%.0f".format(budget)}ms)",
            secondMs <= budget,
        )
    }

    /**
     * Absolute ceilings alone are smoke bounds, not regression bounds.
     * Intra-run self-consistency (see [assertSelfConsistent]) covers mid-run
     * degradation; tools/test/run-all.sh additionally compares this run
     * against build/perf.history.jsonl for cross-run drift. run-all.sh owns
     * the cross-run drift factors (DRIFT_FACTORS there); the +250 ms floor
     * and intra-run factor here cover only this suite's own repeat check.
     */
}

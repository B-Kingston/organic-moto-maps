package com.organicmoto.maps.fuzz

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.organicmoto.maps.geocoding.GeocodeSearchController
import com.organicmoto.maps.routing.GraphHopperRouter
import com.organicmoto.maps.routing.GRAPH_CACHE_COPY_LOCK
import com.organicmoto.maps.routing.ROUTE_CORPUS
import com.organicmoto.maps.storage.GeoPoint
import com.organicmoto.maps.storage.SavedRouteDraft
import com.organicmoto.maps.storage.SavedRouteRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryStressTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 900_000)
    fun repeatedRoutingStorageAndSearchStayWithinHeapBudget() = runBlocking {
        val runtime = Runtime.getRuntime()
        System.gc()
        val baseline = usedHeap(runtime)
        val router = GraphHopperRouter(context)
        synchronized(GRAPH_CACHE_COPY_LOCK) {
            repeat(15) { index ->
                router.route(ROUTE_CORPUS.first().from, ROUTE_CORPUS.first().to, 1.0)
                if ((index + 1) % 5 == 0) System.gc()
            }
        }
        val afterRouting = usedHeap(runtime)
        assertTrue("routing heap grew by ${afterRouting - baseline} bytes", afterRouting - baseline < 96L * 1024 * 1024)

        val repository = SavedRouteRepository(context)
        repeat(50) { index ->
            val saved = repository.save(draft(index))
            repository.delete(saved.id)
        }

        val geocoder = GeocodeSearchController(context)
        repeat(200) { index ->
            geocoder.search(
                when (index % 4) {
                    0 -> "Brisbane"
                    1 -> "Mount"
                    2 -> "fuel"
                    else -> "-27.4698,153.0251"
                },
            )
        }
        System.gc()
        val finalHeap = usedHeap(runtime)
        assertTrue("final heap grew by ${finalHeap - baseline} bytes", finalHeap - baseline < 96L * 1024 * 1024)
    }

    private fun usedHeap(runtime: Runtime): Long = runtime.totalMemory() - runtime.freeMemory()

    private fun draft(index: Int) = SavedRouteDraft(
        fromName = "Brisbane $index",
        from = GeoPoint(-27.0, 153.0),
        toName = "Toowoomba $index",
        to = GeoPoint(-27.5, 151.9),
        distanceMeters = 100_000.0,
        durationMillis = 3_600_000L,
        complexity = 0f,
        maxRoadSharePercent = 70f,
        blockUnpaved = false,
        points = listOf(GeoPoint(-27.0, 153.0), GeoPoint(-27.5, 151.9)),
    )
}

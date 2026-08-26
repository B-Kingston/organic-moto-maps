package com.organicmoto.maps.routing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GraphCopyTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graphDir get() = File(context.filesDir, "gh-cache")
    private val stagingDir get() = File(context.filesDir, "gh-cache.tmp")
    private val marker get() = File(graphDir, ".copy-complete")

    @Before
    fun resetGraphDirectories() {
        withGraphCache {
            graphDir.deleteRecursively()
            stagingDir.deleteRecursively()
        }
    }

    @Test(timeout = 300_000)
    fun cleanCopyCreatesMarkerAndGraph() {
        withGraphCache {
            GraphHopperRouter(context).copyGraphFromAssetsIfNeeded()
            assertTrue(graphDir.isDirectory)
            assertTrue(marker.isFile)
            assertTrue(graphDir.listFiles().orEmpty().isNotEmpty())
        }
    }

    @Test(timeout = 300_000)
    fun secondRunDoesNotRecopy() {
        withGraphCache {
            val router = GraphHopperRouter(context)
            router.copyGraphFromAssetsIfNeeded()
            val sentinel = File(graphDir, "sentinel")
            sentinel.writeText("keep")
            router.copyGraphFromAssetsIfNeeded()
            assertTrue(sentinel.isFile)
            assertTrue(sentinel.readText() == "keep")
        }
    }

    @Test(timeout = 300_000)
    fun leftoverStagingIsDiscarded() {
        withGraphCache {
            val router = GraphHopperRouter(context)
            router.copyGraphFromAssetsIfNeeded()
            stagingDir.mkdirs()
            File(stagingDir, "partial").writeText("discard")
            router.copyGraphFromAssetsIfNeeded()
            assertFalse(stagingDir.exists())
            assertTrue(marker.isFile)
        }
    }

    @Test(timeout = 300_000)
    fun missingMarkerForcesRecopy() {
        withGraphCache {
            val router = GraphHopperRouter(context)
            router.copyGraphFromAssetsIfNeeded()
            File(graphDir, ".copy-complete").delete()
            val sentinel = File(graphDir, "sentinel")
            sentinel.writeText("discard")
            router.copyGraphFromAssetsIfNeeded()
            assertFalse(sentinel.exists())
            assertTrue(marker.isFile)
        }
    }

    @Test(timeout = 300_000)
    fun routeAfterFreshCopyLoadsGraph() {
        withGraphCache {
            val router = GraphHopperRouter(context)
            router.copyGraphFromAssetsIfNeeded()
            val result = router.route(
                ROUTE_CORPUS.first().from,
                ROUTE_CORPUS.first().to,
                0.0,
            )
            assertTrue(result.routes.isNotEmpty())
        }
    }

    private inline fun <T> withGraphCache(block: () -> T): T =
        synchronized(GRAPH_CACHE_COPY_LOCK) { block() }
}

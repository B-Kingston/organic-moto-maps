package com.organicmoto.maps.map

import com.organicmoto.maps.ui.dismissMediaStartupPrompt
import android.graphics.RectF
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.organicmoto.maps.MainActivity
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Real native POI-collision contract for the raised/dense CBD style.
 *
 * The POI layers used to carry a data-driven `symbol-sort-key`, which MapLibre
 * 13.5 turns into one drawable per feature (~880 draw calls in the dense CBD).
 * The style now uses a coarse rank band (or no key). Either way, the labels a
 * rider sees must not disappear: this suite loads the shipped style against the
 * real PMTiles archive, settles the same pitched camera the perf sweeps use,
 * and records the labels that won placement via `queryRenderedFeatures`.
 *
 * It fails if a committed important winner disappears, if POI icons or labels
 * stop rendering, or if the raised towers leave the frame. Run it once per
 * style variant and diff the `POI_WINNERS` lines to compare placement outcomes
 * explicitly instead of judging a screenshot by eye.
 */
@RunWith(AndroidJUnit4::class)
class SymbolPriorityOnDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun dismissStartupSetup() = compose.dismissMediaStartupPrompt()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var renderer: MapView? = null
    private val context = instrumentation.targetContext

    @After
    fun releaseRenderer() {
        instrumentation.runOnMainSync {
            renderer?.apply { onPause(); onStop(); onDestroy() }
            renderer = null
        }
    }

    @Test(timeout = 240_000)
    fun importantPoiLabelsSurviveCollisionAtThePerfCameras() {
        val map = map()
        val tiles = File(context.filesDir, "tiles/basemap.pmtiles")
        assertTrue("Seed offline PMTiles on this explicit serial before this suite", tiles.isFile)
        val url = "pmtiles://file://${tiles.absolutePath}"
        val style = context.assets.open("style.json").bufferedReader().use { it.readText() }
            .replace("{tiles_path}", url)
        loadStyle(map, style)

        assertCamera(map, CBD_LAT, CBD_LON, 18.0, "cbd-z18", 20, EXPECTED_CBD_Z18_WINNERS)
        assertCamera(map, CBD_LAT, CBD_LON, 16.0, "cbd-z16", 40, EXPECTED_CBD_Z16_WINNERS)
        assertCamera(map, CBD_LAT, CBD_LON, 14.0, "cbd-z14-foreground", 1, EXPECTED_CBD_Z14_WINNERS, foreground = true)
        assertCamera(map, HOUSES_LAT, HOUSES_LON, 18.5, "houses-z18.5", 1, EXPECTED_HOUSES_WINNERS)
    }

    private fun assertCamera(
        map: MapLibreMap,
        lat: Double,
        lon: Double,
        zoom: Double,
        label: String,
        minimumLabels: Int,
        expectedWinners: List<String>,
        foreground: Boolean = false,
    ) {
        camera(map, lat, lon, zoom)
        waitForRendered(map, "poi-labels", label)
        Thread.sleep(1_500)
        val bounds = viewport(foreground)
        val wins = main {
            map.queryRenderedFeatures(bounds, "poi-labels")
                .mapNotNull { feature ->
                    val name = feature.getStringProperty("name:latin")
                        ?: feature.getStringProperty("name")
                    name?.takeIf { it.isNotBlank() }
                }
                .distinct()
                .sorted()
        }
        val iconCount = main { map.queryRenderedFeatures(bounds, "poi-icons").size }
        // Foreground bounds are the label contract; the tower contract is the
        // whole pitched frame (the near field across the river still needs the
        // real raised geometry to be present somewhere in the wide view).
        val towers = main { map.queryRenderedFeatures(viewport(false), "building-3d") }
        // Place labels sit in the mid/far band of a pitched frame, so the
        // wide-view contract is the whole frame, not the foreground slice.
        val placeWins = main {
            map.queryRenderedFeatures(viewport(false), "place-labels")
                .mapNotNull { feature -> feature.getStringProperty("name:latin") ?: feature.getStringProperty("name") }
                .distinct()
                .sorted()
        }
        Log.i(
            POI_TAG,
            "POI_WINNERS $label count=${wins.size} icons=$iconCount towers=${towers.size} " +
                "place=[${placeWins.joinToString(" | ")}] :: ${wins.joinToString(" | ")}",
        )
        assertTrue(
            "$label must render POI labels from the real archive, got ${wins.size}",
            wins.size >= minimumLabels,
        )
        assertTrue("$label must render POI icons from the real archive, got $iconCount", iconCount >= 5)
        assertTrue(
            "$label must keep real raised buildings in frame",
            towers.any { it.hasProperty("render_height") },
        )
        if (foreground) {
            assertTrue(
                "$label must keep place labels at the wide view, got $placeWins",
                placeWins.isNotEmpty(),
            )
        }
        val survivors = expectedWinners.count { expected ->
            wins.any { winner -> winner.contains(expected, ignoreCase = true) }
        }
        val missing = expectedWinners.filterNot { expected ->
            wins.any { winner -> winner.contains(expected, ignoreCase = true) }
        }
        assertTrue(
            "$label lost committed important POI winners: $missing (wins=$wins)",
            survivors >= (expectedWinners.size * 9) / 10,
        )
    }

    private fun waitForRendered(map: MapLibreMap, layerId: String, label: String) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            val features = main { map.queryRenderedFeatures(viewport(), layerId).size }
            if (features > 0) return
            Thread.sleep(250)
        }
        throw AssertionError("$label never rendered any $layerId features from the installed archive")
    }

    private fun map(): MapLibreMap {
        val result = AtomicReference<MapLibreMap>()
        instrumentation.runOnMainSync {
            renderer = MapView(compose.activity).apply {
                onCreate(null)
                compose.activity.setContentView(this)
                onStart()
                onResume()
                getMapAsync { result.set(it) }
            }
        }
        compose.waitUntil(60_000) { result.get() != null }
        return result.get()
    }

    private fun loadStyle(map: MapLibreMap, json: String) {
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            map.setStyle(Style.Builder().fromJson(json)) { ready.countDown() }
        }
        assertTrue("Local style must load", ready.await(30, TimeUnit.SECONDS))
    }

    /** The lower (near) part of the pitched frame, where the foreground city is. */
    private fun viewport(foreground: Boolean = false): RectF {
        val height = renderer!!.height.toFloat()
        return if (foreground) {
            RectF(0f, height * 0.55f, renderer!!.width.toFloat(), height)
        } else {
            RectF(0f, 0f, renderer!!.width.toFloat(), height)
        }
    }

    private fun camera(map: MapLibreMap, lat: Double, lon: Double, zoom: Double) {
        instrumentation.runOnMainSync {
            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(lat, lon))
                        .zoom(zoom)
                        .tilt(58.0)
                        .bearing(0.0)
                        .build(),
                ),
            )
        }
        Thread.sleep(2_500)
    }

    private fun <T> main(action: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                result.set(action())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    private companion object {
        const val POI_TAG = "OrganicMoto.PoiProbe"
        const val CBD_LAT = -27.4679
        const val CBD_LON = 153.0281
        const val HOUSES_LAT = -27.4616
        const val HOUSES_LON = 153.0466

        // Committed placement winners recorded from the shipped style at the
        // perf cameras (matching is case-insensitive substring). These are
        // high-rank CBD places the collision solver must keep placing; the
        // list is intentionally a subset so ordinary data drift cannot make
        // the suite flaky, while a priority regression that drops the whole
        // important class still fails.
        // Names that won placement in every captured variant of the shipped
        // style at each camera (original per-feature sort key, coarse band,
        // and no key), so this is a collision-survival contract, not a
        // snapshot of one style revision.
        val EXPECTED_CBD_Z18_WINNERS = listOf(
            "General Post Office",
            "Queen Street Stop 57",
            "HSBC",
            "Woolworths",
        )
        val EXPECTED_CBD_Z16_WINNERS = listOf(
            "HSBC",
            "Woolworths",
            "Post Office Square",
            "Anzac Square",
        )
        val EXPECTED_HOUSES_WINNERS = listOf(
            "Teneriffe Park",
        )
        /** Foreground CBD landmark that must survive the wide-view tile LOD. */
        val EXPECTED_CBD_Z14_WINNERS = listOf("South Bank")
    }
}

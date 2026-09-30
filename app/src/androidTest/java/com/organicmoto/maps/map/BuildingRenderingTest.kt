package com.organicmoto.maps.map

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.organicmoto.maps.MainActivity
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Requires the separately installed Queensland PMTiles (visual.py launch seeds it).
 * A rendered-feature query alone is not proof: compare native map snapshot pixels
 * with only the extrusion layer hidden at the SAME pitched, overzoomed camera.
 */
@RunWith(AndroidJUnit4::class)
class BuildingRenderingTest {
    @get:Rule val permissions = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private var renderer: MapView? = null

    @After fun releaseRenderer() {
        main { renderer?.apply { onPause(); onStop(); onDestroy() }; renderer = null }
    }

    @Test(timeout = 240_000)
    fun offlineTowersAndDefaultHeightHousesChangeRenderedPixelsAfterStyleReload() {
        val map = map()
        val context = instrumentation.targetContext
        val tiles = File(context.filesDir, "tiles/basemap.pmtiles")
        assertTrue("Seed offline PMTiles on this explicit serial before this suite", tiles.isFile)
        val url = "pmtiles://file://${tiles.absolutePath}"
        val normal = context.assets.open("style.json").bufferedReader().use { it.readText() }.replace("{tiles_path}", url)
        val dark = context.assets.open("ride-dark-style.json").bufferedReader().use { it.readText() }.replace("{tiles_path}", url)
        loadStyle(map, normal)
        assertBuildingPixels(map, -27.4679, 153.0281, 18.0, houses = false)
        assertBuildingPixels(map, -27.4616, 153.0466, 18.5, houses = true)
        // Same private URL replacement/style reloading must recreate the layer.
        loadStyle(map, dark)
        main { assertNull(map.style?.getLayer("building-3d")) }
        loadStyle(map, normal)
        assertBuildingPixels(map, -27.4616, 153.0466, 18.5, houses = true)
        // At z13.9 there must be footprints, not merged block extrusions.
        camera(map, -27.4679, 153.0281, 13.9)
        main {
            assertTrue(map.queryRenderedFeatures(viewport(), "building-3d").isEmpty())
            assertTrue(map.queryRenderedFeatures(viewport(), "building").isNotEmpty())
        }
    }

    @Test(timeout = 240_000)
    fun cityRouteAndRiderRenderAcrossTallSilhouettesAndZoomBands() {
        val map = map()
        val context = instrumentation.targetContext
        val url = "pmtiles://file://${File(context.filesDir, "tiles/basemap.pmtiles").absolutePath}"
        val normal = context.assets.open("style.json").bufferedReader().use { it.readText() }.replace("{tiles_path}", url)
        loadStyle(map, normal)
        val route = ResponsePath().setPoints(PointList().apply {
            add(-27.4701, 153.0252); add(-27.4682, 153.0271); add(-27.4665, 153.0290)
        })
        for (zoom in listOf(18.0, 16.0, 14.0)) {
            camera(map, -27.4682, 153.0271, zoom, 225.0)
            main {
                map.drawRoutes(listOf(route), 0, RouteGeometryCache())
                map.updateGuidancePosition(-27.4682, 153.0271, 45.0, context.resources.displayMetrics.density)
            }
            Thread.sleep(1500)
            val image = snapshot(map)
            var routeInk = 0
            var riderInk = 0
            for (y in 0 until image.height step 2) for (x in 0 until image.width step 2) {
                val c = image.getPixel(x, y)
                if (Color.red(c) > 210 && Color.green(c) in 40..140 && Color.blue(c) < 60) routeInk++
                if (Color.blue(c) > 180 && Color.red(c) < 70 && Color.green(c) in 65..200) riderInk++
            }
            save(image, "city-route-rider-z$zoom")
            assertTrue("Selected orange route must have rendered ink at z$zoom: $routeInk", routeInk > 30)
            assertTrue("Rider chevron must have rendered ink at z$zoom: $riderInk", riderInk > 30)
        }
    }

    private fun assertBuildingPixels(map: MapLibreMap, lat: Double, lon: Double, zoom: Double, houses: Boolean) {
        camera(map, lat, lon, zoom)
        main {
            val buildings = map.queryRenderedFeatures(viewport(), "building-3d")
            assertTrue("Must render real offline building geometries", buildings.isNotEmpty())
            assertTrue("${if (houses) "Default-height houses" else "Tall towers"} must exist in installed tiles",
                buildings.any { it.hasProperty("render_height") &&
                    (if (houses) it.getNumberProperty("render_height").toDouble() == 5.0
                    else it.getNumberProperty("render_height").toDouble() >= 30.0) })
        }
        val withBuildings = snapshot(map)
        main { map.style!!.getLayer("building-3d")!!.setProperties(PropertyFactory.visibility(Property.NONE)) }
        Thread.sleep(800)
        val withoutBuildings = snapshot(map)
        var changed = 0
        for (y in 0 until withBuildings.height step 3) for (x in 0 until withBuildings.width step 3) {
            val a = withBuildings.getPixel(x, y)
            val b = withoutBuildings.getPixel(x, y)
            if (kotlin.math.abs(Color.red(a) - Color.red(b)) + kotlin.math.abs(Color.green(a) - Color.green(b)) +
                kotlin.math.abs(Color.blue(a) - Color.blue(b)) > 18) changed++
        }
        assertTrue("Extrusion must visibly change pixels, not merely query as data: $changed", changed > 100)
        save(withBuildings, if (houses) "residential-extrusions" else "city-extrusions")
        main { map.style!!.getLayer("building-3d")!!.setProperties(PropertyFactory.visibility(Property.VISIBLE)) }
    }

    private fun map(): MapLibreMap {
        val result = AtomicReference<MapLibreMap>()
        // Own this MapView: the real screen's live GPS effects must not replace
        // deliberately positioned overlay fixtures while pixel assertions run.
        main {
            renderer = MapView(compose.activity).apply {
                onCreate(null)
                compose.activity.setContentView(this)
                onStart(); onResume()
                getMapAsync { result.set(it) }
            }
        }
        compose.waitUntil(30_000) {
            result.get() != null
        }
        return result.get()
    }
    private fun loadStyle(map: MapLibreMap, json: String) {
        val ready = CountDownLatch(1)
        main { map.setStyle(Style.Builder().fromJson(json)) { ready.countDown() } }
        assertTrue("Local style must load", ready.await(30, TimeUnit.SECONDS))
    }
    private fun viewport() = RectF(0f, 0f, renderer!!.width.toFloat(), renderer!!.height.toFloat())
    private fun camera(map: MapLibreMap, lat: Double, lon: Double, zoom: Double, bearing: Double = 0.0) {
        main { map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder()
            .target(LatLng(lat, lon)).zoom(zoom).tilt(58.0).bearing(bearing).build())) }
        Thread.sleep(2500)
    }
    private fun snapshot(map: MapLibreMap): Bitmap {
        val ready = CountDownLatch(1)
        val result = AtomicReference<Bitmap>()
        main { map.snapshot { result.set(it); ready.countDown() } }
        assertTrue("Native renderer must return a bitmap", ready.await(20, TimeUnit.SECONDS))
        return result.get()
    }
    private fun save(bitmap: Bitmap, name: String) {
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "building-rendering").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

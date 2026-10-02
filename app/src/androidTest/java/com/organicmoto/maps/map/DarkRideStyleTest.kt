package com.organicmoto.maps.map

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import com.graphhopper.util.details.PathDetail
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/**
 * Native MapLibre regression coverage for the black-and-white ride map.
 *
 * The JVM [com.organicmoto.maps.assets.StyleAndSpritesIntegrityTest] pins the
 * shipped style JSON, but the rider's bug lived below that: the renderer drew
 * `transportation` polygons as hairline borders (the "empty street with a thin
 * outline" frame), and the road widths stopped at the archive's z14 tile
 * limit. This suite loads the real shipped style into a real MapView with a
 * synthetic GeoJSON source in place of the vector source, then proves natively
 * that polygon features are rejected, that line widths honor the style's dp
 * stops at the z16/z18 guidance bands, and that the round cap/join layout
 * properties survive into the parsed style.
 *
 * The map runs in texture mode so the rendered frame can be read back as a
 * bitmap (`TextureView.getBitmap()`); `MapLibreMap.snapshot` is not usable
 * here because it merges a view-drawing-cache bitmap that stays null on a
 * hardware-accelerated window, and TextureViewMapRenderer rejects
 * `setRenderingRefreshMode`. It therefore renders on demand: the waits nudge
 * frames with `triggerRepaint()`, wait for a finished frame before the first
 * `queryRenderedFeatures` (an early query dereferences the renderer's
 * not-yet-created native actor), and only accept a texture readback whose road
 * band repeats on the next capture so a stale previous-camera frame cannot be
 * measured. Nothing needs an installed PMTiles archive: the style is loaded
 * straight from its shipped asset, with the vector source swapped for the
 * test's own GeoJSON (the only test-local edits are dropping `source-layer`,
 * which is a vector-source-only selector, and hiding the label layer so
 * street-name pixels cannot pollute the road probes). Production code and its
 * seams are untouched.
 */
@RunWith(AndroidJUnit4::class)
class DarkRideStyleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val mapRef = AtomicReference<MapLibreMap?>()
    private val styleRef = AtomicReference<Style?>()
    private val mapViewRef = AtomicReference<MapView?>()
    private val renderedFrames = AtomicInteger(0)
    private val styleLoaded = CountDownLatch(1)

    @After
    fun tearDownMapView() {
        val mapView = mapViewRef.getAndSet(null) ?: return
        onMain {
            // The view may only have reached part of its lifecycle when the
            // test failed; clean what exists without masking the failure.
            runCatching {
                mapView.onPause()
                mapView.onStop()
            }
            runCatching { mapView.onDestroy() }
        }
    }

    @Test(timeout = 180_000)
    fun darkRideStyleDrawsSolidNativeWidthsAndIgnoresTransportationPolygons() {
        val map = startRideStyleMap()
        val mapView = mapViewRef.get()!!
        val anchor = waitForRenderedRoad(map, RIDE_ZOOM)
        val (bitmap, band) = awaitRoadBitmap(mapView, map, anchor, RIDE_ZOOM)
        try {
            val ratio = mapView.pixelRatio
            val expected = shippedLineWidthDp(SERVICE_LAYER_ID, RIDE_ZOOM) * ratio
            assertTrue(
                "service roads must render their native dp width at z16 " +
                    "(expected ~${expected}px, measured ${band.widthPx}px)",
                band.widthPx >= expected * 0.55 && band.widthPx <= expected * 1.6,
            )
            assertEquals(
                "the road must be one solid band, not a hollow casing: ${band.describe()}",
                1,
                band.runs,
            )
            assertTrue(
                "the road band must be painted at the style's grey, not a ghost: ${band.describe()}",
                band.brightestLuminance >= 60,
            )
            assertTrue(
                "the far corner must stay the ride map's black background",
                luminanceOf(bitmap.getPixel(4, 4)) < 12,
            )

            // A polygon fed to a line layer draws only its border: the
            // black-map wireframe artefact. The synthetic patches carry the
            // classes the road layers know (the archive ships `path`/`pier`/
            // `bridge` polygons), so any road-coloured pixel inside them is a
            // native filter leak.
            SYNTHETIC_PATCHES.forEach { patch ->
                val box = screenBox(map, patch.corners)
                assertTrue(
                    "${patch.name} must be on screen for the probe",
                    box.width() > 0f && box.height() > 0f,
                )
                val bright = brightPixelCount(bitmap, box)
                assertTrue(
                    "transportation polygons must not be outlined as roads, but " +
                        "${patch.name} painted $bright road-coloured pixels",
                    bright <= MAX_TOLERATED_STRAY_PIXELS,
                )
                val leaked = onMain {
                    map.queryRenderedFeatures(box, patch.layerId).mapNotNull { feature ->
                        feature.getStringProperty("name")
                    }
                }
                assertTrue(
                    "${patch.name} leaked into ${patch.layerId}: $leaked",
                    leaked.isEmpty(),
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test(timeout = 180_000)
    fun darkRideStyleKeepsRoundNativeLayoutAndThickerStreetZoomWidths() {
        val map = startRideStyleMap()
        val mapView = mapViewRef.get()!!
        val ratio = mapView.pixelRatio

        // Layer construction and property getters check the map thread, so the
        // native style is inspected on the main thread.
        onMain {
            val style = styleRef.get()!!
            val expectedMinZooms = mapOf(
                SERVICE_LAYER_ID to 13f,
                "ride-local-roads" to 10f,
                "ride-major-roads" to 6f,
            )
            expectedMinZooms.forEach { (layerId, minZoom) ->
                val layer = style.getLayerAs<LineLayer>(layerId)
                    ?: error("$layerId must parse as a native line layer")
                assertEquals("$layerId native minzoom", minZoom, layer.minZoom)
                assertEquals("$layerId native line-cap", "round", layer.lineCap.value)
                assertEquals("$layerId native line-join", "round", layer.lineJoin.value)
                val gap = layer.lineGapWidth.value ?: 0f
                assertEquals("$layerId must not draw a hollow casing", 0f, gap)
            }
            val labels = style.getLayerAs<SymbolLayer>("ride-road-labels")
                ?: error("ride-road-labels must parse as a native symbol layer")
            assertEquals("line", labels.symbolPlacement.value)
            assertEquals("map", labels.textRotationAlignment.value)
            assertEquals(
                "street names must stay upright in the pitched guidance viewport",
                "viewport",
                labels.textPitchAlignment.value,
            )
        }

        // Native width evaluation: z18 (slow, street-close) must render a
        // visibly thicker band than z16, matching the style's growth past the
        // archive's z14 tile limit.
        val anchor16 = waitForRenderedRoad(map, RIDE_ZOOM)
        val z16 = awaitRoadBand(mapView, map, anchor16, RIDE_ZOOM)
        val anchor18 = waitForRenderedRoad(map, STREET_ZOOM)
        val z18 = awaitRoadBand(mapView, map, anchor18, STREET_ZOOM)
        val expected16 = shippedLineWidthDp(SERVICE_LAYER_ID, RIDE_ZOOM) * ratio
        val expected18 = shippedLineWidthDp(SERVICE_LAYER_ID, STREET_ZOOM) * ratio
        assertTrue(
            "service road width must track the style's z16 dp stop " +
                "(expected ~${expected16}px, measured ${z16.widthPx}px)",
            z16.widthPx >= expected16 * 0.55 && z16.widthPx <= expected16 * 1.6,
        )
        assertTrue(
            "service road width must track the style's z18 dp stop " +
                "(expected ~${expected18}px, measured ${z18.widthPx}px)",
            z18.widthPx >= expected18 * 0.55 && z18.widthPx <= expected18 * 1.6,
        )
        assertTrue(
            "z18 streets must be visibly thicker than z16 " +
                "(${z16.widthPx}px -> ${z18.widthPx}px)",
            z18.widthPx >= z16.widthPx * 1.4,
        )
    }

    // --- map host -----------------------------------------------------------------

    @Test(timeout = 180_000)
    fun routedRoadIsWhiteAcrossItsFullWidthAtEveryGuidanceZoom() {
        val map = startRideStyleMap()
        val mapView = mapViewRef.get()!!
        val cache = RouteGeometryCache()
        for ((roadClass, width) in listOf("service" to RideRoadWidth.SERVICE, "tertiary" to RideRoadWidth.LOCAL, "primary" to RideRoadWidth.MAJOR)) {
            val path = ResponsePath().setPoints(PointList().apply {
                add(LINE_CENTRE.latitude - 0.006, LINE_CENTRE.longitude)
                add(LINE_CENTRE.latitude + 0.006, LINE_CENTRE.longitude)
            }).apply {
                addPathDetails(mapOf("road_class" to listOf(PathDetail(roadClass).apply {
                    setFirst(0)
                    setLast(1)
                })))
            }
            onMain {
                styleRef.get()!!.getSourceAs<GeoJsonSource>("omt")!!.setGeoJson(syntheticRoads(roadClass))
                map.drawRoutes(listOf(path), 0, cache, darkGuidanceMode = true)
                val style = styleRef.get()!!
                assertTrue("no dark outline may cut into the ridden road", style.getLayer("route-focus-casing") == null)
                assertTrue(style.layers.indexOfFirst { it.id == "route-focus-line" } <
                    style.layers.indexOfFirst { it.id == "ride-road-labels" })
            }
            for (zoom in listOf(14.0, 15.0, 16.0, 17.0, 18.0)) {
                val anchor = waitForRenderedRoad(map, zoom, width.layerId)
                val expected = (width.widthAt(zoom) + 2.0) * mapView.pixelRatio
                val deadline = SystemClock.uptimeMillis() + 15_000
                var filled = false
                while (SystemClock.uptimeMillis() < deadline && !filled) {
                    onMain { map.triggerRepaint() }
                    val bitmap = captureMap(mapView)
                    if (bitmap != null) {
                        try {
                            val row = anchor.y.roundToInt()
                            val centre = anchor.x.roundToInt()
                            // Ignore the outer antialiased pixel, but check both shoulders,
                            // not just the centre (the old 8 dp stripe passed a centre probe).
                            val half = (expected / 2.0 - 2.0 * mapView.pixelRatio).roundToInt().coerceAtLeast(1)
                            filled = (centre - half..centre + half).all { x ->
                                val pixel = bitmap.getPixel(x, row)
                                Color.red(pixel) >= 245 && Color.green(pixel) >= 245 && Color.blue(pixel) >= 245
                            }
                            if (filled) {
                                assertTrue("white road must not balloon beyond its class width",
                                    luminanceOf(bitmap.getPixel(centre + (expected / 2 + 3 * mapView.pixelRatio).roundToInt(), row)) < 200)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    if (!filled) SystemClock.sleep(200)
                }
                assertTrue("$roadClass at z$zoom must be white across ~${expected}px, not a thin centre stripe", filled)
            }
        }
    }

    /** Boots a texture-mode MapView on the synthetic ride style; camera at z16 on the test road. */
    private fun startRideStyleMap(): MapLibreMap {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            MapLibre.getInstance(composeRule.activity.applicationContext)
        }
        composeRule.setContent {
            val context = LocalContext.current
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    val options = MapLibreMapOptions.createFromAttributes(context)
                        .textureMode(true)
                        .pixelRatio(viewContext.resources.displayMetrics.density)
                    MapView(viewContext, options).also { mapView ->
                        // Track the view before any lifecycle call so a failed
                        // initialization is still torn down by @After.
                        mapViewRef.set(mapView)
                        mapView.onCreate(null)
                        mapView.addOnDidFinishRenderingFrameListener { _, _, _ ->
                            renderedFrames.incrementAndGet()
                        }
                        mapView.onStart()
                        mapView.onResume()
                        mapView.getMapAsync { map ->
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LINE_CENTRE, RIDE_ZOOM))
                            map.setStyle(Style.Builder().fromJson(rideTestStyleJson())) { style ->
                                styleRef.set(style)
                                styleLoaded.countDown()
                            }
                            mapRef.set(map)
                        }
                    }
                },
            )
        }
        // No rule-level idle sync here: this suite owns its waits through the
        // map's own frame callbacks (forcing Compose idle while the GL renderer
        // draws is exactly what the repo's fuzz guidance warns about).
        assertTrue(
            "the shipped ride style did not finish loading into the test MapView",
            styleLoaded.await(60, TimeUnit.SECONDS),
        )
        val map = mapRef.get()
        assertNotNull("the MapView never reported a MapLibreMap", map)
        waitForFirstFrame(map!!)
        return map
    }

    /**
     * MapLibre's texture renderer creates its native actor only once the
     * texture surface is live; `queryRenderedFeatures` before the first
     * finished frame dereferences a null actor and SIGSEGVs the test process.
     * The style finishing its load is not that signal, so wait for a real
     * render pass, nudging the on-demand renderer with `triggerRepaint()`.
     */
    private fun waitForFirstFrame(map: MapLibreMap) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (renderedFrames.get() > 0) return
            onMain { map.triggerRepaint() }
            SystemClock.sleep(100)
        }
        throw AssertionError("the MapView never finished a render frame")
    }

    /**
     * The shipped black-and-white style with the offline vector source swapped
     * for the test's synthetic GeoJSON. `source-layer` is a vector-source-only
     * selector, so it is dropped; the label layer is hidden so street-name
     * pixels cannot pollute the road probes (its layout is asserted natively
     * elsewhere in this suite).
     */
    private fun rideTestStyleJson(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val style = JSONObject(
            context.assets.open("ride-dark-style.json").bufferedReader().use { it.readText() },
        )
        val layers = style.getJSONArray("layers")
        for (index in 0 until layers.length()) {
            val layer = layers.getJSONObject(index)
            layer.remove("source-layer")
            if (layer.getString("id") == "ride-road-labels") {
                layer.getJSONObject("layout").put("visibility", "none")
            }
        }
        style.getJSONObject("sources").put(
            "omt",
            JSONObject()
                .put("type", "geojson")
                .put("data", JSONObject(syntheticRoads().toJson())),
        )
        return style.toString()
    }

    // --- synthetic geometry -------------------------------------------------------

    private class Patch(val name: String, val edgeClass: String, val layerId: String, val corners: List<LatLng>)

    private fun syntheticRoads(roadClass: String = "service"): FeatureCollection {
        val line = Feature.fromGeometry(
            LineString.fromLngLats(
                listOf(
                    Point.fromLngLat(LINE_CENTRE.longitude, LINE_CENTRE.latitude - 0.006),
                    Point.fromLngLat(LINE_CENTRE.longitude, LINE_CENTRE.latitude + 0.006),
                ),
            ),
        ).apply {
            addStringProperty("class", roadClass)
            addStringProperty("name", SERVICE_LINE_NAME)
        }
        val patches = SYNTHETIC_PATCHES.map { patch ->
            val west = patch.corners.minOf { it.longitude }
            val east = patch.corners.maxOf { it.longitude }
            val south = patch.corners.minOf { it.latitude }
            val north = patch.corners.maxOf { it.latitude }
            Feature.fromGeometry(
                Polygon.fromLngLats(
                    listOf(
                        listOf(
                            Point.fromLngLat(west, south),
                            Point.fromLngLat(east, south),
                            Point.fromLngLat(east, north),
                            Point.fromLngLat(west, north),
                            Point.fromLngLat(west, south),
                        ),
                    ),
                ),
            ).apply {
                addStringProperty("class", patch.edgeClass)
                addStringProperty("name", patch.name)
            }
        }
        return FeatureCollection.fromFeatures(listOf(line) + patches)
    }

    // --- measurement helpers ------------------------------------------------------

    private fun waitForRenderedRoad(
        map: MapLibreMap,
        zoom: Double = RIDE_ZOOM,
        layerId: String = SERVICE_LAYER_ID,
    ): PointF {
        val moved = abs(onMain { map.cameraPosition.zoom } - zoom) > 0.01
        if (moved) {
            onMain {
                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LINE_CENTRE, zoom))
                map.triggerRepaint()
            }
        }
        // TextureViewMapRenderer renders on demand. A frame that finished
        // before a camera move can still be the one the texture readback
        // holds, so a moved camera also waits for two post-move frames; at the
        // same zoom the already-rendered frame is accepted as-is.
        val framesNeeded = if (moved) renderedFrames.get() + 2 else 0
        val deadline = SystemClock.uptimeMillis() + 30_000
        var anchor = PointF(0f, 0f)
        while (SystemClock.uptimeMillis() < deadline) {
            onMain { map.triggerRepaint() }
            anchor = onMain { map.projection.toScreenLocation(LINE_CENTRE) }
            // The map may still be between styles on the first polls; treat a
            // query failure like "not rendered yet" instead of failing the run.
            val rendered = runCatching {
                onMain {
                    map.queryRenderedFeatures(
                        RectF(anchor.x - 8f, anchor.y - 8f, anchor.x + 8f, anchor.y + 8f),
                        layerId,
                    ).any { it.getStringProperty("name") == SERVICE_LINE_NAME }
                }
            }.getOrDefault(false)
            val atZoom = abs(onMain { map.cameraPosition.zoom } - zoom) <= 0.01
            if (rendered && atZoom && renderedFrames.get() >= framesNeeded) return anchor
            SystemClock.sleep(200)
        }
        throw AssertionError(
            "the synthetic $SERVICE_LAYER_ID road never rendered into the MapView at z$zoom",
        )
    }

    /** Reads the rendered texture; null until the TextureView is available. */
    private fun captureMap(mapView: MapView): Bitmap? {
        val renderView = mapView.renderView
        assertTrue(
            "the test MapView must render through a TextureView, found $renderView",
            renderView is TextureView,
        )
        return onMain { (renderView as TextureView).bitmap }
    }

    /**
     * Waits until the rendered frame shows one lit road band crossing [anchor],
     * and the band repeats on the next capture so a texture readback that still
     * holds the previous camera's frame (z16 while now at z18) is rejected.
     */
    private fun awaitRoadBitmap(
        mapView: MapView,
        map: MapLibreMap,
        anchor: PointF,
        zoom: Double,
    ): Pair<Bitmap, RoadBand> {
        val deadline = SystemClock.uptimeMillis() + 30_000
        var last: RoadBand? = null
        while (SystemClock.uptimeMillis() < deadline) {
            onMain { map.triggerRepaint() }
            val first = captureMap(mapView)
            if (first == null) {
                SystemClock.sleep(250)
                continue
            }
            val firstBand = roadBandIn(first, mapView, anchor)
            if (firstBand.runs == 1 && firstBand.widthPx > 0) {
                SystemClock.sleep(250)
                onMain { map.triggerRepaint() }
                val second = captureMap(mapView)
                if (second != null) {
                    val secondBand = roadBandIn(second, mapView, anchor)
                    if (secondBand.runs == 1 && abs(secondBand.widthPx - firstBand.widthPx) <= 1) {
                        first.recycle()
                        return second to secondBand
                    }
                    second.recycle()
                }
            }
            last = firstBand
            first.recycle()
            SystemClock.sleep(200)
        }
        throw AssertionError(
            "the rendered road never settled into one lit band at z$zoom (last: ${last?.describe()})",
        )
    }

    private fun awaitRoadBand(
        mapView: MapView,
        map: MapLibreMap,
        anchor: PointF,
        zoom: Double,
    ): RoadBand {
        val (bitmap, band) = awaitRoadBitmap(mapView, map, anchor, zoom)
        bitmap.recycle()
        return band
    }

    /** The horizontal road band crossing [anchor]: runs of road-coloured pixels. */
    private fun roadBandIn(bitmap: Bitmap, mapView: MapView, anchor: PointF): RoadBand {
        val ratio = mapView.pixelRatio
        val row = anchor.y.roundToInt().coerceIn(0, bitmap.height - 1)
        val centreX = anchor.x.roundToInt().coerceIn(0, bitmap.width - 1)
        val halfWindow = (16 * ratio).roundToInt().coerceAtLeast(8)
        val left = (centreX - halfWindow).coerceAtLeast(0)
        val right = (centreX + halfWindow).coerceAtMost(bitmap.width - 1)
        val pixels = IntArray(right - left + 1)
        bitmap.getPixels(pixels, 0, pixels.size, left, row, pixels.size, 1)
        var runs = 0
        var width = 0
        var brightest = 0
        var previousLit = false
        pixels.forEach { pixel ->
            val luminance = luminanceOf(pixel)
            brightest = maxOf(brightest, luminance)
            val lit = luminance >= ROAD_LUMINANCE_FLOOR
            if (lit) width++
            if (lit && !previousLit) runs++
            previousLit = lit
        }
        return RoadBand(runs = runs, widthPx = width, brightestLuminance = brightest)
    }

    private class RoadBand(val runs: Int, val widthPx: Int, val brightestLuminance: Int) {
        fun describe(): String = "$runs run(s), ${widthPx}px, brightest $brightestLuminance"
    }

    private fun brightPixelCount(bitmap: Bitmap, box: RectF): Int {
        val left = box.left.roundToInt().coerceIn(0, bitmap.width - 1)
        val right = box.right.roundToInt().coerceIn(0, bitmap.width - 1)
        val top = box.top.roundToInt().coerceIn(0, bitmap.height - 1)
        val bottom = box.bottom.roundToInt().coerceIn(0, bitmap.height - 1)
        if (right <= left || bottom <= top) return Int.MAX_VALUE
        val row = IntArray(right - left + 1)
        var bright = 0
        for (y in top..bottom) {
            bitmap.getPixels(row, 0, row.size, left, y, row.size, 1)
            row.forEach { if (luminanceOf(it) >= ROAD_LUMINANCE_FLOOR) bright++ }
        }
        return bright
    }

    private fun screenBox(map: MapLibreMap, corners: List<LatLng>): RectF {
        val points = onMain { corners.map { map.projection.toScreenLocation(it) } }
        return RectF(
            points.minOf { it.x },
            points.minOf { it.y },
            points.maxOf { it.x },
            points.maxOf { it.y },
        )
    }

    private fun luminanceOf(pixel: Int): Int =
        (0.2126 * Color.red(pixel) +
            0.7152 * Color.green(pixel) +
            0.0722 * Color.blue(pixel)).roundToInt()

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try {
                result.set(block())
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    /**
     * Evaluates the shipped style's numeric zoom functions (`line-width`),
     * accepting the legacy `{"base": x, "stops": [...]}` form and the modern
     * `["interpolate", ...]` form. Mirrors the JVM suite's expected dp values
     * instead of hard-coding them, so the pixel tolerances track the style.
     */
    private fun shippedLineWidthDp(layerId: String, zoom: Double): Double {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val style = JSONObject(
            context.assets.open("ride-dark-style.json").bufferedReader().use { it.readText() },
        )
        val layers = style.getJSONArray("layers")
        val layer = (0 until layers.length())
            .map { layers.getJSONObject(it) }
            .first { it.getString("id") == layerId }
        val value = layer.getJSONObject("paint").get("line-width")
        return when (value) {
            is Number -> value.toDouble()
            is JSONObject -> interpolate(
                points = value.getJSONArray("stops").let { stops ->
                    (0 until stops.length()).map {
                        val stop = stops.getJSONArray(it)
                        stop.getDouble(0) to stop.getDouble(1)
                    }
                },
                base = value.optDouble("base", 1.0),
                zoom = zoom,
            )
            is JSONArray -> interpolate(
                points = (3 until value.length() step 2).map {
                    value.getDouble(it) to value.getDouble(it + 1)
                },
                base = (value.get(1) as? JSONArray)
                    ?.takeIf { it.getString(0) == "exponential" }
                    ?.getDouble(1) ?: 1.0,
                zoom = zoom,
            )
            else -> throw AssertionError("Unsupported line-width value $value")
        }
    }

    private fun interpolate(points: List<Pair<Double, Double>>, base: Double, zoom: Double): Double {
        require(points.size >= 2) { "A zoom function needs at least two stops: $points" }
        if (zoom <= points.first().first) return points.first().second
        if (zoom >= points.last().first) return points.last().second
        for (index in 0 until points.size - 1) {
            val (lowZoom, lowValue) = points[index]
            val (highZoom, highValue) = points[index + 1]
            if (zoom in lowZoom..highZoom) {
                val progress = if (base == 1.0) {
                    (zoom - lowZoom) / (highZoom - lowZoom)
                } else {
                    (base.pow(zoom - lowZoom) - 1.0) / (base.pow(highZoom - lowZoom) - 1.0)
                }
                return lowValue + (highValue - lowValue) * progress
            }
        }
        return points.last().second
    }

    private companion object {
        const val SERVICE_LAYER_ID = "ride-service-roads"
        const val SERVICE_LINE_NAME = "Test Service Street"
        const val RIDE_ZOOM = 16.0
        const val STREET_ZOOM = 18.0

        /** Above the water fill (#181818) and below the dimmest road (#484848). */
        const val ROAD_LUMINANCE_FLOOR = 40

        /** Antialiasing tolerance; a real polygon outline paints hundreds of pixels. */
        const val MAX_TOLERATED_STRAY_PIXELS = 4

        /** The synthetic road runs through the camera centre. */
        val LINE_CENTRE = LatLng(-27.4700, 153.0250)

        /**
         * Transportation patches carrying classes the solid road layers know,
         * placed clear of the measured corridor but inside the z16 viewport.
         * The archive really ships `path`/`pier`/`bridge` polygons in this
         * layer, so a polygon must never be stroked by a road layer; one patch
         * exercises the service class and one the local solid class.
         */
        val SYNTHETIC_PATCHES = listOf(
            Patch(
                name = "Test Service Patch East",
                edgeClass = "service",
                layerId = SERVICE_LAYER_ID,
                corners = listOf(
                    LatLng(-27.4692, 153.0272),
                    LatLng(-27.4708, 153.0286),
                ),
            ),
            Patch(
                name = "Test Local Patch West",
                edgeClass = "minor",
                layerId = "ride-local-roads",
                corners = listOf(
                    LatLng(-27.4692, 153.0214),
                    LatLng(-27.4708, 153.0228),
                ),
            ),
        )
    }
}

package com.organicmoto.maps.map

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.RenderingEngine
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.Property

/**
 * On-device contract for the debug map-performance probe and the deterministic
 * sweep runner.
 *
 * The shipped style is measured by `tools/test/visual.py perf` against the real
 * PMTiles archive; this suite proves the machinery those numbers depend on: the
 * native finish-frame listener, the Choreographer and window-metrics series,
 * the native `easeCamera` legs, the per-frame renderer counters, the declared
 * rendering backend, the building diagnostics (verified *while* the sweep runs,
 * not only after), and the guaranteed restore of camera and paint when a sweep
 * finishes, is interrupted, or is cancelled. A synthetic extrusion source
 * keeps the test independent of an installed archive.
 */
@RunWith(AndroidJUnit4::class)
class MapPerformanceOnDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val mapRef = AtomicReference<MapLibreMap?>()
    private val styleRef = AtomicReference<Style?>()
    private val mapViewRef = AtomicReference<MapView?>()
    private val renderedFrames = AtomicInteger(0)
    private val styleLoaded = CountDownLatch(1)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDownMapView() {
        MapPerfProbeGate.exit()
        val mapView = mapViewRef.getAndSet(null) ?: return
        onMain {
            runCatching {
                mapView.onPause()
                mapView.onStop()
            }
            runCatching { mapView.onDestroy() }
        }
    }

    @Test(timeout = 240_000)
    fun sweepExportsNativeFrameStatsAndRestoresCameraAndPaint() {
        val map = startExtrusionMap()
        val mapView = mapViewRef.get()!!
        waitForFirstFrame(map)

        val probe = newProbe(mapView, map)
        val runner = MapPerfSweepRunner(
            mapView = mapView,
            map = map,
            probe = probe,
            cacheDir = context.cacheDir,
            log = { },
        )
        probe.attach()
        try {
            val originalCamera = onMain { map.cameraPosition }
            val originalOpacity = onMain { extrusionLayer().fillExtrusionOpacity.value }
            assertEquals("the shipped diagnostic baseline is translucent", 0.28f, originalOpacity)

            val request = MapPerfSweepRequest(
                requestId = System.nanoTime(),
                label = "on-device-combo",
                motion = MapPerfMotion.COMBO,
                lat = CENTRE.latitude,
                lon = CENTRE.longitude,
                latEnd = CENTRE.latitude + 0.004,
                lonEnd = CENTRE.longitude + 0.004,
                zoomStart = 16.0,
                zoomEnd = 17.0,
                tilt = 58.0,
                bearingStart = 0.0,
                bearingEnd = 90.0,
                durationMs = 3_000L,
                settle = false,
                buildingMode = BuildingDiagnosticMode.CURRENT,
            )
            val report = runBlocking(Dispatchers.Main) { runner.run(request) }

            assertTrue("sweep must complete", report.getBoolean("completed"))
            assertTrue("sweep must not report cancellation", !report.getBoolean("cancelled"))
            assertTrue("sweep must not report interruption", !report.getBoolean("interrupted"))
            assertEquals("on-device-combo", report.getString("label"))
            assertEquals("the report must name the request it measured", request.requestId, report.getLong("requestId"))
            val dimensions = report.getJSONObject("dimensions")
            assertTrue("map width must be measured", dimensions.getInt("widthPx") > 0)
            assertTrue("map height must be measured", dimensions.getInt("heightPx") > 0)
            val all = report.getJSONObject("segments").getJSONObject("all")
            val frames = all.getInt("frames")
            assertTrue(
                "a 3 s animated sweep must finish real native frames, got $frames",
                frames >= 20,
            )
            assertTrue("frame intervals must be published", all.getDouble("p95Ms") > 0.0)
            assertTrue(
                "the report must carry a cold/warm split",
                report.getJSONObject("segments").has("coldFrames"),
            )
            val intervals = report.getJSONArray("frameIntervalsMs").let { array ->
                List(array.length()) { array.getDouble(it) }
            }
            assertEquals(
                "raw intervals must match the aggregate frame count",
                frames - 1,
                intervals.size,
            )
            assertTrue(
                "at least one steady 100 ms stretch must exist in $intervals",
                FrameIntervalAggregator.longestSteadyRunForIntervals(intervals, 100.0) >= 5,
            )
            assertTrue(
                "the UI vsync series must be recorded",
                report.getJSONObject("uiVsyncMs").getInt("frames") > 0,
            )
            assertTrue(
                "window frame metrics must be labelled as window-scoped durations",
                report.getJSONObject("windowFrameMetrics").getString("scope").contains("SurfaceView"),
            )
            // The per-frame counters must be derived, not averaged cumulatively.
            val counters = report.getJSONObject("nativeRenderStats")
            assertEquals("counter samples must match the recorded frames", frames, counters.getInt("frames"))
            assertTrue("per-frame deltas must be published", counters.getInt("perFrameSamples") >= frames - 1)
            assertTrue(
                "the cumulative counters must be explained",
                counters.getString("scope").contains("cumulative"),
            )

            // The runner must put the camera and the extrusion paint back.
            val restored = onMain { map.cameraPosition }
            assertEquals(originalCamera.zoom, restored.zoom, 0.01)
            assertEquals(originalCamera.bearing, restored.bearing, 0.5)
            assertEquals(originalCamera.tilt, restored.tilt, 0.5)
            assertEquals(originalCamera.target!!.latitude, restored.target!!.latitude, 1e-6)
            assertEquals(originalCamera.target!!.longitude, restored.target!!.longitude, 1e-6)
            val restoredOpacity = onMain { extrusionLayer().fillExtrusionOpacity.value }
            assertEquals("diagnostic paint must be restored", 0.28f, restoredOpacity)
            assertEquals(Property.VISIBLE, onMain { extrusionLayer().visibility.value })
            assertFalse("the sweep gate must come back down", MapPerfProbeGate.sweepActive)
        } finally {
            probe.detach()
        }
    }

    @Test(timeout = 300_000)
    fun opaqueAndHiddenDiagnosticsChangeTheLayerDuringTheSweepThenRestoreStructure() {
        val map = startExtrusionMap()
        val mapView = mapViewRef.get()!!
        waitForFirstFrame(map)
        val probe = newProbe(mapView, map)
        val style = styleRef.get()!!
        val before = onMain { snapshotStyle(style) }
        assertTrue("the test style must expose a height property", before.buildingHeightJson != null)
        assertTrue("the test style must expose a base property", before.buildingBaseJson != null)
        probe.attach()
        try {
            for (mode in BuildingDiagnosticMode.entries) {
                val during = AtomicReference<StyleSnapshot?>()
                val runner = MapPerfSweepRunner(
                    mapView = mapView,
                    map = map,
                    probe = probe,
                    cacheDir = context.cacheDir,
                    log = { },
                    onSweepStarted = { during.set(snapshotStyle(style)) },
                )
                val request = MapPerfSweepRequest(
                    requestId = System.nanoTime(),
                    label = "on-device-${mode.name.lowercase()}",
                    motion = MapPerfMotion.ORBIT,
                    lat = CENTRE.latitude,
                    lon = CENTRE.longitude,
                    latEnd = CENTRE.latitude,
                    lonEnd = CENTRE.longitude,
                    zoomStart = 17.0,
                    zoomEnd = 17.0,
                    tilt = 58.0,
                    bearingStart = 0.0,
                    bearingEnd = 180.0,
                    durationMs = 1_600L,
                    settle = true,
                    buildingMode = mode,
                )
                val report = runBlocking(Dispatchers.Main) { runner.run(request) }
                assertTrue("sweep must complete for $mode", report.getBoolean("completed"))
                assertEquals(mode.name.lowercase(), report.getString("buildingMode"))

                val active = during.get() ?: throw AssertionError("$mode never reached its active phase")
                when (mode) {
                    BuildingDiagnosticMode.CURRENT -> {
                        assertEquals(
                            "current mode must leave the shipped extrusion visible",
                            "visible",
                            active.buildingVisibility,
                        )
                        assertEquals(
                            "current mode must not add a layer",
                            before.extrusionLayerCount,
                            active.extrusionLayerCount,
                        )
                    }
                    BuildingDiagnosticMode.OPAQUE -> {
                        assertEquals(
                            "the original extrusion must be hidden while the opaque copy draws",
                            "none",
                            active.buildingVisibility,
                        )
                        assertEquals(
                            "opaque mode must add exactly one extrusion layer",
                            before.extrusionLayerCount + 1,
                            active.extrusionLayerCount,
                        )
                        val copy = active.layers.firstOrNull { it.id != BUILDING_LAYER_ID && it.isExtrusion }
                            ?: throw AssertionError("opaque mode did not add an extrusion layer")
                        assertEquals(
                            "the opaque diagnostic copy must sit directly above the original",
                            active.layerIds.indexOf(BUILDING_LAYER_ID) + 1,
                            active.layerIds.indexOf(copy.id),
                        )
                        assertEquals("the diagnostic copy must be fully opaque", 1.0, copy.opacity, 1e-6)
                        assertTrue(
                            "the diagnostic copy must carry the shipped height/base expressions",
                            copy.heightJson != null && copy.baseJson != null,
                        )
                        // The native getter renders an expression property as its
                        // raw JSON while the rebuilt copy carries an Expression, so
                        // compare the semantic content, not the exact toString form.
                        assertTrue(
                            "the diagnostic copy must keep the shipped height expression, got ${copy.heightJson}",
                            copy.heightJson?.contains("render_height") == true,
                        )
                        assertTrue(
                            "the diagnostic copy must keep the shipped base expression, got ${copy.baseJson}",
                            copy.baseJson?.contains("render_min_height") == true,
                        )
                        assertEquals(
                            "the original layer must keep its translucent paint",
                            0.28,
                            active.buildingOpacity,
                            1e-6,
                        )
                    }
                    BuildingDiagnosticMode.HIDDEN -> {
                        assertEquals(
                            "hidden mode must hide the extrusion",
                            "none",
                            active.buildingVisibility,
                        )
                        assertEquals(
                            "hidden mode must not add a layer",
                            before.extrusionLayerCount,
                            active.extrusionLayerCount,
                        )
                    }
                }

                val after = onMain { snapshotStyle(style) }
                assertEquals(
                    "layer order must survive the $mode diagnostic",
                    before.layerIds,
                    after.layerIds,
                )
                assertEquals("paint must be restored after $mode", before.buildingOpacity, after.buildingOpacity, 1e-6)
                assertEquals("visibility must be restored after $mode", "visible", after.buildingVisibility)
                assertEquals(
                    "height expression must be restored after $mode",
                    before.buildingHeightJson,
                    after.buildingHeightJson,
                )
                assertEquals(
                    "base expression must be restored after $mode",
                    before.buildingBaseJson,
                    after.buildingBaseJson,
                )
                assertEquals(
                    "filter must be restored after $mode",
                    before.buildingFilterJson,
                    after.buildingFilterJson,
                )
                assertEquals("minzoom must be restored after $mode", before.buildingMinZoom, after.buildingMinZoom, 1e-6)
                assertEquals("maxzoom must be restored after $mode", before.buildingMaxZoom, after.buildingMaxZoom, 1e-6)
                assertFalse("the gate must come back down after $mode", MapPerfProbeGate.sweepActive)
            }
        } finally {
            probe.detach()
        }
    }

    @Test(timeout = 240_000)
    fun cancelledSweepRestoresCameraAndPaintAndDropsTheGate() {
        val map = startExtrusionMap()
        val mapView = mapViewRef.get()!!
        waitForFirstFrame(map)
        val probe = newProbe(mapView, map)
        probe.attach()
        val originalCamera = onMain { map.cameraPosition }
        val style = styleRef.get()!!
        val before = onMain { snapshotStyle(style) }
        val requestId = System.nanoTime()
        val request = MapPerfSweepRequest(
            requestId = requestId,
            label = "on-device-cancel",
            motion = MapPerfMotion.COMBO,
            lat = CENTRE.latitude,
            lon = CENTRE.longitude,
            latEnd = CENTRE.latitude + 0.006,
            lonEnd = CENTRE.longitude + 0.006,
            zoomStart = 16.0,
            zoomEnd = 17.0,
            tilt = 58.0,
            bearingStart = 0.0,
            bearingEnd = 120.0,
            durationMs = 8_000L,
            settle = false,
            buildingMode = BuildingDiagnosticMode.OPAQUE,
        )
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val job = scope.launch {
            try {
                val runner = MapPerfSweepRunner(
                    mapView = mapView,
                    map = map,
                    probe = probe,
                    cacheDir = context.cacheDir,
                    log = { },
                    onSweepStarted = { started.countDown() },
                )
                runner.run(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // expected: the test cancels the sweep mid-flight
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        val resultFile = File(context.cacheDir, "map-perf-result-$requestId.json")
        try {
            assertTrue("the sweep must start", started.await(30, TimeUnit.SECONDS))
            SystemClock.sleep(400)
            assertTrue("the probe must be recording before cancellation", probe.isRecording())
            assertTrue("the gate must be up during the sweep", MapPerfProbeGate.sweepActive)
            assertEquals(
                "the opaque diagnostic must be live during the sweep",
                before.extrusionLayerCount + 1,
                onMain { snapshotStyle(style).extrusionLayerCount },
            )
            job.cancel()
            assertTrue("the sweep must unwind after cancellation", finished.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw it }

            assertTrue("a cancelled sweep must export its report", resultFile.isFile)
            val report = JSONObject(resultFile.readText())
            assertTrue("the report must record the cancellation", report.getBoolean("cancelled"))
            assertFalse("a cancelled sweep is not a completed benchmark", report.getBoolean("completed"))
            assertTrue(
                "a sweep cancelled mid-leg must be marked interrupted, not completed",
                report.getBoolean("interrupted"),
            )
            assertTrue("a cancelled sweep reports frames, not a lie", report.getInt("version") >= 2)

            val restored = onMain { map.cameraPosition }
            assertEquals(originalCamera.zoom, restored.zoom, 0.01)
            assertEquals(originalCamera.bearing, restored.bearing, 0.5)
            assertEquals(originalCamera.tilt, restored.tilt, 0.5)
            val after = onMain { snapshotStyle(style) }
            assertEquals("layer order must be restored after cancellation", before.layerIds, after.layerIds)
            assertEquals(
                "the diagnostic layer must be removed after cancellation",
                before.extrusionLayerCount,
                after.extrusionLayerCount,
            )
            assertEquals("paint must be restored after cancellation", before.buildingOpacity, after.buildingOpacity, 1e-6)
            assertEquals("visibility must be restored after cancellation", "visible", after.buildingVisibility)
            assertFalse("the gate must come back down after cancellation", MapPerfProbeGate.sweepActive)
            assertFalse("the probe must stop recording after cancellation", probe.isRecording())
        } finally {
            job.cancel()
            resultFile.delete()
            probe.detach()
        }
    }

    @Test(timeout = 240_000)
    fun cancelledMidPlanSweepStopsShortAndStillRestores() {
        val map = startExtrusionMap()
        val mapView = mapViewRef.get()!!
        waitForFirstFrame(map)
        val probe = newProbe(mapView, map)
        probe.attach()
        val originalCamera = onMain { map.cameraPosition }
        val style = styleRef.get()!!
        val before = onMain { snapshotStyle(style) }
        val requestId = System.nanoTime()
        val request = MapPerfSweepRequest(
            requestId = requestId,
            label = "on-device-midplan",
            motion = MapPerfMotion.COMBO,
            lat = CENTRE.latitude,
            lon = CENTRE.longitude,
            latEnd = CENTRE.latitude + 0.004,
            lonEnd = CENTRE.longitude + 0.004,
            zoomStart = 16.0,
            zoomEnd = 17.0,
            tilt = 58.0,
            bearingStart = 0.0,
            bearingEnd = 90.0,
            durationMs = 6_000L,
            settle = false,
            buildingMode = BuildingDiagnosticMode.OPAQUE,
        )
        val started = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val job = scope.launch {
            try {
                val runner = MapPerfSweepRunner(
                    mapView = mapView,
                    map = map,
                    probe = probe,
                    cacheDir = context.cacheDir,
                    log = { },
                    onSweepStarted = { started.countDown() },
                )
                runner.run(request)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // expected: the plan is cancelled while its second leg runs
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        val resultFile = File(context.cacheDir, "map-perf-result-$requestId.json")
        try {
            assertTrue("the sweep must start", started.await(30, TimeUnit.SECONDS))
            // Four 1.5 s legs; cancel during the second one.
            SystemClock.sleep(1_900)
            job.cancel()
            assertTrue("the sweep must unwind after cancellation", finished.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw it }

            val report = JSONObject(resultFile.readText())
            assertTrue("the report must record the cancellation", report.getBoolean("cancelled"))
            assertFalse("a cancelled sweep is not a completed benchmark", report.getBoolean("completed"))
            assertTrue(report.getBoolean("interrupted"))
            val planned = report.getInt("plannedLegs")
            val legs = report.getJSONArray("legs")
            assertTrue("the plan must have had several legs, got $planned", planned >= 3)
            assertTrue(
                "a sweep cancelled mid-plan must not claim every planned leg, got ${legs.length()} of $planned",
                legs.length() < planned,
            )
            for (index in 0 until legs.length()) {
                assertTrue(
                    "no leg may be recorded while the sweep is mid-leg (leg $index of ${legs.length()})",
                    legs.getJSONObject(index).getBoolean("finished"),
                )
            }

            val restored = onMain { map.cameraPosition }
            assertEquals(originalCamera.zoom, restored.zoom, 0.01)
            assertEquals(originalCamera.bearing, restored.bearing, 0.5)
            val after = onMain { snapshotStyle(style) }
            assertEquals("layer order must be restored after mid-plan cancellation", before.layerIds, after.layerIds)
            assertEquals("paint must be restored after mid-plan cancellation", before.buildingOpacity, after.buildingOpacity, 1e-6)
            assertEquals("visibility must be restored after mid-plan cancellation", "visible", after.buildingVisibility)
            assertFalse("the gate must come back down", MapPerfProbeGate.sweepActive)
            assertFalse("the probe must stop recording", probe.isRecording())
        } finally {
            job.cancel()
            resultFile.delete()
            probe.detach()
        }
    }

    @Test(timeout = 240_000)
    fun sweepReportDeclaresTheRealBackendAndMeasurementScope() {
        val map = startExtrusionMap()
        val mapView = mapViewRef.get()!!
        waitForFirstFrame(map)
        val probe = newProbe(mapView, map)
        probe.attach()
        try {
            val runner = MapPerfSweepRunner(
                mapView = mapView,
                map = map,
                probe = probe,
                cacheDir = context.cacheDir,
                log = { },
            )
            val report = runBlocking(Dispatchers.Main) {
                runner.run(
                    MapPerfSweepRequest(
                        requestId = System.nanoTime(),
                        label = "on-device-backend",
                        motion = MapPerfMotion.ZOOM,
                        lat = CENTRE.latitude,
                        lon = CENTRE.longitude,
                        latEnd = CENTRE.latitude,
                        lonEnd = CENTRE.longitude,
                        zoomStart = 16.0,
                        zoomEnd = 17.0,
                        tilt = 58.0,
                        bearingStart = 0.0,
                        bearingEnd = 0.0,
                        durationMs = 1_600L,
                        settle = false,
                        buildingMode = BuildingDiagnosticMode.CURRENT,
                    ),
                )
            }
            val backend = report.getJSONObject("backend")
            val engine = onMain { RenderingEngine.getCurrentType() }
            assertNotNull("MapLibre must have initialized a rendering engine", engine)
            assertEquals(
                "the report must name the backend MapLibre actually initialized",
                engine!!.name.lowercase(),
                backend.getString("renderer"),
            )
            assertEquals(
                "the report must name the loaded MapLibre artifact flavor",
                org.maplibre.android.BuildConfig.FLAVOR,
                backend.getString("maplibreFlavor"),
            )
            assertTrue(
                "the backend flavor must be a real MapLibre flavor",
                backend.getString("maplibreFlavor") in setOf("opengl", "vulkan"),
            )
            assertEquals(
                "the report must name the app build type",
                com.organicmoto.maps.BuildConfig.BUILD_TYPE,
                backend.getString("appBuildType"),
            )
            assertTrue("the report must name the device", backend.getString("deviceModel").isNotEmpty())
            assertTrue("the report must name the Android SDK", backend.getInt("androidSdk") >= 26)

            val measurement = report.getJSONObject("measurement")
            assertTrue(
                "encoding must be described as including fence waits",
                measurement.getString("encoding").contains("fence"),
            )
            assertTrue(
                "the counter semantics must be described",
                measurement.getString("counters").contains("cumulative"),
            )
            assertTrue(
                "the primary cadence source must be described",
                measurement.getString("cadence").contains("primary"),
            )
            assertTrue(
                "the per-frame listener series must be described",
                measurement.getString("nativeFps").contains("not smoothed"),
            )
            val nativeFps = report.getJSONObject("nativeFps")
            assertTrue("native fps samples must be recorded", nativeFps.getInt("samples") > 0)
            assertTrue("native fps scope must ship", nativeFps.getString("scope").contains("harmonic"))
        } finally {
            probe.detach()
        }
    }

    /** The probe captures the current thread's Choreographer, so create it on main. */
    private fun newProbe(mapView: MapView, map: MapLibreMap): MapPerformanceProbe = onMain {
        MapPerformanceProbe(
            mapView = mapView,
            map = map,
            window = composeRule.activity.window,
            log = { },
        )
    }

    private fun startExtrusionMap(): MapLibreMap {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            MapLibre.getInstance(composeRule.activity.applicationContext)
        }
        composeRule.setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { factoryContext ->
                    MapView(factoryContext).also { mapView ->
                        mapViewRef.set(mapView)
                        mapView.onCreate(null)
                        mapView.addOnDidFinishRenderingFrameListener { _, _, _ ->
                            renderedFrames.incrementAndGet()
                        }
                        mapView.onStart()
                        mapView.onResume()
                        mapView.getMapAsync { map ->
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(CENTRE, 16.0))
                            map.setStyle(Style.Builder().fromJson(testStyleJson())) { style ->
                                styleRef.set(style)
                                styleLoaded.countDown()
                            }
                            mapRef.set(map)
                        }
                    }
                },
            )
        }
        assertTrue(
            "the extrusion test style must load",
            styleLoaded.await(60, TimeUnit.SECONDS),
        )
        return mapRef.get() ?: throw AssertionError("MapLibre never produced a map")
    }

    private fun waitForFirstFrame(map: MapLibreMap) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (renderedFrames.get() > 0) return
            onMain { map.triggerRepaint() }
            SystemClock.sleep(100)
        }
        throw AssertionError("the MapView never finished a render frame")
    }

    private fun extrusionLayer(): FillExtrusionLayer =
        styleRef.get()?.getLayerAs<FillExtrusionLayer>(BUILDING_LAYER_ID)
            ?: throw AssertionError("the test style lost its building-3d layer")

    /**
     * Serializes the layer list plus the shipped extrusion layer so a diagnostic
     * can be compared against it structurally, not only by its opacity: order,
     * paint, filter, zoom bounds and the height/base expressions must all come
     * back exactly as they were.
     */
    private fun snapshotStyle(style: Style): StyleSnapshot {
        // Read the live style, not `style.json` (a serialized snapshot can lag
        // an addLayerAbove on the style thread) and not only opacity: order,
        // paint, filter, zoom bounds and the height/base properties must all
        // come back exactly as they were.
        val descriptors = style.layers.map { layer ->
            val extrusion = layer as? FillExtrusionLayer
            LayerDescriptor(
                id = layer.id,
                isExtrusion = extrusion != null,
                opacity = runCatching { extrusion?.fillExtrusionOpacity?.value?.toDouble() }
                    .getOrNull() ?: Double.NaN,
                heightJson = runCatching { extrusion?.fillExtrusionHeight?.toString() }.getOrNull(),
                baseJson = runCatching { extrusion?.fillExtrusionBase?.toString() }.getOrNull(),
            )
        }
        val building = style.getLayerAs<FillExtrusionLayer>(BUILDING_LAYER_ID)
            ?: throw AssertionError("the test style lost $BUILDING_LAYER_ID")
        return StyleSnapshot(
            layerIds = descriptors.map { it.id },
            layers = descriptors,
            extrusionLayerCount = descriptors.count { it.isExtrusion },
            buildingVisibility = building.visibility.value,
            buildingOpacity = building.fillExtrusionOpacity.value.toDouble(),
            buildingHeightJson = runCatching { building.fillExtrusionHeight.toString() }.getOrNull(),
            buildingBaseJson = runCatching { building.fillExtrusionBase.toString() }.getOrNull(),
            buildingFilterJson = runCatching { building.filter?.toString() }.getOrNull(),
            buildingMinZoom = building.minZoom.toDouble(),
            buildingMaxZoom = building.maxZoom.toDouble(),
        )
    }

    private data class LayerDescriptor(
        val id: String,
        val isExtrusion: Boolean,
        val opacity: Double,
        val heightJson: String?,
        val baseJson: String?,
    )

    private data class StyleSnapshot(
        val layerIds: List<String>,
        val layers: List<LayerDescriptor>,
        val extrusionLayerCount: Int,
        val buildingVisibility: String,
        val buildingOpacity: Double,
        val buildingHeightJson: String?,
        val buildingBaseJson: String?,
        val buildingFilterJson: String?,
        val buildingMinZoom: Double,
        val buildingMaxZoom: Double,
    )

    private fun testStyleJson(): String {
        val features = JSONArray()
        var row = 0
        while (row < 6) {
            var column = 0
            while (column < 6) {
                val lat = CENTRE.latitude + row * 0.0009
                val lon = CENTRE.longitude + column * 0.0009
                val half = 0.00025
                features.put(
                    JSONObject()
                        .put("type", "Feature")
                        .put(
                            "properties",
                            JSONObject()
                                .put("render_height", 12.0 + (row * 6 + column) * 4.0)
                                .put("render_min_height", 0.0),
                        )
                        .put(
                            "geometry",
                            JSONObject()
                                .put("type", "Polygon")
                                .put(
                                    "coordinates",
                                    JSONArray().put(
                                        JSONArray()
                                            .put(JSONArray().put(lon - half).put(lat - half))
                                            .put(JSONArray().put(lon + half).put(lat - half))
                                            .put(JSONArray().put(lon + half).put(lat + half))
                                            .put(JSONArray().put(lon - half).put(lat + half))
                                            .put(JSONArray().put(lon - half).put(lat - half)),
                                    ),
                                ),
                        ),
                )
                column += 1
            }
            row += 1
        }
        val collection = JSONObject()
            .put("type", "FeatureCollection")
            .put("features", features)
        return JSONObject()
            .put("version", 8)
            .put("name", "map-perf-on-device")
            .put(
                "sources",
                JSONObject().put(
                    "buildings",
                    JSONObject().put("type", "geojson").put("data", collection),
                ),
            )
            .put(
                "layers",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "background")
                            .put("type", "background")
                            .put("paint", JSONObject().put("background-color", "#f6f3ec")),
                    )
                    .put(
                        JSONObject()
                            .put("id", BUILDING_LAYER_ID)
                            .put("type", "fill-extrusion")
                            .put("source", "buildings")
                            .put(
                                "paint",
                                JSONObject()
                                    .put("fill-extrusion-color", "#918b82")
                                    .put("fill-extrusion-opacity", 0.28)
                                    .put("fill-extrusion-vertical-gradient", true)
                                    .put("fill-extrusion-height", JSONArray().put("get").put("render_height"))
                                    .put("fill-extrusion-base", JSONArray().put("get").put("render_min_height")),
                            ),
                    ),
            )
            .toString()
    }

    private fun <T> onMain(action: () -> T): T {
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
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
        const val BUILDING_LAYER_ID = "building-3d"
        val CENTRE = LatLng(-27.4679, 153.0281)
    }
}

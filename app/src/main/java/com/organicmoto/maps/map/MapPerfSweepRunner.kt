package com.organicmoto.maps.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Host-queued sweep requests, mirroring the debug camera/route snapshot
 * plumbing: `tools/test/visual.py` broadcasts an intent, the debug receiver
 * validates it, and this object hands it to the live map.
 */
internal object MapPerfSweepQueue {

    const val SWEEP_ACTION = "com.organicmoto.maps.DEBUG_MAP_PERF_SWEEP"

    private const val PENDING_FILE = "map-perf-request.json"
    private const val LATEST_FILE = "map-perf-latest.json"
    private const val HISTORY_FILE = "map-perf-history.jsonl"

    fun queue(cacheDir: File, request: MapPerfSweepRequest) {
        writeAtomically(File(cacheDir, PENDING_FILE), request.toJson().toString())
    }

    fun take(cacheDir: File): MapPerfSweepRequest? {
        val file = File(cacheDir, PENDING_FILE)
        if (!file.isFile) return null
        return runCatching { MapPerfSweepRequest.fromJson(JSONObject(file.readText())) }
            .getOrNull()
            .also { file.delete() }
    }

    fun writeResult(cacheDir: File, result: JSONObject, requestId: Long) {
        writeAtomically(File(cacheDir, "map-perf-result-$requestId.json"), result.toString())
        writeAtomically(File(cacheDir, LATEST_FILE), result.toString())
        runCatching {
            File(cacheDir, HISTORY_FILE).appendText(result.toString() + "\n")
        }
    }

    private fun writeAtomically(output: File, content: String) {
        val temporary = File(output.parentFile, output.name + ".tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(output)) {
            output.writeText(content)
            temporary.delete()
        }
    }
}

/**
 * Runs one deterministic camera sweep on the live map and exports the frame
 * report. Motion uses MapLibre's own `easeCamera` animator with long legs, so
 * the benchmark measures native rendering instead of host round-trips; the
 * probe records the frames, and the runner restores the camera and the
 * building paint in every exit path (including cancellation).
 */
internal class MapPerfSweepRunner(
    private val mapView: MapView,
    private val map: MapLibreMap,
    private val probe: MapPerformanceProbe,
    private val cacheDir: File,
    private val log: (String) -> Unit,
    /**
     * Instrumented-test seam, called on the main thread once the sweep is
     * actively recording (diagnostics applied, camera moving, probe attached).
     * Production callers never pass it.
     */
    private val onSweepStarted: (() -> Unit)? = null,
) {

    /** Extra time the native animator may take before a leg is abandoned. */
    private val animationGraceMs = 1_500L

    /** A warmed run waits this long without new frames before it starts. */
    private val quietWindowMs = 400L

    private val settleTimeoutMs = 6_000L

    suspend fun run(request: MapPerfSweepRequest): JSONObject {
        // The gate goes up first and comes down in `finally` for every exit
        // path, including a failure while capturing the original camera.
        MapPerfProbeGate.enter()
        var originalCamera: CameraPosition? = null
        var building: BuildingPaintHandle? = null
        var visibility: LayerVisibilityHandle? = null
        var tileLod: TileLodHandle? = null
        var capture: SweepCapture? = null
        var completed = false
        var cancelled = false
        var settleTimedOut = false
        var legRuns: List<JSONObject> = emptyList()
        var plannedLegs = 0
        var report: JSONObject? = null
        try {
            originalCamera = map.cameraPosition
            building = BuildingPaintHandle.capture(map.style)
            visibility = LayerVisibilityHandle.capture(map.style, request.hideLayers)
            tileLod = TileLodHandle.capture(map, request.tileLod)
            val legs = MapPerfSweepPlan.legs(request)
            plannedLegs = legs.size
            building.apply(request.buildingMode, log)
            visibility.hide(log)
            tileLod.apply(log)
            moveTo(startCamera(request))
            if (request.settle && !awaitQuiet()) {
                settleTimedOut = true
                log("settle window expired; '${request.label}' is not a verified warmed run")
            }
            if (!probe.begin(request.label)) {
                throw IllegalStateException("a sweep is already recording")
            }
            onSweepStarted?.invoke()
            legRuns = animateLegs(legs)
            completed = legRuns.all { it.optBoolean("finished", false) }
        } catch (cancellation: CancellationException) {
            cancelled = true
            throw cancellation
        } finally {
            capture = probe.end()
            // Restore even if the map is mid-teardown: a failed restore must
            // never mask the sweep outcome.
            runCatching { map.cancelTransitions() }
            visibility?.restore(log)
            building?.restore(log)
            tileLod?.restore(log)
            originalCamera?.let {
                runCatching { map.moveCamera(CameraUpdateFactory.newCameraPosition(it)) }
            }
            MapPerfProbeGate.exit()
            report = buildResult(request, legRuns, plannedLegs, capture, completed, cancelled, settleTimedOut)
            withContext(NonCancellable + Dispatchers.IO) {
                MapPerfSweepQueue.writeResult(cacheDir, report, request.requestId)
            }
            log(
                "sweep '${request.label}' ${if (completed) "completed" else "stopped"} " +
                    "frames=${capture?.frames?.size ?: 0}",
            )
        }
        return report ?: error("sweep report missing")
    }

    /**
     * Animates every leg and reports which ones actually finished. A leg that
     * `onCancel`s (another camera owner moved the map) or times out is recorded
     * as unfinished, and the sweep is marked interrupted instead of claiming a
     * clean benchmark it did not run.
     */
    private suspend fun animateLegs(legs: List<MapPerfLeg>): List<JSONObject> {
        val runs = ArrayList<JSONObject>(legs.size)
        for (leg in legs) {
            val finished = withTimeoutOrNull(leg.durationMs + animationGraceMs) {
                animateLeg(leg)
            } ?: false
            if (!finished) log("leg '${leg.note}' did not finish; sweep marked interrupted")
            runs.add(JSONObject().put("note", leg.note).put("durationMs", leg.durationMs).put("finished", finished))
        }
        return runs
    }

    /** Returns true only when MapLibre reported the animation finished. */
    private suspend fun animateLeg(leg: MapPerfLeg): Boolean {
        val update = CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(LatLng(leg.target.lat, leg.target.lon))
                .zoom(leg.zoom)
                .tilt(leg.tilt)
                .bearing(leg.bearing)
                .padding(0.0, 0.0, 0.0, 0.0)
                .build(),
        )
        return suspendCancellableCoroutine { continuation ->
            map.easeCamera(
                update,
                leg.durationMs,
                false,
                object : MapLibreMap.CancelableCallback {
                    override fun onFinish() {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancel() {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
            )
            continuation.invokeOnCancellation { runCatching { map.cancelTransitions() } }
        }
    }

    private fun startCamera(request: MapPerfSweepRequest): CameraPosition =
        CameraPosition.Builder()
            .target(LatLng(request.lat, request.lon))
            .zoom(request.zoomStart)
            .tilt(request.tilt)
            .bearing(MapPerfSweepPlan.normalizeBearing(request.bearingStart))
            .padding(0.0, 0.0, 0.0, 0.0)
            .build()

    private fun moveTo(camera: CameraPosition) {
        map.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
    }

    /**
     * Waits until the renderer has been quiet for [quietWindowMs] after a fully
     * rendered frame, or [settleTimeoutMs] elapses. A warm run starts its
     * motion from a settled frame instead of competing with tile loading.
     * Returns false when the timeout won the race, so the report can say that
     * the run was not a verified warmed state instead of claiming one.
     */
    private suspend fun awaitQuiet(): Boolean {
        val deadline = System.nanoTime() + settleTimeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            val lastFrame = probe.lastFrameNanos()
            if (lastFrame != 0L && probe.lastFrameFullyRendered() &&
                (System.nanoTime() - lastFrame) / 1_000_000L >= quietWindowMs
            ) {
                return true
            }
            delay(50)
        }
        return false
    }

    private fun buildResult(
        request: MapPerfSweepRequest,
        legRuns: List<JSONObject>,
        plannedLegs: Int,
        capture: SweepCapture?,
        completed: Boolean,
        cancelled: Boolean,
        settleTimedOut: Boolean,
    ): JSONObject {
        val frames = capture?.frames.orEmpty()
        val allStats = FrameIntervalAggregator.stats(frames)
        val (cold, warm) = FrameIntervalAggregator.coldWarmSplit(frames)
        // Raw native intervals ship with the aggregates so a reviewer (or a
        // regression test) can re-derive percentiles and steady stretches from
        // the actual series instead of trusting a summary.
        val intervalArray = JSONArray()
        FrameIntervalAggregator.intervalsMs(frames).forEach { interval ->
            intervalArray.put(Math.round(interval * 10.0) / 10.0)
        }
        val segments = JSONObject().put("all", allStats.toJson())
        segments.put("coldFrames", cold.size)
        segments.put("warmFrames", warm.size)
        segments.put("cold", if (cold.size >= 2) FrameIntervalAggregator.stats(cold).toJson() else JSONObject.NULL)
        segments.put("warm", if (warm.size >= 2) FrameIntervalAggregator.stats(warm).toJson() else JSONObject.NULL)
        val legArray = JSONArray()
        legRuns.forEach { legArray.put(it) }
        return JSONObject()
            .put("version", 2)
            .put("requestId", request.requestId)
            .put("label", request.label)
            .put("completed", completed)
            .put("cancelled", cancelled)
            .put(
                "interrupted",
                // A sweep that stopped before every planned leg ran is
                // interrupted, even when the in-flight leg was cancelled before
                // it could return: a canceled native camera leg must never
                // count as a completed benchmark.
                legRuns.size < plannedLegs || legRuns.any { !it.optBoolean("finished", false) },
            )
            .put("plannedLegs", plannedLegs)
            .apply { request.tileLod?.let { put("tileLod", it.toJson()) } }
            .put("motion", request.motion.name.lowercase(Locale.US))
            .put("settle", request.settle)
            .put("settleTimedOut", settleTimedOut)
            .put("buildingMode", request.buildingMode.name.lowercase(Locale.US))
            .put("target", JSONObject().put("lat", request.lat).put("lon", request.lon))
            .put("targetEnd", JSONObject().put("lat", request.latEnd).put("lon", request.lonEnd))
            .put("zoomStart", request.zoomStart)
            .put("zoomEnd", request.zoomEnd)
            .put("tilt", request.tilt)
            .put("bearingStart", request.bearingStart)
            .put("bearingEnd", request.bearingEnd)
            .put("requestedDurationMs", request.durationMs)
            .put(
                "actualDurationMs",
                if (capture != null && capture.endedAtNanos > capture.startedAtNanos) {
                    Math.round((capture.endedAtNanos - capture.startedAtNanos) / 1_000_000.0)
                } else {
                    0L
                },
            )
            .put(
                "dimensions",
                JSONObject()
                    .put("widthPx", mapView.width)
                    .put("heightPx", mapView.height)
                    .put("densityDpi", mapView.resources.displayMetrics.densityDpi),
            )
            .put("legs", legArray)
            .put("frameIntervalsMs", intervalArray)
            .put("segments", segments)
            .put("backend", MapRendererIdentity.describe(mapView.context))
            .put(
                "measurement",
                JSONObject()
                    .put(
                        "cadence",
                        "segments.*.fps is the elapsed frame-timestamp average " +
                            "(count-1)*1000/durationMs and is the primary cadence; " +
                            "nativeFps is the secondary per-frame listener series",
                    )
                    .put(
                        "encoding",
                        "segments.*.encodingMeanMs/P95 are MapLibre RenderingStats.encodingTime " +
                            "(seconds) converted to ms; on Vulkan this includes Context::beginFrame " +
                            "fence waits, so it is encode+GPU-wait, not pure CPU encode time",
                    )
                    .put(
                        "rendering",
                        "segments.*.renderingMeanMs/P95 are MapLibre RenderingStats.renderingTime " +
                            "(seconds) converted to ms",
                    )
                    .put("counters", RenderStatsAggregator.SCOPE)
                    .put("nativeFps", NativeFpsAggregator.SCOPE),
            )
            .put("nativeRenderStats", RenderStatsAggregator.summarize(capture?.renderStats.orEmpty()))
            .put("nativeFps", NativeFpsAggregator.summarize(capture?.fpsSamples.orEmpty()))
            .put(
                "uiVsyncMs",
                FrameIntervalAggregator.statsForNanos(capture?.uiFrameNanos.orEmpty()).toJson(),
            )
            .put(
                "windowFrameMetrics",
                JSONObject()
                    .put("source", "Window.FrameMetrics")
                    .put(
                        "scope",
                        "per-frame durations of this activity window's UI frames; a " +
                            "SurfaceView-backed MapView renders on its own GL surface, " +
                            "so these numbers are not map presentation timestamps",
                    )
                    .put("total", FrameIntervalAggregator.statsForDurations(capture?.presentationNanos.orEmpty()).toJson())
                    .put("swap", FrameIntervalAggregator.statsForDurations(capture?.swapNanos.orEmpty()).toJson())
                    .put("gpu", FrameIntervalAggregator.statsForDurations(capture?.gpuNanos.orEmpty()).toJson()),
            )
            .put("finishedAtMillis", System.currentTimeMillis())
    }

}

/**
 * The rendering backend actually initialized in this process. A sweep report
 * that does not say which backend and GPU it measured is not evidence, so the
 * values come from the loaded MapLibre artifact itself
 * (`RenderingEngine.getCurrentType()` plus the artifact's `BuildConfig`), never
 * from a build-time guess.
 */
internal object MapRendererIdentity {

    fun describe(context: android.content.Context): JSONObject =
        JSONObject()
            .put("renderer", currentRenderer())
            .put("maplibreFlavor", artifactConstant("FLAVOR"))
            .put("maplibreVersion", artifactConstant("MAPLIBRE_VERSION_STRING"))
            .put("maplibreRevision", artifactConstant("GIT_REVISION_SHORT"))
            .put("appBuildType", com.organicmoto.maps.BuildConfig.BUILD_TYPE)
            .put("deviceManufacturer", android.os.Build.MANUFACTURER)
            .put("deviceModel", android.os.Build.MODEL)
            .put("androidSdk", android.os.Build.VERSION.SDK_INT)
            .put("glEsVersion", glEsVersion(context))

    private fun currentRenderer(): String = runCatching {
        org.maplibre.android.RenderingEngine.getCurrentType()?.name?.lowercase(Locale.US)
    }.getOrNull() ?: "unknown"

    /**
     * Reads a MapLibre `BuildConfig` constant through reflection.
     *
     * A direct reference like `org.maplibre.android.BuildConfig.FLAVOR` is a
     * compile-time constant: Kotlin inlines the string into this class, and an
     * ABI-identical artifact swap (`android-sdk` <-> `android-sdk-opengl` at the
     * same version) can leave the stale value in a cached/incrementally
     * compiled build. The measured evidence proved it: a Vulkan run reported
     * `renderer=vulkan` but an inlined `flavor=opengl`. Reflection reads the
     * class that is actually packaged in the running APK.
     */
    private fun artifactConstant(name: String): String = runCatching {
        org.maplibre.android.BuildConfig::class.java.getField(name).get(null) as? String
    }.getOrNull() ?: "unknown"

    private fun glEsVersion(context: android.content.Context): String = runCatching {
        context.getSystemService(android.app.ActivityManager::class.java)
            ?.deviceConfigurationInfo
            ?.glEsVersion
            .orEmpty()
    }.getOrDefault("")
}

/**
 * Native tile-LOD override for one sweep: captures the map's current
 * `setTileLod*` values and puts them back afterwards. The MapLibre default
 * pitch threshold is 60 degrees, above this app's 58 degree camera, so the
 * threshold must be lowered for the designed LOD path to participate at all.
 */
private class TileLodHandle(
    private val map: MapLibreMap,
    private val previous: MapPerfTileLod,
    private val requested: MapPerfTileLod?,
) {

    fun apply(log: (String) -> Unit) {
        val lod = requested ?: return
        runCatching {
            map.tileLodPitchThreshold = lod.pitchThresholdRadians
            map.tileLodMinRadius = lod.minRadius
            map.tileLodScale = lod.scale
            map.tileLodZoomShift = lod.zoomShift
        }.onFailure { log("tile LOD could not be applied: ${it.message}") }
        log(
            "tile LOD applied: threshold=${lod.pitchThresholdRadians} rad " +
                "minRadius=${lod.minRadius} scale=${lod.scale} zoomShift=${lod.zoomShift}",
        )
    }

    fun restore(log: (String) -> Unit) {
        if (requested == null) return
        runCatching {
            map.tileLodPitchThreshold = previous.pitchThresholdRadians
            map.tileLodMinRadius = previous.minRadius
            map.tileLodScale = previous.scale
            map.tileLodZoomShift = previous.zoomShift
        }.onFailure { log("tile LOD could not be restored: ${it.message}") }
    }

    companion object {
        fun capture(map: MapLibreMap, requested: MapPerfTileLod?): TileLodHandle {
            val previous = runCatching {
                MapPerfTileLod(
                    pitchThresholdRadians = map.tileLodPitchThreshold,
                    minRadius = map.tileLodMinRadius,
                    scale = map.tileLodScale,
                    zoomShift = map.tileLodZoomShift,
                )
            }.getOrElse {
                MapPerfTileLod(Math.PI / 3.0, 3.0, 1.0, 0.0)
            }
            return TileLodHandle(map, previous, requested)
        }
    }
}

/**
 * Debug-only layer isolation: hides the requested layer ids for one sweep and
 * restores each layer's captured visibility afterwards. Layer ids that are not
 * in the style are reported and ignored, so a diagnostic request can never
 * disturb a layer it did not name.
 */
private class LayerVisibilityHandle(
    private val style: Style?,
    private val hidden: List<Pair<String, PropertyValue<String>>>,
) {

    fun hide(log: (String) -> Unit) {
        if (hidden.isEmpty()) return
        val activeStyle = style ?: return
        for ((id, _) in hidden) {
            runCatching {
                activeStyle.getLayer(id)?.setProperties(PropertyFactory.visibility(Property.NONE))
            }.onFailure { log("diagnostic hide of '$id' failed: ${it.message}") }
        }
        log("diagnostic: hid ${hidden.size} layer(s): ${hidden.joinToString(", ") { it.first }}")
    }

    fun restore(log: (String) -> Unit) {
        val activeStyle = style ?: return
        for ((id, visibility) in hidden) {
            runCatching { activeStyle.getLayer(id)?.setProperties(visibility) }
                .onFailure { log("diagnostic restore of '$id' failed: ${it.message}") }
        }
    }

    companion object {
        fun capture(style: Style?, layerIds: List<String>): LayerVisibilityHandle {
            if (style == null || layerIds.isEmpty()) return LayerVisibilityHandle(null, emptyList())
            val captured = layerIds.mapNotNull { id ->
                val layer = runCatching { style.getLayer(id) }.getOrNull() ?: return@mapNotNull null
                id to layer.visibility
            }
            return LayerVisibilityHandle(style, captured)
        }
    }
}

/**
 * Building-rendering diagnostics for the A/B extrusion measurement.
 *
 * The opaque case deliberately builds a *fresh* `fill-extrusion` layer instead
 * of editing the shipped layer's opacity: MapLibre decides a layer's render
 * pass from the layer as it is built, so an in-place opacity change is not
 * guaranteed to move the geometry onto the opaque path the measurement is
 * meant to compare. The fresh copy sits directly above the hidden original, so
 * draw order relative to the rest of the style is unchanged, and the original
 * layer is never modified beyond its visibility — restoration is exact.
 */
private class BuildingPaintHandle(
    private val style: Style?,
    private val original: FillExtrusionLayer?,
    private val originalVisibility: PropertyValue<String>?,
) {

    private var diagnosticLayerId: String? = null

    fun apply(mode: BuildingDiagnosticMode, log: (String) -> Unit) {
        val activeStyle = style
        val source = original
        if (activeStyle == null || source == null) {
            if (mode != BuildingDiagnosticMode.CURRENT) {
                log("building-3d layer missing; ${mode.name.lowercase(Locale.US)} diagnostics skipped")
            }
            return
        }
        when (mode) {
            BuildingDiagnosticMode.CURRENT -> Unit
            BuildingDiagnosticMode.OPAQUE -> {
                val copy = FillExtrusionLayer(DIAGNOSTIC_LAYER_ID, source.sourceId)
                source.sourceLayer?.let { copy.setSourceLayer(it) }
                source.filter?.let { copy.setFilter(it) }
                copy.setMinZoom(source.minZoom)
                copy.setMaxZoom(source.maxZoom)
                copy.setProperties(
                    source.fillExtrusionColor,
                    PropertyFactory.fillExtrusionOpacity(1f),
                    extrusionHeightProperty(source),
                    extrusionBaseProperty(source),
                    source.fillExtrusionVerticalGradient,
                    source.fillExtrusionTranslate,
                    source.fillExtrusionTranslateAnchor,
                )
                source.setProperties(PropertyFactory.visibility(Property.NONE))
                activeStyle.addLayerAbove(copy, source.id)
                diagnosticLayerId = copy.id
                log("diagnostic: fresh opaque building-3d layer added above the hidden original")
            }
            BuildingDiagnosticMode.HIDDEN -> {
                source.setProperties(PropertyFactory.visibility(Property.NONE))
                log("diagnostic: building-3d hidden")
            }
        }
    }

    fun restore(log: (String) -> Unit) {
        val activeStyle = style ?: return
        diagnosticLayerId?.let { id ->
            runCatching { activeStyle.removeLayer(id) }
                .onFailure { log("diagnostic layer '$id' could not be removed: ${it.message}") }
        }
        diagnosticLayerId = null
        val source = original ?: return
        runCatching {
            source.setProperties(originalVisibility ?: PropertyFactory.visibility(Property.VISIBLE))
        }.onFailure { log("building-3d visibility could not be restored: ${it.message}") }
    }

    companion object {
        private const val DIAGNOSTIC_LAYER_ID = "map-perf-building-3d-diagnostic"

        /**
         * Rebuilds a data-driven height/base property for the diagnostic copy.
         *
         * Passing the native getter's `PropertyValue` straight into
         * `setProperties` loses expression properties: the native getter returns
         * the raw JSON array, and re-setting it produced a `null` height (the
         * copy rendered nothing, which is why an earlier opaque A/B was
         * confounded). `Expression.raw` round-trips the shipped expression.
         */
        private fun extrusionHeightProperty(layer: FillExtrusionLayer): PropertyValue<*> =
            propertyFor(layer.fillExtrusionHeight) { PropertyFactory.fillExtrusionHeight(it) }

        private fun extrusionBaseProperty(layer: FillExtrusionLayer): PropertyValue<*> =
            propertyFor(layer.fillExtrusionBase) { PropertyFactory.fillExtrusionBase(it) }

        private fun propertyFor(
            property: PropertyValue<Float>,
            factory: (Expression) -> PropertyValue<Expression>,
        ): PropertyValue<*> {
            if (property.isExpression) {
                property.expression?.let { return factory(it) }
            }
            val raw = property.value
            return when {
                raw is Number -> factory(Expression.literal(raw))
                raw != null -> factory(Expression.raw(raw.toString()))
                else -> factory(Expression.literal(0f))
            }
        }

        fun capture(style: Style?): BuildingPaintHandle {
            val layer = runCatching { style?.getLayerAs<FillExtrusionLayer>("building-3d") }.getOrNull()
            return BuildingPaintHandle(
                style = style,
                original = layer,
                originalVisibility = layer?.visibility,
            )
        }
    }
}

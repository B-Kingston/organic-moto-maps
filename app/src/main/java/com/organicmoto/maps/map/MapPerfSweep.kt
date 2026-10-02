package com.organicmoto.maps.map

import org.json.JSONObject
import java.util.Locale

/**
 * Deterministic camera-sweep vocabulary for the debug map-performance probe.
 *
 * The benchmark must move the real app map without a host in the loop, so the
 * plan is a pure description of camera legs: [MapPerfSweepPlan] turns one
 * request into legs, and the runner animates them with MapLibre's own
 * `easeCamera` animator. Keeping this half free of Android classes lets JVM
 * tests pin the geometry and the JSON contract; only the runner touches
 * `CameraPosition`.
 */
internal enum class MapPerfMotion {
    ORBIT,
    PAN,
    ZOOM,
    COMBO,
    ;

    companion object {
        fun fromWire(value: String?): MapPerfMotion? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * Building-rendering diagnostics for the A/B extrusion measurement. These are
 * temporary paint overrides on the shipped `building-3d` layer, applied and
 * restored by the sweep runner; they are never a production rendering policy.
 */
internal enum class BuildingDiagnosticMode {
    /** Leave the shipped layer untouched. */
    CURRENT,

    /** Force `fill-extrusion-opacity: 1` to test the opaque fast path. */
    OPAQUE,

    /** Hide the extrusion layer entirely. */
    HIDDEN,
    ;

    companion object {
        fun fromWire(value: String?): BuildingDiagnosticMode? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/** Pure WGS84 point so the plan stays testable off-device. */
internal data class MapPerfPoint(val lat: Double, val lon: Double)

/**
 * Native camera-based tile LOD overrides for a sweep (MapLibre
 * `setTileLod*`). `pitchThreshold` is radians above which LOD applies; the
 * MapLibre default is 60 degrees, which never activates at this app's 58
 * degree camera, so a sweep can ask for the designed LOD path explicitly. The
 * runner captures the previous values and restores them afterwards.
 */
internal data class MapPerfTileLod(
    val pitchThresholdRadians: Double,
    val minRadius: Double,
    val scale: Double,
    val zoomShift: Double,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pitchThresholdRadians", pitchThresholdRadians)
        .put("minRadius", minRadius)
        .put("scale", scale)
        .put("zoomShift", zoomShift)

    companion object {
        fun fromJson(json: JSONObject?): MapPerfTileLod? {
            if (json == null || json.length() == 0) return null
            val threshold = json.optDouble("pitchThresholdRadians", Double.NaN)
            val minRadius = json.optDouble("minRadius", 3.0)
            val scale = json.optDouble("scale", 1.0)
            val zoomShift = json.optDouble("zoomShift", 0.0)
            if (!threshold.isFinite() || threshold !in 0.0..Math.PI) return null
            if (!minRadius.isFinite() || minRadius < 1.0 || minRadius > 20.0) return null
            if (!scale.isFinite() || scale <= 0.0 || scale > 8.0) return null
            if (!zoomShift.isFinite() || zoomShift !in -3.0..3.0) return null
            return MapPerfTileLod(threshold, minRadius, scale, zoomShift)
        }
    }
}

/** One camera leg: where to go and how long the native animator should take. */
internal data class MapPerfLeg(
    val target: MapPerfPoint,
    val zoom: Double,
    val tilt: Double,
    val bearing: Double,
    val durationMs: Int,
    val note: String,
)

/**
 * One benchmark run: a named camera motion at a fixed place and pitch. The
 * host queues these through the debug receiver; the sweep runner animates the
 * legs and exports a frame report.
 */
internal data class MapPerfSweepRequest(
    val requestId: Long,
    val label: String,
    val motion: MapPerfMotion,
    val lat: Double,
    val lon: Double,
    val latEnd: Double,
    val lonEnd: Double,
    val zoomStart: Double,
    val zoomEnd: Double,
    val tilt: Double,
    val bearingStart: Double,
    val bearingEnd: Double,
    val durationMs: Long,
    /**
     * When true the runner waits for the map to finish rendering the start
     * camera before the motion starts (a warmed run). When false the motion
     * starts immediately after the jump, so tile/geometry loading overlaps the
     * first frames and the report's cold segment is real work.
     */
    val settle: Boolean,
    val buildingMode: BuildingDiagnosticMode,
    /**
     * Debug-only layer isolation for finding which layers dominate a slow
     * frame. The runner hides exactly these layer ids for the sweep and puts
     * their captured visibility back afterwards; it is never a production
     * rendering policy.
     */
    val hideLayers: List<String> = emptyList(),
    /** Optional native tile-LOD override; null keeps the map's own values. */
    val tileLod: MapPerfTileLod? = null,
) {
    /** Round-trips through the debug receiver and into the sweep report. */
    fun toJson(): JSONObject = JSONObject()
        .put("requestId", requestId)
        .put("label", label)
        .put("motion", motion.name.lowercase(Locale.US))
        .put("lat", lat)
        .put("lon", lon)
        .put("latEnd", latEnd)
        .put("lonEnd", lonEnd)
        .put("zoomStart", zoomStart)
        .put("zoomEnd", zoomEnd)
        .put("tilt", tilt)
        .put("bearingStart", bearingStart)
        .put("bearingEnd", bearingEnd)
        .put("durationMs", durationMs)
        .put("settle", settle)
        .put("buildingMode", buildingMode.name.lowercase(Locale.US))
        .put("hideLayers", org.json.JSONArray(hideLayers))
        .apply { tileLod?.let { put("tileLod", it.toJson()) } }

    companion object {
        /** Upper bound so a bad host request cannot animate for minutes. */
        const val MAX_DURATION_MS = 60_000L

        fun fromJson(json: JSONObject): MapPerfSweepRequest? {
            val requestId = json.optLong("requestId", -1L)
            if (requestId <= 0L) return null
            val motion = MapPerfMotion.fromWire(json.optString("motion", "orbit")) ?: return null
            val buildingMode = BuildingDiagnosticMode.fromWire(json.optString("buildingMode", "current"))
                ?: return null
            val lat = json.optDouble("lat", Double.NaN)
            val lon = json.optDouble("lon", Double.NaN)
            val latEnd = json.optDouble("latEnd", lat)
            val lonEnd = json.optDouble("lonEnd", lon)
            val zoomStart = json.optDouble("zoomStart", Double.NaN)
            val zoomEnd = json.optDouble("zoomEnd", zoomStart)
            val tilt = json.optDouble("tilt", 0.0)
            val bearingStart = json.optDouble("bearingStart", 0.0)
            val bearingEnd = json.optDouble("bearingEnd", bearingStart)
            val durationMs = json.optLong("durationMs", 6_000L)
            if (!lat.isFinite() || lat !in -85.0..85.0) return null
            if (!lon.isFinite() || lon !in -180.0..180.0) return null
            if (!latEnd.isFinite() || latEnd !in -85.0..85.0) return null
            if (!lonEnd.isFinite() || lonEnd !in -180.0..180.0) return null
            if (!zoomStart.isFinite() || zoomStart !in 0.0..22.0) return null
            if (!zoomEnd.isFinite() || zoomEnd !in 0.0..22.0) return null
            if (!tilt.isFinite() || tilt !in 0.0..60.0) return null
            if (!bearingStart.isFinite() || !bearingEnd.isFinite()) return null
            if (durationMs !in 500L..MAX_DURATION_MS) return null
            val label = json.optString("label", "").trim().take(120)
            if (label.isEmpty()) return null
            val hideLayers = json.optJSONArray("hideLayers")?.let { array ->
                (0 until array.length())
                    .mapNotNull { array.optString(it, null) }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.length <= 64 }
                    .distinct()
                    .take(64)
            }.orEmpty()
            return MapPerfSweepRequest(
                requestId = requestId,
                label = label,
                motion = motion,
                lat = lat,
                lon = lon,
                latEnd = latEnd,
                lonEnd = lonEnd,
                zoomStart = zoomStart,
                zoomEnd = zoomEnd,
                tilt = tilt,
                bearingStart = bearingStart,
                bearingEnd = bearingEnd,
                durationMs = durationMs,
                settle = json.optBoolean("settle", true),
                buildingMode = buildingMode,
                hideLayers = hideLayers,
                tileLod = MapPerfTileLod.fromJson(json.optJSONObject("tileLod")),
            )
        }
    }
}

/**
 * One native frame's renderer counters (MapLibre `RenderingStats`). These say
 * whether a slow frame is drawing a lot (`numDrawCalls`/`totalDrawCalls`),
 * re-uploading geometry (`bufferUpdates`/`bufferUpdateBytes`), or rebuilding
 * textures (`numTextureUpdates`/`textureUpdateBytes`/`numCreatedTextures`).
 *
 * MapLibre reports `numDrawCalls`/`totalDrawCalls` per frame, but the buffer,
 * texture and memory counters are cumulative since the renderer started. The
 * aggregator below therefore derives per-frame deltas for those instead of
 * averaging the running totals (an average of cumulative counters would
 * describe the history of the process, not one frame of the sweep).
 */
internal data class RenderFrameStats(
    val drawCalls: Int,
    val totalDrawCalls: Int,
    val bufferUpdates: Long,
    val bufferUpdateBytes: Long,
    val textureUpdates: Int,
    val textureUpdateBytes: Long,
    val createdTextures: Int,
    val memBuffers: Long,
    val memTextures: Long,
)

/** Pure aggregation of [RenderFrameStats] shared by the runner and JVM tests. */
internal object RenderStatsAggregator {

    const val SCOPE =
        "drawCalls (numDrawCalls) is per frame; totalDrawCalls, bufferUpdates, " +
            "bufferUpdateBytes, textureUpdates, textureUpdateBytes, createdTextures, memBuffers " +
            "and memTextures are cumulative since renderer start, so *PerFrame* values are deltas " +
            "between consecutive samples and mean-of-totals is intentionally not reported"

    fun summarize(stats: List<RenderFrameStats>): JSONObject {
        if (stats.isEmpty()) {
            return JSONObject().put("frames", 0).put("perFrameSamples", 0).put("scope", SCOPE)
        }
        fun mean(values: List<Double>): Double =
            if (values.isEmpty()) 0.0 else values.sum() / values.size

        val totalDrawCalls = deltas(stats.map { it.totalDrawCalls.toLong() })
        val bufferUpdates = deltas(stats.map { it.bufferUpdates })
        val bufferBytes = deltas(stats.map { it.bufferUpdateBytes })
        val textureUpdates = deltas(stats.map { it.textureUpdates.toLong() })
        val textureBytes = deltas(stats.map { it.textureUpdateBytes })
        val createdTextures = deltas(stats.map { it.createdTextures.toLong() })
        return JSONObject()
            .put("frames", stats.size)
            .put("perFrameSamples", bufferUpdates.size)
            .put("scope", SCOPE)
            .put("drawCallsMean", mean(stats.map { it.drawCalls.toDouble() }))
            .put("drawCallsMax", stats.maxOf { it.drawCalls })
            .put("totalDrawCallsPerFrameMean", mean(totalDrawCalls))
            .put("totalDrawCallsPerFrameMax", totalDrawCalls.maxOrNull() ?: 0.0)
            .put("cumulativeTotalDrawCallsLast", stats.last().totalDrawCalls)
            .put("bufferUpdatesPerFrameMean", mean(bufferUpdates))
            .put("bufferUpdatesPerFrameMax", bufferUpdates.maxOrNull() ?: 0.0)
            .put("bufferUpdateBytesPerFrameMean", mean(bufferBytes))
            .put("bufferUpdateBytesPerFrameMax", bufferBytes.maxOrNull() ?: 0.0)
            .put("textureUpdatesPerFrameMean", mean(textureUpdates))
            .put("textureUpdateBytesPerFrameMean", mean(textureBytes))
            .put("createdTexturesPerFrameMean", mean(createdTextures))
            .put("cumulativeBufferUpdatesLast", stats.last().bufferUpdates)
            .put("cumulativeBufferUpdateBytesLast", stats.last().bufferUpdateBytes)
            .put("cumulativeTextureUpdateBytesLast", stats.last().textureUpdateBytes)
            .put("memBuffersMax", stats.maxOf { it.memBuffers })
            .put("memTexturesMax", stats.maxOf { it.memTextures })
    }

    /**
     * Consecutive differences, clamped at zero. A first sample has no
     * predecessor and contributes no delta; a reset counter (which should not
     * happen, but must not corrupt the report) clamps to zero instead of
     * producing a negative upload.
     */
    fun deltas(values: List<Long>): List<Double> =
        values.zipWithNext { previous, next ->
            (next - previous).coerceAtLeast(0L).toDouble()
        }
}

/**
 * MapLibre's `OnFpsChangedListener` reports `1e9 / delta` for every render
 * thread frame, not a smoothed rate. The elapsed average frame rate over the
 * recorded frames is `used / sum(delta_i)`, which equals the harmonic mean of
 * the reported rates, so a plain arithmetic mean of those rates
 * over-represents the short (fast) intervals. The very first reported rate
 * spans the idle interval between the last frame before the sweep and the
 * first recorded frame; it is discarded when at least two samples exist. The
 * probe's own frame timestamps remain the primary cadence evidence.
 */
internal object NativeFpsAggregator {

    const val SCOPE =
        "per-frame listener rates (1e9/delta, not smoothed); harmonic = elapsed average over " +
            "the used samples; the first sample (pre-sweep idle interval) is discarded when at " +
            "least two samples exist; primary cadence is the frame-timestamp series"

    fun used(samples: List<Double>): List<Double> {
        val finite = samples.filter { it.isFinite() && it > 0.0 }
        return if (finite.size >= 2) finite.drop(1) else finite
    }

    fun harmonicMean(samples: List<Double>): Double {
        val rates = used(samples)
        if (rates.isEmpty()) return 0.0
        val inverseSum = rates.sumOf { 1.0 / it }
        return if (inverseSum > 0.0) rates.size / inverseSum else 0.0
    }

    fun summarize(samples: List<Double>): JSONObject {
        val finite = samples.filter { it.isFinite() && it > 0.0 }
        val rates = used(samples).sorted()
        if (finite.isEmpty()) {
            return JSONObject()
                .put("samples", 0)
                .put("discardedFirst", false)
                .put("used", 0)
                .put("harmonic", 0.0)
                .put("arithmetic", 0.0)
                .put("min", 0.0)
                .put("p50", 0.0)
                .put("max", 0.0)
                .put("scope", SCOPE)
        }
        return JSONObject()
            .put("samples", finite.size)
            .put("discardedFirst", finite.size >= 2)
            .put("used", rates.size)
            .put("harmonic", harmonicMean(samples))
            .put("arithmetic", finite.sum() / finite.size)
            .put("min", rates.firstOrNull() ?: 0.0)
            .put("p50", FrameIntervalAggregator.percentile(rates, 0.5))
            .put("max", rates.lastOrNull() ?: 0.0)
            .put("scope", SCOPE)
    }
}

/**
 * Turns a request into native camera legs. Every leg uses the same pitch and
 * ends on the analytic path, so two runs of the same request animate the same
 * pixels; the host compares frame stats between runs and building modes.
 */
internal object MapPerfSweepPlan {

    /** Long enough for the native animator to hold a steady cadence. */
    private const val MIN_LEG_MS = 400

    fun legs(request: MapPerfSweepRequest): List<MapPerfLeg> {
        val total = request.durationMs.coerceAtLeast(MIN_LEG_MS.toLong())
        return when (request.motion) {
            MapPerfMotion.ORBIT -> orbitLegs(request, total)
            MapPerfMotion.PAN -> panLegs(request, total)
            MapPerfMotion.ZOOM -> zoomLegs(request, total)
            MapPerfMotion.COMBO -> comboLegs(request, total)
        }
    }

    private fun orbitLegs(request: MapPerfSweepRequest, totalMs: Long): List<MapPerfLeg> {
        val sweep = shortestBearingDelta(request.bearingStart, request.bearingEnd)
        val legs = legCount(kotlin.math.abs(sweep), 90.0)
        return List(legs) { index ->
            val start = request.bearingStart + sweep * index / legs
            val end = request.bearingStart + sweep * (index + 1) / legs
            MapPerfLeg(
                target = MapPerfPoint(request.lat, request.lon),
                zoom = request.zoomStart,
                tilt = request.tilt,
                bearing = normalizeBearing(end),
                durationMs = legDuration(totalMs, legs),
                note = "orbit %.0f->%.0f".format(Locale.US, normalizeBearing(start), normalizeBearing(end)),
            )
        }
    }

    private fun panLegs(request: MapPerfSweepRequest, totalMs: Long): List<MapPerfLeg> {
        val legs = panLegCount(request)
        return List(legs) { index ->
            val end = fraction(index + 1, legs)
            MapPerfLeg(
                target = MapPerfPoint(
                    request.lat + (request.latEnd - request.lat) * end,
                    request.lon + (request.lonEnd - request.lon) * end,
                ),
                zoom = request.zoomStart,
                tilt = request.tilt,
                bearing = normalizeBearing(request.bearingStart),
                durationMs = legDuration(totalMs, legs),
                note = "pan leg ${index + 1}/$legs",
            )
        }
    }

    private fun zoomLegs(request: MapPerfSweepRequest, totalMs: Long): List<MapPerfLeg> {
        val legs = legCount(kotlin.math.abs(request.zoomEnd - request.zoomStart), 1.5)
        return List(legs) { index ->
            val end = fraction(index + 1, legs)
            MapPerfLeg(
                target = MapPerfPoint(request.lat, request.lon),
                zoom = request.zoomStart + (request.zoomEnd - request.zoomStart) * end,
                tilt = request.tilt,
                bearing = normalizeBearing(request.bearingStart),
                durationMs = legDuration(totalMs, legs),
                note = "zoom leg ${index + 1}/$legs",
            )
        }
    }

    private fun comboLegs(request: MapPerfSweepRequest, totalMs: Long): List<MapPerfLeg> {
        val legs = 4
        val sweep = shortestBearingDelta(request.bearingStart, request.bearingEnd)
        return List(legs) { index ->
            val end = fraction(index + 1, legs)
            MapPerfLeg(
                target = MapPerfPoint(
                    request.lat + (request.latEnd - request.lat) * end,
                    request.lon + (request.lonEnd - request.lon) * end,
                ),
                zoom = request.zoomStart + (request.zoomEnd - request.zoomStart) * end,
                tilt = request.tilt,
                bearing = normalizeBearing(request.bearingStart + sweep * end),
                durationMs = legDuration(totalMs, legs),
                note = "combo leg ${index + 1}/$legs",
            )
        }
    }

    private fun panLegCount(request: MapPerfSweepRequest): Int {
        val metres = haversineMeters(request.lat, request.lon, request.latEnd, request.lonEnd)
        return (metres / 250.0).toInt().coerceIn(2, 6)
    }

    private fun legCount(span: Double, perLeg: Double): Int =
        (span / perLeg).let { if (it > it.toInt()) it.toInt() + 1 else it.toInt() }.coerceIn(1, 8)

    private fun legDuration(totalMs: Long, legs: Int): Int =
        (totalMs / legs).coerceAtLeast(MIN_LEG_MS.toLong()).toInt()

    private fun fraction(part: Int, whole: Int): Double = part.toDouble() / whole.toDouble()

    /**
     * Bearings animate the short way around the compass, except that an exact
     * full turn (start 0, end 360) stays a full turn instead of collapsing to
     * zero — that is the host's explicit "orbit once" request.
     */
    internal fun shortestBearingDelta(start: Double, end: Double): Double {
        val raw = end - start
        val delta = raw % 360.0
        if (delta == 0.0 && raw != 0.0) return if (raw > 0.0) 360.0 else -360.0
        if (delta > 180.0) return delta - 360.0
        if (delta < -180.0) return delta + 360.0
        return delta
    }

    internal fun normalizeBearing(bearing: Double): Double {
        val wrapped = bearing % 360.0
        return if (wrapped < 0.0) wrapped + 360.0 else wrapped
    }

    internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dPhi / 2).let { it * it } +
            kotlin.math.cos(phi1) * kotlin.math.cos(phi2) *
            kotlin.math.sin(dLambda / 2).let { it * it }
        return 2 * earthRadius * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0)))
    }
}

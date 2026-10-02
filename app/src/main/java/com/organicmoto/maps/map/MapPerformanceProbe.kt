package com.organicmoto.maps.map

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Debug-only native frame measurement for the real app map.
 *
 * MapLibre's own FPS listener reports a smoothed number, and Compose frame
 * callbacks only cover the UI thread, so neither can show a rider why the map
 * stutters. This probe records three independent series from the live view:
 *
 * - `MapView.OnDidFinishRenderingFrameListener` (boolean, double, double)
 *   callbacks with a wall-clock stamp taken inside the callback: the cadence
 *   of frames the native renderer actually finished, plus its CPU
 *   encoding/rendering durations.
 * - `Choreographer` vsync stamps on the UI thread: what the app observed as
 *   display cadence.
 * - `Window.OnFrameMetricsAvailableListener` total/swap durations: the
 *   platform's own per-frame presentation measurement.
 *
 * Nothing is attached unless a debug build explicitly asks for it, so release
 * builds keep the map untouched. The series are recorded only between
 * [begin] and [end]; between sweeps the probe costs nothing but two listener
 * registrations.
 */
internal data class FrameSample(
    /** `System.nanoTime()` taken inside the native finish-frame callback. */
    val nanos: Long,
    val fullyRendered: Boolean,
    /**
     * MapLibre's own CPU encoding time for this frame, in milliseconds.
     * `RenderingStats.encodingTime` is a `duration<double>` in *seconds*, so
     * the probe converts it once, here.
     */
    val encodingMs: Double,
    /** MapLibre's own CPU rendering time for this frame, in milliseconds. */
    val renderingMs: Double,
)

/**
 * Interval statistics for one frame series. Intervals are milliseconds between
 * consecutive frames; `durationMs` is the span the series covers.
 */
internal data class FrameIntervalStats(
    val frames: Int,
    val durationMs: Double,
    val fps: Double,
    val minMs: Double,
    val meanMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
    val stalls50: Int,
    val stalls100: Int,
    val fullyRenderedFrames: Int,
    /** Mean native CPU encoding time per frame (MapLibre's own number). */
    val encodingMeanMs: Double = 0.0,
    val encodingP95Ms: Double = 0.0,
    /** Mean native CPU rendering time per frame (MapLibre's own number). */
    val renderingMeanMs: Double = 0.0,
    val renderingP95Ms: Double = 0.0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("frames", frames)
        .put("durationMs", round3(durationMs))
        .put("fps", round3(fps))
        .put("minMs", round3(minMs))
        .put("meanMs", round3(meanMs))
        .put("p50Ms", round3(p50Ms))
        .put("p95Ms", round3(p95Ms))
        .put("p99Ms", round3(p99Ms))
        .put("maxMs", round3(maxMs))
        .put("stalls50", stalls50)
        .put("stalls100", stalls100)
        .put("fullyRenderedFrames", fullyRenderedFrames)
        .put("encodingMeanMs", round3(encodingMeanMs))
        .put("encodingP95Ms", round3(encodingP95Ms))
        .put("renderingMeanMs", round3(renderingMeanMs))
        .put("renderingP95Ms", round3(renderingP95Ms))

    companion object {
        val EMPTY = FrameIntervalStats(
            frames = 0,
            durationMs = 0.0,
            fps = 0.0,
            minMs = 0.0,
            meanMs = 0.0,
            p50Ms = 0.0,
            p95Ms = 0.0,
            p99Ms = 0.0,
            maxMs = 0.0,
            stalls50 = 0,
            stalls100 = 0,
            fullyRenderedFrames = 0,
        )

        private fun round3(value: Double): Double =
            if (value.isFinite()) Math.round(value * 1000.0) / 1000.0 else 0.0
    }
}

/**
 * Pure aggregation shared by the probe and its JVM tests. Percentiles use
 * linear interpolation between the two nearest order statistics (the common
 * `numpy.percentile` definition), which is stable for the small series a
 * 6-10 second sweep produces.
 */
internal object FrameIntervalAggregator {

    const val STALL_50_MS = 50.0
    const val STALL_100_MS = 100.0

    fun intervalsMs(frames: List<FrameSample>): List<Double> {
        if (frames.size < 2) return emptyList()
        return List(frames.size - 1) { index ->
            (frames[index + 1].nanos - frames[index].nanos) / 1_000_000.0
        }
    }

    fun stats(frames: List<FrameSample>): FrameIntervalStats {
        if (frames.isEmpty()) return FrameIntervalStats.EMPTY
        val intervals = intervalsMs(frames).sorted()
        val durationMs = if (frames.size >= 2) {
            (frames.last().nanos - frames.first().nanos) / 1_000_000.0
        } else {
            0.0
        }
        val sum = intervals.sum()
        val mean = if (intervals.isEmpty()) 0.0 else sum / intervals.size
        val fps = if (durationMs > 0.0) (frames.size - 1) * 1000.0 / durationMs else 0.0
        val encoding = frames.map { it.encodingMs }.sorted()
        val rendering = frames.map { it.renderingMs }.sorted()
        return FrameIntervalStats(
            frames = frames.size,
            durationMs = durationMs,
            fps = fps,
            minMs = intervals.firstOrNull() ?: 0.0,
            meanMs = mean,
            p50Ms = percentile(intervals, 0.50),
            p95Ms = percentile(intervals, 0.95),
            p99Ms = percentile(intervals, 0.99),
            maxMs = intervals.lastOrNull() ?: 0.0,
            stalls50 = intervals.count { it > STALL_50_MS },
            stalls100 = intervals.count { it > STALL_100_MS },
            fullyRenderedFrames = frames.count { it.fullyRendered },
            encodingMeanMs = if (encoding.isEmpty()) 0.0 else encoding.sum() / encoding.size,
            encodingP95Ms = percentile(encoding, 0.95),
            renderingMeanMs = if (rendering.isEmpty()) 0.0 else rendering.sum() / rendering.size,
            renderingP95Ms = percentile(rendering, 0.95),
        )
    }

    /** Aggregates a bare timestamp series (for example UI vsync stamps). */
    fun statsForNanos(nanos: List<Long>): FrameIntervalStats =
        stats(nanos.map { FrameSample(it, fullyRendered = true, encodingMs = 0.0, renderingMs = 0.0) })

    /**
     * Aggregates a per-frame *duration* series (window frame metrics), which is
     * not a timestamp series: every value is one frame's own cost, so the
     * percentiles are taken over the values themselves and `durationMs` is the
     * summed frame cost. `fps` is the inverse of the mean duration and only
     * describes how much frame time one frame costs, never the presented rate.
     */
    fun statsForDurations(nanos: List<Long>): FrameIntervalStats {
        if (nanos.isEmpty()) return FrameIntervalStats.EMPTY
        val durationsMs = nanos.map { it / 1_000_000.0 }.sorted()
        val mean = durationsMs.sum() / durationsMs.size
        return FrameIntervalStats(
            frames = durationsMs.size,
            durationMs = durationsMs.sum(),
            fps = if (mean > 0.0) 1000.0 / mean else 0.0,
            minMs = durationsMs.first(),
            meanMs = mean,
            p50Ms = percentile(durationsMs, 0.50),
            p95Ms = percentile(durationsMs, 0.95),
            p99Ms = percentile(durationsMs, 0.99),
            maxMs = durationsMs.last(),
            stalls50 = durationsMs.count { it > STALL_50_MS },
            stalls100 = durationsMs.count { it > STALL_100_MS },
            fullyRenderedFrames = 0,
        )
    }

    /**
     * Splits a sweep at the first fully rendered frame: everything up to and
     * including it is the cold segment (tiles, geometry and textures still
     * arriving), everything after is warmed motion. A warmed run may have no
     * cold frames, and a run that never reports a complete frame is all cold.
     */
    fun coldWarmSplit(frames: List<FrameSample>): Pair<List<FrameSample>, List<FrameSample>> {
        val boundary = frames.indexOfFirst { it.fullyRendered }
        if (boundary < 0) return frames to emptyList()
        return frames.subList(0, boundary + 1) to frames.subList(boundary + 1, frames.size)
    }

    fun percentile(sortedValues: List<Double>, fraction: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        if (sortedValues.size == 1) return sortedValues.first()
        val position = (sortedValues.size - 1) * fraction.coerceIn(0.0, 1.0)
        val lower = position.toInt()
        val upper = min(lower + 1, sortedValues.size - 1)
        val weight = position - lower
        return sortedValues[lower] * (1.0 - weight) + sortedValues[upper] * weight
    }

    /**
     * Longest run of frames whose consecutive intervals all stayed within
     * [budgetMs]; used by the on-device test to prove a sweep held a steady
     * cadence for a stretch instead of averaging one good burst.
     */
    fun longestSteadyRun(frames: List<FrameSample>, budgetMs: Double): Int {
        if (frames.isEmpty()) return 0
        return longestSteadyRunForIntervals(intervalsMs(frames), budgetMs)
    }

    fun longestSteadyRunForIntervals(intervals: List<Double>, budgetMs: Double): Int {
        var best = 1
        var run = 1
        for (interval in intervals) {
            run = if (interval <= budgetMs) run + 1 else 1
            best = max(best, run)
        }
        return best
    }
}

/** Everything one [MapPerformanceProbe] recording captured. */
internal data class SweepCapture(
    val label: String,
    val startedAtNanos: Long,
    val endedAtNanos: Long,
    val frames: List<FrameSample>,
    val uiFrameNanos: List<Long>,
    val presentationNanos: List<Long>,
    val swapNanos: List<Long>,
    val gpuNanos: List<Long>,
    val fpsSamples: List<Double>,
    val renderStats: List<RenderFrameStats>,
)

/**
 * Attaches native measurement listeners to one live map. The debug runner owns
 * the lifecycle: [attach] once when the map appears, [detach] when the screen
 * goes away, and [begin]/[end] around each sweep. All listener callbacks are
 * guarded by [recording], so a sweep never records a neighbour's frames.
 */
internal class MapPerformanceProbe(
    private val mapView: MapView,
    private val map: MapLibreMap,
    private val window: Window?,
    private val log: (String) -> Unit,
) {

    private val lock = Any()
    private val frames = ArrayList<FrameSample>()
    private val uiFrames = ArrayList<Long>()
    private val presentationFrames = ArrayList<Long>()
    private val swapFrames = ArrayList<Long>()
    private val gpuFrames = ArrayList<Long>()
    private val fpsSamples = ArrayList<Double>()
    private val renderStats = ArrayList<RenderFrameStats>()

    @Volatile
    private var recording = false

    @Volatile
    private var label = ""

    @Volatile
    private var lastFrameNanos = 0L

    @Volatile
    private var lastFrameFullyRendered = false

    @Volatile
    private var lastLogNanos = 0L

    private var attached = false
    private var metricsAttached = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()

    private val frameListener = MapView.OnDidFinishRenderingFrameListener { fullyRendered, encodingTime, renderingTime ->
        onNativeFrame(fullyRendered, encodingTime, renderingTime)
    }

    private val fpsListener = MapLibreMap.OnFpsChangedListener { fps ->
        if (!recording || !fps.isFinite()) return@OnFpsChangedListener
        synchronized(lock) { fpsSamples.add(fps) }
    }

    private val statsListener =
        MapView.OnDidFinishRenderingFrameWithStatsListener { _, stats ->
            if (!recording) return@OnDidFinishRenderingFrameWithStatsListener
            synchronized(lock) {
                renderStats.add(
                    RenderFrameStats(
                        drawCalls = stats.numDrawCalls,
                        totalDrawCalls = stats.totalDrawCalls,
                        bufferUpdates = stats.bufferUpdates,
                        bufferUpdateBytes = stats.bufferUpdateBytes,
                        textureUpdates = stats.numTextureUpdates,
                        textureUpdateBytes = stats.textureUpdateBytes,
                        createdTextures = stats.numCreatedTextures,
                        memBuffers = stats.memBuffers.toLong(),
                        memTextures = stats.memTextures.toLong(),
                    ),
                )
            }
        }

    private val uiFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!recording) return
            synchronized(lock) { uiFrames.add(frameTimeNanos) }
            choreographer.postFrameCallback(this)
        }
    }

    private val frameMetricsListener =
        Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (!recording) return@OnFrameMetricsAvailableListener
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val swap = metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
            val gpu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                metrics.getMetric(FrameMetrics.GPU_DURATION)
            } else {
                -1L
            }
            synchronized(lock) {
                presentationFrames.add(total)
                if (swap >= 0L) swapFrames.add(swap)
                if (gpu >= 0L) gpuFrames.add(gpu)
            }
        }

    fun attach() {
        if (attached) return
        attached = true
        mapView.addOnDidFinishRenderingFrameListener(frameListener)
        mapView.addOnDidFinishRenderingFrameListener(statsListener)
        map.setOnFpsChangedListener(fpsListener)
        log("attached native frame probe to ${mapView.width}x${mapView.height} map")
    }

    fun detach() {
        if (!attached) return
        attached = false
        recording = false
        choreographer.removeFrameCallback(uiFrameCallback)
        detachFrameMetrics()
        mapView.removeOnDidFinishRenderingFrameListener(frameListener)
        mapView.removeOnDidFinishRenderingFrameListener(statsListener)
        map.setOnFpsChangedListener(null)
        log("detached native frame probe")
    }

    /** Starts a recording; returns false when one is already running. */
    fun begin(sweepLabel: String): Boolean {
        synchronized(lock) {
            if (recording) return false
            frames.clear()
            uiFrames.clear()
            presentationFrames.clear()
            swapFrames.clear()
            gpuFrames.clear()
            fpsSamples.clear()
            renderStats.clear()
            label = sweepLabel
            recording = true
        }
        lastFrameNanos = 0L
        lastFrameFullyRendered = false
        lastLogNanos = 0L
        attachFrameMetrics()
        choreographer.postFrameCallback(uiFrameCallback)
        return true
    }

    /** Stops the recording and returns the captured series, or null if idle. */
    fun end(): SweepCapture? {
        val capture: SweepCapture
        synchronized(lock) {
            if (!recording) return null
            recording = false
            capture = SweepCapture(
                label = label,
                startedAtNanos = frames.firstOrNull()?.nanos ?: 0L,
                endedAtNanos = frames.lastOrNull()?.nanos ?: 0L,
                frames = frames.toList(),
                uiFrameNanos = uiFrames.toList(),
                presentationNanos = presentationFrames.toList(),
                swapNanos = swapFrames.toList(),
                gpuNanos = gpuFrames.toList(),
                fpsSamples = fpsSamples.toList(),
                renderStats = renderStats.toList(),
            )
        }
        choreographer.removeFrameCallback(uiFrameCallback)
        detachFrameMetrics()
        return capture
    }

    /** True when the most recent native frame reported the map complete. */
    fun lastFrameFullyRendered(): Boolean = lastFrameFullyRendered

    fun lastFrameNanos(): Long = lastFrameNanos

    fun isRecording(): Boolean = recording

    private fun onNativeFrame(fullyRendered: Boolean, encodingTime: Double, renderingTime: Double) {
        val now = System.nanoTime()
        lastFrameNanos = now
        lastFrameFullyRendered = fullyRendered
        if (!recording) return
        // MapLibre reports both CPU times in seconds (`duration<double>`), not
        // milliseconds; publishing them as milliseconds without this factor
        // understated a 263 ms frame as "0.263 ms" and hid the real bottleneck.
        val encodingMs = (encodingTime * 1000.0).takeIf { it.isFinite() } ?: 0.0
        val renderingMs = (renderingTime * 1000.0).takeIf { it.isFinite() } ?: 0.0
        synchronized(lock) {
            frames.add(
                FrameSample(
                    nanos = now,
                    fullyRendered = fullyRendered,
                    encodingMs = encodingMs,
                    renderingMs = renderingMs,
                ),
            )
        }
        if (lastLogNanos == 0L || now - lastLogNanos >= 1_000_000_000L) {
            lastLogNanos = now
            val stats = snapshotStats()
            log(
                "sweep '$label' fps=${"%.1f".format(Locale.US, stats.fps)} " +
                    "frames=${stats.frames} fullyRendered=${stats.fullyRenderedFrames} " +
                    "p95=${"%.1f".format(Locale.US, stats.p95Ms)}ms",
            )
        }
    }

    private fun snapshotStats(): FrameIntervalStats {
        val copy = synchronized(lock) { frames.toList() }
        return FrameIntervalAggregator.stats(copy)
    }

    private fun attachFrameMetrics() {
        val activeWindow = window ?: return
        if (metricsAttached) return
        metricsAttached = true
        runCatching { activeWindow.addOnFrameMetricsAvailableListener(frameMetricsListener, mainHandler) }
            .onFailure {
                metricsAttached = false
                log("frame metrics unavailable: ${it.message}")
            }
    }

    private fun detachFrameMetrics() {
        val activeWindow = window ?: return
        if (!metricsAttached) return
        metricsAttached = false
        runCatching { activeWindow.removeOnFrameMetricsAvailableListener(frameMetricsListener) }
    }
}

/**
 * Gate shared with RouteScreen: while a sweep drives the camera, the debug
 * camera snapshot writer must stay quiet or it would add 60 file writes per
 * second to the very measurement it is meant to observe.
 */
internal object MapPerfProbeGate {
    @Volatile
    var sweepActive: Boolean = false
        private set

    fun enter() {
        sweepActive = true
    }

    fun exit() {
        sweepActive = false
    }
}

package com.organicmoto.maps.routing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.ResponsePath
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.util.shapes.GHPoint
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TAG = "OrganicMoto.Router"


/** Serializes cache swaps with the destructive graph-copy instrumentation cases. */
internal val GRAPH_CACHE_COPY_LOCK = Any()
private const val MAX_DISPLAYED_ROUTES = 3

/** The primary route is first; later entries are accepted alternatives. */
class RouteResult(routes: List<ResponsePath>) {
    val routes: List<ResponsePath> = Collections.unmodifiableList(routes.toList())

    init {
        require(this.routes.isNotEmpty() && this.routes.size <= MAX_DISPLAYED_ROUTES) {
            "A route result must contain between one and $MAX_DISPLAYED_ROUTES routes"
        }
    }
}

/**
 * GraphHopper-backed router.
 *
 * [graphDir] is the dataset's graph directory: the bundled fallback still
 * copies from APK assets on first use, while an installed regional package
 * reads its immutable directory directly. [close] waits for in-flight routing
 * to finish and then releases the graph's MMAP handles, which is required
 * before switching to another region's dataset.
 */
class GraphHopperRouter(
    context: Context,
    private val graphDir: File = File(context.applicationContext.filesDir, "gh-cache"),
    private val copyGraphFromAssets: Boolean = true,
) {

    private val appContext = context.applicationContext
    private val lifecycleLock = ReentrantReadWriteLock()
    @Volatile
    private var closed = false

    // Distinct routes already assigned to each dial-detent level (one knob
    // click). Keeping the edge-distance signature lets later detents reject
    // routes that share more than the configured road percentage with any
    // earlier one.
    private data class RouteKey(
        val points: List<Pair<Double, Double>>,
        val blockUnpaved: Boolean,
    )
    private data class CachedRouteSet(
        val routes: List<AlternativePolicy.CachedRoute>,
        val maxRoadSharePercent: Int,
    )



    // Bounded LRU over endpoint pairs: every entry holds full geometries for
    // several detents, so an unbounded map grew heap on long exploratory
    // sessions. Access-order keeps hot pairs; the eldest pair drops at 9.
    private val routeCache =
        object : LinkedHashMap<RouteKey, MutableMap<Int, CachedRouteSet>>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<RouteKey, MutableMap<Int, CachedRouteSet>>,
            ): Boolean = size > MAX_CACHED_ENDPOINT_PAIRS
        }

    private val hopperDelegate = lazy {
        synchronized(GRAPH_CACHE_COPY_LOCK) {
            withGraphCacheFileLock {
                Log.i(TAG, "Initializing GraphHopper (graph dir: ${graphDir.absolutePath})")
                val started = SystemClock.elapsedRealtime()
                if (copyGraphFromAssets) {
                    copyGraphFromAssetsIfNeededUnlocked()
                }
                if (!graphDir.isDirectory || graphDir.listFiles().isNullOrEmpty()) {
                    throw IllegalStateException(
                        "Graph data not found in ${graphDir.absolutePath}. " +
                            "Install a region package or build the bundled graph " +
                            "with tools/gh/config.yml into app/src/main/assets/graph-cache"
                    )
                }
                val config = GraphHopperConfig().apply {
                    putObject("graph.location", graphDir.absolutePath)
                    putObject("graph.dataaccess.default_type", "MMAP")
                    putObject("import.osm.ignored_highways", "footway,steps,corridor,bridleway")
                }
                val loaded = object : GraphHopper() {
                    // Install a factory that builds the custom motorcycle weighting from
                    // the pre-compiled MotorcycleWeightingHelper instead of Janino (which
                    // emits JVM bytecode ART cannot load).
                    override fun createWeightingFactory(): WeightingFactory =
                        MotorcycleWeightingFactory(baseGraph, encodingManager)
                }.apply {
                    init(config)
                    setPathDetailsBuilderFactory(MotoPathDetailsBuilderFactory())
                    setProfiles(motorcycleProfile())
                    importOrLoad()
                }
                Log.i(TAG, "GraphHopper ready in ${SystemClock.elapsedRealtime() - started} ms")
                loaded
            }
        }
    }

    private val hopper: GraphHopper by hopperDelegate

    /**
     * Releases the graph. Waits for any in-flight [route] call, so the MMAP
     * files are never unmapped underneath a routing thread. Safe to call twice.
     */
    fun close() {
        lifecycleLock.writeLock().withLock { doClose() }
    }

    /**
     * Releases the graph without ever blocking the caller: when no route is in
     * flight the work happens inline, otherwise a short-lived daemon thread
     * waits for the route and then closes. Used by screen disposal.
     *
     * Returns the daemon thread that is draining an in-flight route, or null
     * when the close completed inline. Callers that must not delete the graph
     * directory under a live reader join the returned thread off the UI thread
     * before doing so.
     */
    fun closeAsync(): Thread? {
        if (lifecycleLock.writeLock().tryLock()) {
            try {
                doClose()
            } finally {
                lifecycleLock.writeLock().unlock()
            }
            return null
        }
        return Thread({ close() }, "gh-close").apply { isDaemon = true; start() }
    }

    private fun doClose() {
        if (closed) return
        closed = true
        if (hopperDelegate.isInitialized()) {
            runCatching { hopper.close() }
                .onFailure {
                    // ART has no sun.misc.Unsafe.invokeCleaner, so GraphHopper's
                    // explicit MMAP cleanup cannot run; the mapping is reclaimed
                    // by the garbage collector instead.
                    Log.i(TAG, "GraphHopper close deferred to the GC: ${it.message}")
                }
        }
        Log.i(TAG, "GraphHopper released (${graphDir.name})")
    }

    private inline fun <T> withGraphCacheFileLock(block: () -> T): T {
        val lockFile = File(appContext.filesDir, "gh-cache.lock")
        return FileChannel.open(
            lockFile.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use { block() }
        }
    }

    internal fun copyGraphFromAssetsIfNeeded() {
        synchronized(GRAPH_CACHE_COPY_LOCK) {
            withGraphCacheFileLock { copyGraphFromAssetsIfNeededUnlocked() }
        }
    }

    private fun copyGraphFromAssetsIfNeededUnlocked() {
        if (File(graphDir, COPY_COMPLETE_MARKER).isFile) {
            File(appContext.filesDir, "gh-cache.tmp").deleteRecursively()
            return
        }
        Log.i(TAG, "graph-cache missing or incomplete — copying from APK assets")
        val assets = appContext.assets
        val assetRoot = "graph-cache"
        val entries = assets.list(assetRoot)
            ?: throw IllegalStateException("No assets/$assetRoot found in the APK")
        // Copy into a sibling directory and swap only once complete: a process
        // death mid-copy then leaves an ignorable .tmp directory instead of a
        // half-written graph the loader would treat as complete forever.
        val stagingDir = File(appContext.filesDir, "gh-cache.tmp")
        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        entries.forEach { copyAssetRecursive("$assetRoot/$it", stagingDir) }
        File(stagingDir, COPY_COMPLETE_MARKER).writeText("ok")
        graphDir.deleteRecursively()
        if (!stagingDir.renameTo(graphDir)) {
            stagingDir.deleteRecursively()
            throw IllegalStateException("Could not move the copied graph into place")
        }
        Log.i(TAG, "graph-cache copy complete")
    }

    private fun copyAssetRecursive(assetPath: String, targetDir: File) {
        val name = assetPath.substringAfterLast('/')
        val target = File(targetDir, name)
        val children = assetsOf(assetPath)
        if (children != null) {
            target.mkdirs()
            children.forEach { copyAssetRecursive("$assetPath/$it", target) }
        } else {
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun assetsOf(assetPath: String): Array<String>? = runCatching {
        appContext.assets.list(assetPath)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Routes [from] to [to] with the motorcycle profile.
     *
     * [complexity] is the unbounded ride-complexity dial detent in levels
     * (default 0; one level per knob click):
     *  - 0: fastest route over motorcycle-model travel speeds.
     *  - each positive level requests a materially distinct alternative with
     *    stronger motorcycle/curve preference and a progressively wider
     *    detour budget.
     *
     * The first returned route is the primary route. Positive detents can
     * return up to two additional routes. [maxRoadShare] is a strong diversity
     * target against every route already assigned to this endpoint pair. If the
     * network cannot meet it, routing returns the most distinct meaningful
     * candidate available rather than rejecting the ride. When [blockUnpaved]
     * is true, known unpaved surface types are hard-blocked.
     */
    fun route(
        from: GHPoint,
        to: GHPoint,
        complexity: Double = 0.0,
        maxRoadShare: Double = DEFAULT_MAX_ROUTE_SHARE,
        blockUnpaved: Boolean = false,
        viaPoints: List<GHPoint> = emptyList(),
    ): RouteResult {
        lifecycleLock.readLock().withLock {
            check(!closed) { "The routing engine is no longer available" }
            return routeLocked(from, to, complexity, maxRoadShare, blockUnpaved, viaPoints)
        }
    }

    private fun routeLocked(
        from: GHPoint,
        to: GHPoint,
        complexity: Double,
        maxRoadShare: Double,
        blockUnpaved: Boolean,
        viaPoints: List<GHPoint>,
    ): RouteResult {
        if (viaPoints.isEmpty() && abs(from.lat - to.lat) < 1e-4 && abs(from.lon - to.lon) < 1e-4) {
            throw IllegalStateException("No route was found")
        }
        // Intentionally no coordinates in the log: from/to are user-supplied.
        val detent = complexity.coerceAtLeast(0.0).roundToInt()
        val safeMaxRoadShare = maxRoadShare.coerceIn(0.10, 0.90)
        val sharePercent = (safeMaxRoadShare * 100.0).roundToInt()
        val requestPoints = listOf(from) + viaPoints + to
        val key = RouteKey(requestPoints.map { it.lat to it.lon }, blockUnpaved)
        synchronized(routeCache) {
            routeCache[key]?.get(detent)?.let { cached ->
                if (detent == 0 || cached.maxRoadSharePercent == sharePercent) {
                    Log.i(TAG, "Using cached route set for detent $detent")
                    return RouteResult(cached.routes.map { it.path })
                }
            }
        }

        val previous = synchronized(routeCache) {
            routeCache[key]
                ?.filterKeys { it < detent }
                ?.values
                ?.flatMap { it.routes }
                ?.toList()
                .orEmpty()
        }
        val previousEdgeIds = previous.flatMapTo(mutableSetOf()) { it.edgeDistances.keys }

        // The first returned path of the first attempt is this detent's own
        // softly diversified optimum: it defines the ride the dial position
        // asks for. Alternatives are safeguarded relative to it, never against
        // the much faster complexity-0 route — a curvy ride is legitimately
        // slow, and judging it against Fastest made mid detents fail entirely.
        var referenceRoute: AlternativePolicy.CachedRoute? = null
        val started = SystemClock.elapsedRealtime()
        var rejectedAsDetour = 0
        val accepted = mutableListOf<AlternativePolicy.CachedRoute>()
        val fallbackCandidates = mutableListOf<AlternativePolicy.FallbackCandidate>()

        // Start with the requested overlap, then widen GraphHopper's INTERNAL
        // candidate pool on retries. Our own overlap check remains unchanged;
        // the wider pool is what lets us find a useful best-effort route when
        // the road network cannot meet the target exactly.
        // Multi-leg GPX requests use the standard algorithm, which returns one
        // path. Retrying is useful only for two-point alternative discovery.
        val attempts = if (viaPoints.isEmpty()) 0..2 else 0..0
        for (attempt in attempts) {
            if (accepted.size >= MAX_DISPLAYED_ROUTES) break
            val request = buildGhRequest(
                from = from,
                to = to,
                detent = detent,
                blockUnpaved = blockUnpaved,
                maxRoadShare = safeMaxRoadShare,
                attempt = attempt,
                previousEdgeIds = previousEdgeIds,
                viaPoints = viaPoints,
            )
            Log.i(TAG, "Requesting route detent $detent (diversification attempt $attempt)")
            val response = hopper.route(request)
            if (response.hasErrors()) {
                val details = response.errors.joinToString("; ") {
                    it.message ?: it.javaClass.simpleName
                }
                throw IllegalStateException("Routing failed: $details")
            }

            // The fastest detent intentionally stores only GraphHopper's first
            // path. Alternative candidates are enabled only for positive
            // detents.
            if (detent == 0) {
                val path = response.all.firstOrNull()
                    ?: throw IllegalStateException("No route was found")
                val fastestRoute = AlternativePolicy.cachedRoute(path)
                synchronized(routeCache) {
                    routeCache.getOrPut(key) { mutableMapOf() }[detent] =
                        CachedRouteSet(listOf(fastestRoute), sharePercent)
                }
                Log.i(
                    TAG,
                    "Fastest route found in ${SystemClock.elapsedRealtime() - started} ms " +
                        "(${path.distance}m)",
                )
                return RouteResult(listOf(path))
            }

            for (path in response.all) {
                if (accepted.size >= MAX_DISPLAYED_ROUTES) break
                val candidate = AlternativePolicy.cachedRoute(path)
                val reference = referenceRoute
                val isPrimary = reference == null
                if (isPrimary) referenceRoute = candidate
                val withinDetourBudget = reference == null ||
                    AlternativePolicy.withinGlobalDetourBudget(candidate.path, reference.path, detent)
                val isLocalBubble = reference != null &&
                    AlternativePolicy.hasExcessiveLocalDetour(candidate.edgeSections, reference.edgeSections)
                if (isLocalBubble) {
                    rejectedAsDetour++
                    continue
                }
                val diversity = AlternativePolicy.routeDiversity(candidate, previous)
                if (fallbackCandidates.none { it.route.edgeDistances == candidate.edgeDistances }) {
                    fallbackCandidates += AlternativePolicy.FallbackCandidate(
                        candidate,
                        diversity,
                        withinDetourBudget,
                        isPrimary,
                    )
                }
                if (!withinDetourBudget ||
                    !AlternativePolicy.isMeaningfullyDifferent(candidate, diversity, previous) ||
                    diversity.maxOverlap > safeMaxRoadShare
                ) continue
                val siblingOverlap = accepted.maxOfOrNull {
                    AlternativePolicy.sharedRoadFraction(candidate.edgeDistances, it.edgeDistances)
                } ?: 0.0
                if (siblingOverlap > safeMaxRoadShare) continue
                accepted += candidate
            }
        }

        if (accepted.isNotEmpty()) {
            val cachedSet = CachedRouteSet(accepted.toList(), sharePercent)
            synchronized(routeCache) {
                routeCache.getOrPut(key) { mutableMapOf() }[detent] = cachedSet
            }
            if (accepted.size < MAX_DISPLAYED_ROUTES) {
                Log.w(
                    TAG,
                    "Only ${accepted.size} sensible routes found at detent $detent; " +
                        "showing the valid subset",
                )
            } else {
                Log.i(
                    TAG,
                    "Distinct route set $detent found in " +
                        "${SystemClock.elapsedRealtime() - started} ms",
                )
            }
            return RouteResult(accepted.map { it.path })
        }

        // The percentage is a strong target, not a reason to strand the rider.
        // Prefer a meaningful candidate that reaches it even when the candidate
        // exceeds the normal detour budget; otherwise take the most distinct
        // sensible candidate. A tiny side-street-only variation ranks below the
        // detent's own primary, so we show the honest optimum instead of
        // manufacturing novelty.
        val fallback = fallbackCandidates.minWithOrNull(
            compareBy<AlternativePolicy.FallbackCandidate> {
                AlternativePolicy.fallbackTier(it, safeMaxRoadShare, previous)
            }.thenBy { it.diversity.maxOverlap }
                .thenByDescending { it.diversity.longestDistinctStretch }
                .thenBy { it.route.path.time }
        ) ?: referenceRoute?.let {
            AlternativePolicy.FallbackCandidate(it, AlternativePolicy.routeDiversity(it, previous), true, true)
        } ?: throw IllegalStateException("No route was found")
        val cachedSet = CachedRouteSet(listOf(fallback.route), sharePercent)
        synchronized(routeCache) {
            routeCache.getOrPut(key) { mutableMapOf() }[detent] = cachedSet
        }
        Log.w(
            TAG,
            "Shared-road target unavailable at detent $detent; returning best route " +
                "(${(fallback.diversity.maxOverlap * 100.0).roundToInt()}% overlap, " +
                "detours rejected=$rejectedAsDetour)",
        )
        return RouteResult(listOf(fallback.route.path))
    }


    private companion object {
        const val DEFAULT_MAX_ROUTE_SHARE = 0.70
        const val COPY_COMPLETE_MARKER = ".copy-complete"
        const val MAX_CACHED_ENDPOINT_PAIRS = 8
    }
}

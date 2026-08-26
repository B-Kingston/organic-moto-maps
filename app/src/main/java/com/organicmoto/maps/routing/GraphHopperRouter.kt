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

class GraphHopperRouter(context: Context) {

    private val appContext = context.applicationContext
    private val graphDir = File(appContext.filesDir, "gh-cache")

    // Distinct routes already assigned to each dial-detent level (one knob
    // click). Keeping the edge-distance signature lets later detents reject
    // routes that share more than the configured road percentage with any
    // earlier one.
    private data class RouteKey(
        val fromLat: Double,
        val fromLon: Double,
        val toLat: Double,
        val toLon: Double,
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

    private val hopper: GraphHopper by lazy {
        synchronized(GRAPH_CACHE_COPY_LOCK) {
            withGraphCacheFileLock {
                Log.i(TAG, "Initializing GraphHopper (graph dir: ${graphDir.absolutePath})")
                val started = SystemClock.elapsedRealtime()
                copyGraphFromAssetsIfNeededUnlocked()
                if (!graphDir.isDirectory || graphDir.listFiles().isNullOrEmpty()) {
                    throw IllegalStateException(
                        "Graph data not found. Build the graph with tools/gh/config.yml " +
                            "and place it in app/src/main/assets/graph-cache"
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
                    setProfiles(motorcycleProfile())
                    importOrLoad()
                }
                Log.i(TAG, "GraphHopper ready in ${SystemClock.elapsedRealtime() - started} ms")
                loaded
            }
        }
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
    ): RouteResult {
        if (abs(from.lat - to.lat) < 1e-4 && abs(from.lon - to.lon) < 1e-4) {
            throw IllegalStateException("No route was found")
        }
        // Intentionally no coordinates in the log: from/to are user-supplied.
        val detent = complexity.coerceAtLeast(0.0).roundToInt()
        val safeMaxRoadShare = maxRoadShare.coerceIn(0.10, 0.90)
        val sharePercent = (safeMaxRoadShare * 100.0).roundToInt()
        val key = RouteKey(from.lat, from.lon, to.lat, to.lon, blockUnpaved)
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
        for (attempt in 0..2) {
            if (accepted.size >= MAX_DISPLAYED_ROUTES) break
            val request = buildGhRequest(
                from = from,
                to = to,
                detent = detent,
                blockUnpaved = blockUnpaved,
                maxRoadShare = safeMaxRoadShare,
                attempt = attempt,
                previousEdgeIds = previous.flatMapTo(mutableSetOf()) { it.edgeDistances.keys },
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

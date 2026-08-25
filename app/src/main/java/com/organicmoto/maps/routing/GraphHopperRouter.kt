package com.organicmoto.maps.routing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.ResponsePath
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.config.Profile
import com.graphhopper.json.Statement
import com.graphhopper.util.CustomModel
import com.graphhopper.util.DistanceCalcEarth
import com.graphhopper.util.Parameters
import com.graphhopper.util.shapes.GHPoint
import java.io.File
import java.util.Collections
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "OrganicMoto.Router"

/**
 * Profile name shared by the router and [MotorcycleWeightingFactory]. Must stay
 * `motorcycle` in all three places: `tools/gh/config.yml`, this constant, and
 * the request in [route] (GraphHopper matches profiles by name).
 */
internal const val MOTORCYCLE_PROFILE = "motorcycle"

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

    private data class EdgeSection(
        val edgeId: Int,
        val forward: Boolean,
        val distance: Double,
    )

    private data class EdgeTraversal(
        val edgeId: Int,
        val forward: Boolean,
    )

    private data class CachedRoute(
        val path: ResponsePath,
        val edgeDistances: Map<Int, Double>,
        val edgeSections: List<EdgeSection>,
    )

    private data class CachedRouteSet(
        val routes: List<CachedRoute>,
        val maxRoadSharePercent: Int,
    )

    private data class RouteDiversity(
        val maxOverlap: Double,
        val longestDistinctStretch: Double,
    )

    private data class FallbackCandidate(
        val route: CachedRoute,
        val diversity: RouteDiversity,
        val withinDetourBudget: Boolean,
        val isPrimary: Boolean,
    )

    private val routeCache = mutableMapOf<RouteKey, MutableMap<Int, CachedRouteSet>>()

    private val hopper: GraphHopper by lazy {
        Log.i(TAG, "Initializing GraphHopper (graph dir: ${graphDir.absolutePath})")
        val started = SystemClock.elapsedRealtime()
        copyGraphFromAssetsIfNeeded()
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

    /**
     * Rebuilds the import-time motorcycle profile bit-for-bit. The stored graph
     * was imported with `custom_model_files: [motorcycle.json]`, which GraphHopper
     * 11 resolves to the JAR's built-in model
     * (`/com/graphhopper/custom_models/motorcycle.json` classpath resource, see
     * `GraphHopper.resolveCustomModelFiles`). `tools/gh/motorcycle.json` is the
     * checked-in canonical reference copy of that model and must stay identical to
     * it (verified by `tools/gh/generate-weighting.sh`).
     *
     * At load time GraphHopper compares the stored `profiles` property
     * (`motorcycle|198752012` for the current graph) against the string rendered
     * from THIS profile, which hashes the whole hints PMap — including PMap hint
     * INSERTION ORDER. `Profile(name)` pre-seeds an empty `custom_model` hint, so
     * it must be removed first, then `custom_model_files` re-added, then the model
     * attached, mirroring the YAML/import path exactly. The custom_model_files
     * VALUE `[motorcycle.json]` is part of that hash too — do not rename it.
     * Do not reorder or "simplify" these steps or the load will fail with a
     * profile mismatch.
     */
    private fun motorcycleProfile(): Profile {
        val customModel = CustomModel().apply {
            setDistanceInfluence(90.0)
            addToPriority(Statement.If("!car_access", Statement.Op.MULTIPLY, "0"))
            addToPriority(Statement.If("track_type.ordinal() > 1", Statement.Op.MULTIPLY, "0"))
            addToPriority(Statement.If("road_access == PRIVATE", Statement.Op.MULTIPLY, "0"))
            addToPriority(Statement.If("road_access == DESTINATION", Statement.Op.MULTIPLY, "0.1"))
            addToPriority(
                Statement.If("road_class == MOTORWAY || road_class == TRUNK", Statement.Op.MULTIPLY, "0.1")
            )
            addToSpeed(Statement.If("true", Statement.Op.LIMIT, "0.9 * car_average_speed"))
            addToSpeed(Statement.If("true", Statement.Op.LIMIT, "120"))
            addToSpeed(
                Statement.If(
                    "surface==COBBLESTONE || surface==GRASS || surface==GRAVEL || surface==SAND || " +
                        "surface==PAVING_STONES || surface==DIRT || surface==GROUND || " +
                        "surface==UNPAVED || surface==COMPACTED",
                    Statement.Op.LIMIT,
                    "30",
                ),
            )
        }
        return Profile(MOTORCYCLE_PROFILE).apply {
            hints.remove("custom_model")
            putHint("custom_model_files", listOf("motorcycle.json"))
            setCustomModel(customModel)
        }
    }

    private fun copyGraphFromAssetsIfNeeded() {
        if (graphDir.isDirectory && !graphDir.listFiles().isNullOrEmpty()) return
        Log.i(TAG, "graph-cache missing or empty — copying from APK assets")
        val assets = appContext.assets
        val assetRoot = "graph-cache"
        val entries = assets.list(assetRoot)
            ?: throw IllegalStateException("No assets/$assetRoot found in the APK")
        graphDir.mkdirs()
        entries.forEach { copyAssetRecursive("$assetRoot/$it", graphDir) }
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
        var referenceRoute: CachedRoute? = null
        val started = SystemClock.elapsedRealtime()
        var rejectedAsDetour = 0
        val accepted = mutableListOf<CachedRoute>()
        val fallbackCandidates = mutableListOf<FallbackCandidate>()

        // Start with the requested overlap, then widen GraphHopper's INTERNAL
        // candidate pool on retries. Our own overlap check remains unchanged;
        // the wider pool is what lets us find a useful best-effort route when
        // the road network cannot meet the target exactly.
        for (attempt in 0..2) {
            if (accepted.size >= MAX_DISPLAYED_ROUTES) break
            val request = GHRequest(from, to)
                .setProfile(MOTORCYCLE_PROFILE)
                .setPathDetails(listOf("edge_id"))
            request.putHint(Parameters.CH.DISABLE, true)
            request.putHint(MOTO_COMPLEXITY, detent.toDouble())
            request.putHint(BLOCK_UNPAVED, blockUnpaved)
            if (detent > 0) {
                request.putHint(
                    MOTO_PREVIOUS_EDGES,
                    previous.flatMapTo(mutableSetOf()) { it.edgeDistances.keys },
                )
                request.putHint(
                    MOTO_PREVIOUS_EDGE_PENALTY,
                    previousRoadPenalty(safeMaxRoadShare, detent, attempt),
                )
                request.setAlgorithm(Parameters.Algorithms.ALT_ROUTE)
                request.putHint(
                    Parameters.Algorithms.AltRoute.MAX_PATHS,
                    (8 + detent * 3 + attempt * 6).coerceAtMost(40),
                )
                request.putHint(
                    Parameters.Algorithms.AltRoute.MAX_SHARE,
                    min(MAX_CANDIDATE_SHARE, safeMaxRoadShare + attempt * CANDIDATE_SHARE_STEP),
                )
                request.putHint(
                    Parameters.Algorithms.AltRoute.MAX_WEIGHT,
                    min(
                        MAX_ALTERNATIVE_WEIGHT,
                        maxAlternativeWeight(detent) + attempt * CANDIDATE_WEIGHT_STEP,
                    ),
                )
                request.putHint(
                    "alternative_route.max_exploration_factor",
                    1.2 + detent * 0.15 + attempt * 0.75,
                )
                request.putHint("alternative_route.min_plateau_factor", MIN_PLATEAU_FACTOR)
            }
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
                val fastestRoute = cachedRoute(path)
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
                val candidate = cachedRoute(path)
                val reference = referenceRoute
                val isPrimary = reference == null
                if (isPrimary) referenceRoute = candidate
                val withinDetourBudget = reference == null ||
                    withinGlobalDetourBudget(candidate.path, reference.path, detent)
                val isLocalBubble = reference != null &&
                    hasExcessiveLocalDetour(candidate.edgeSections, reference.edgeSections)
                if (isLocalBubble) {
                    rejectedAsDetour++
                    continue
                }
                val diversity = routeDiversity(candidate, previous)
                if (fallbackCandidates.none { it.route.edgeDistances == candidate.edgeDistances }) {
                    fallbackCandidates += FallbackCandidate(
                        candidate,
                        diversity,
                        withinDetourBudget,
                        isPrimary,
                    )
                }
                if (!withinDetourBudget ||
                    !isMeaningfullyDifferent(candidate, diversity, previous) ||
                    diversity.maxOverlap > safeMaxRoadShare
                ) continue
                val siblingOverlap = accepted.maxOfOrNull {
                    sharedRoadFraction(candidate.edgeDistances, it.edgeDistances)
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
            compareBy<FallbackCandidate> {
                fallbackTier(it, safeMaxRoadShare, previous)
            }.thenBy { it.diversity.maxOverlap }
                .thenByDescending { it.diversity.longestDistinctStretch }
                .thenBy { it.route.path.time }
        ) ?: referenceRoute?.let {
            FallbackCandidate(it, routeDiversity(it, previous), true, true)
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

    private fun fallbackTier(
        candidate: FallbackCandidate,
        maxRoadShare: Double,
        previous: List<CachedRoute>,
    ): Int {
        val meaningful = isMeaningfullyDifferent(candidate.route, candidate.diversity, previous)
        return when {
            meaningful && candidate.diversity.maxOverlap <= maxRoadShare -> 0
            meaningful && candidate.withinDetourBudget -> 1
            meaningful -> 2
            candidate.isPrimary -> 3
            else -> 4
        }
    }

    private fun routeDiversity(
        candidate: CachedRoute,
        previous: List<CachedRoute>,
    ): RouteDiversity {
        if (previous.isEmpty()) return RouteDiversity(0.0, candidate.path.distance)
        val closest = previous.maxByOrNull {
            sharedRoadFraction(candidate.edgeDistances, it.edgeDistances)
        } ?: return RouteDiversity(0.0, candidate.path.distance)
        return RouteDiversity(
            sharedRoadFraction(candidate.edgeDistances, closest.edgeDistances),
            longestDistinctStretch(candidate.edgeSections, closest.edgeDistances.keys),
        )
    }

    private fun isMeaningfullyDifferent(
        candidate: CachedRoute,
        diversity: RouteDiversity,
        previous: List<CachedRoute>,
    ): Boolean {
        if (previous.isEmpty()) return true
        val requiredStretch = min(
            MAX_MEANINGFUL_STRETCH,
            maxOf(MIN_MEANINGFUL_STRETCH, candidate.path.distance * MEANINGFUL_STRETCH_FRACTION),
        )
        return diversity.longestDistinctStretch >= requiredStretch
    }

    private fun longestDistinctStretch(
        candidate: List<EdgeSection>,
        comparisonEdgeIds: Set<Int>,
    ): Double {
        var longest = 0.0
        var current = 0.0
        candidate.forEach { section ->
            if (section.edgeId !in comparisonEdgeIds) {
                current += section.distance
                longest = maxOf(longest, current)
            } else {
                current = 0.0
            }
        }
        return longest
    }

    private fun cachedRoute(path: ResponsePath): CachedRoute {
        val sections = edgeSections(path)
        val distances = mutableMapOf<Int, Double>()
        sections.forEach { section ->
            distances[section.edgeId] = distances.getOrDefault(section.edgeId, 0.0) + section.distance
        }
        return CachedRoute(path, distances, sections)
    }

    private fun edgeSections(path: ResponsePath): List<EdgeSection> {
        val points = path.points
        if (points.size() == 0) return emptyList()
        return path.pathDetails["edge_id"].orEmpty().mapNotNull { detail ->
            val edgeId = (detail.value as? Number)?.toInt() ?: return@mapNotNull null
            val first = detail.first.coerceIn(0, points.size() - 1)
            val last = min(detail.last, points.size() - 1)
            var distance = 0.0
            for (index in first until last) {
                distance += DistanceCalcEarth.DIST_EARTH.calcDist(
                    points.getLat(index),
                    points.getLon(index),
                    points.getLat(index + 1),
                    points.getLon(index + 1),
                )
            }
            val fromLat = points.getLat(first)
            val toLat = points.getLat(last)
            val forward = fromLat < toLat ||
                (fromLat == toLat && points.getLon(first) <= points.getLon(last))
            EdgeSection(edgeId, forward, distance)
        }
    }

    /**
     * Budget for an extra alternative relative to [reference] — the detent's
     * own primary route, not the complexity-0 fastest route. The caps widen
     * with the dial position up to the hard maxima.
     */
    private fun withinGlobalDetourBudget(
        candidate: ResponsePath,
        reference: ResponsePath,
        detent: Int,
    ): Boolean {
        val extraDetents = (detent - 1).coerceAtLeast(0)
        val maxTimeRatio = min(MAX_TIME_RATIO, FIRST_DETENT_TIME_RATIO + extraDetents * 0.10)
        val maxDistanceRatio = min(
            MAX_DISTANCE_RATIO,
            FIRST_DETENT_DISTANCE_RATIO + extraDetents * 0.15,
        )
        val timeRatio = if (reference.time > 0) candidate.time.toDouble() / reference.time else 1.0
        val distanceRatio = if (reference.distance > 0.0) candidate.distance / reference.distance else 1.0
        return timeRatio <= maxTimeRatio && distanceRatio <= maxDistanceRatio
    }

    /**
     * Rejects a local bubble that leaves the reference route (the detent's own
     * primary) and rejoins it after taking far more road than the reference
     * does over the corresponding section. This catches town-sized loops that
     * can hide inside an otherwise acceptable whole-route detour ratio. Only
     * bounded local sections are checked, so genuinely different long-distance
     * corridors remain governed by the global budget.
     */
    private fun hasExcessiveLocalDetour(
        candidate: List<EdgeSection>,
        reference: List<EdgeSection>,
    ): Boolean {
        if (candidate.isEmpty() || reference.isEmpty()) return false
        val referencePositions = reference.indices.groupBy {
            EdgeTraversal(reference[it].edgeId, reference[it].forward)
        }
        data class Match(val candidateIndex: Int, val referenceIndex: Int)

        // Only compare bubbles bounded by two real shared edges. Treating the
        // endpoints as anchors rejected many legitimate alternatives that leave
        // the shared corridor near the start or remain separate until the end.
        // Direction-sensitive keys still prevent doubled-back edges from being
        // treated as aligned.
        val matches = mutableListOf<Match>()
        var lastReferenceIndex = -1
        candidate.forEachIndexed { candidateIndex, section ->
            val traversal = EdgeTraversal(section.edgeId, section.forward)
            val referenceIndex = referencePositions[traversal]
                ?.firstOrNull { it > lastReferenceIndex }
                ?: return@forEachIndexed
            matches += Match(candidateIndex, referenceIndex)
            lastReferenceIndex = referenceIndex
        }
        if (matches.size < 2) return false

        for (index in 1 until matches.size) {
            val before = matches[index - 1]
            val after = matches[index]
            if (after.candidateIndex == before.candidateIndex + 1 &&
                after.referenceIndex == before.referenceIndex + 1
            ) continue

            val candidateDistance = candidate
                .subList(before.candidateIndex + 1, after.candidateIndex)
                .sumOf { it.distance }
            val referenceDistance = reference
                .subList(before.referenceIndex + 1, after.referenceIndex)
                .sumOf { it.distance }
            if (referenceDistance > MAX_LOCAL_BASELINE_DISTANCE) continue
            val extraDistance = candidateDistance - referenceDistance
            val ratio = candidateDistance / referenceDistance.coerceAtLeast(MIN_LOCAL_BASELINE_DISTANCE)
            if (extraDistance >= MIN_LOCAL_EXTRA_DISTANCE && ratio > MAX_LOCAL_DETOUR_RATIO)
                return true
        }
        return false
    }

    private fun sharedRoadFraction(a: Map<Int, Double>, b: Map<Int, Double>): Double {
        val denominator = min(a.values.sum(), b.values.sum())
        if (denominator <= 0.0) return 1.0
        val shared = a.entries.sumOf { (edgeId, distance) ->
            min(distance, b[edgeId] ?: 0.0)
        }
        return shared / denominator
    }

    private fun maxAlternativeWeight(detent: Int): Double =
        min(BASE_MAX_ALTERNATIVE_WEIGHT, 1.50 + detent * 0.25)

    private fun previousRoadPenalty(maxRoadShare: Double, detent: Int, attempt: Int): Double =
        (1.0 - maxRoadShare) *
            (BASE_PREVIOUS_ROAD_PENALTY + attempt * PREVIOUS_ROAD_PENALTY_STEP) *
            (1.0 + detent * PREVIOUS_ROAD_DETENT_STEP)

    private companion object {
        const val DEFAULT_MAX_ROUTE_SHARE = 0.70
        const val BASE_MAX_ALTERNATIVE_WEIGHT = 2.50
        const val MAX_ALTERNATIVE_WEIGHT = 4.00
        const val CANDIDATE_WEIGHT_STEP = 0.75
        const val MAX_CANDIDATE_SHARE = 0.98
        const val CANDIDATE_SHARE_STEP = 0.20
        const val MIN_PLATEAU_FACTOR = 0.10

        const val BASE_PREVIOUS_ROAD_PENALTY = 2.0
        const val PREVIOUS_ROAD_PENALTY_STEP = 6.0
        const val PREVIOUS_ROAD_DETENT_STEP = 0.10

        const val MIN_MEANINGFUL_STRETCH = 750.0
        const val MAX_MEANINGFUL_STRETCH = 3_000.0
        const val MEANINGFUL_STRETCH_FRACTION = 0.05

        const val FIRST_DETENT_TIME_RATIO = 1.50
        const val FIRST_DETENT_DISTANCE_RATIO = 1.65
        const val MAX_TIME_RATIO = 2.00
        const val MAX_DISTANCE_RATIO = 2.25

        const val MAX_LOCAL_DETOUR_RATIO = 2.25
        const val MIN_LOCAL_EXTRA_DISTANCE = 1_500.0
        const val MIN_LOCAL_BASELINE_DISTANCE = 250.0
        const val MAX_LOCAL_BASELINE_DISTANCE = 15_000.0
    }
}

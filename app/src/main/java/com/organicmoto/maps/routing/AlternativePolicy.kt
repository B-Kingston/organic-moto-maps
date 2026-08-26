package com.organicmoto.maps.routing

import com.graphhopper.ResponsePath
import com.graphhopper.util.DistanceCalcEarth
import kotlin.math.min

/** Pure GraphHopper route-diversity policy used by the Android router. */
internal object AlternativePolicy {
    data class EdgeSection(
        val edgeId: Int,
        val forward: Boolean,
        val distance: Double,
    )

    data class EdgeTraversal(
        val edgeId: Int,
        val forward: Boolean,
    )

    data class CachedRoute(
        val path: ResponsePath,
        val edgeDistances: Map<Int, Double>,
        val edgeSections: List<EdgeSection>,
    )

    data class RouteDiversity(
        val maxOverlap: Double,
        val longestDistinctStretch: Double,
    )

    data class FallbackCandidate(
        val route: CachedRoute,
        val diversity: RouteDiversity,
        val withinDetourBudget: Boolean,
        val isPrimary: Boolean,
    )

    internal const val MAX_CANDIDATE_SHARE = 0.98
    internal const val CANDIDATE_SHARE_STEP = 0.20
    internal const val MAX_ALTERNATIVE_WEIGHT = 4.00
    internal const val CANDIDATE_WEIGHT_STEP = 0.75
    internal const val MIN_PLATEAU_FACTOR = 0.10

    private const val BASE_MAX_ALTERNATIVE_WEIGHT = 2.50
    private const val BASE_PREVIOUS_ROAD_PENALTY = 2.0
    private const val PREVIOUS_ROAD_PENALTY_STEP = 6.0
    private const val PREVIOUS_ROAD_DETENT_STEP = 0.10

    private const val MIN_MEANINGFUL_STRETCH = 750.0
    private const val MAX_MEANINGFUL_STRETCH = 3_000.0
    private const val MEANINGFUL_STRETCH_FRACTION = 0.05

    private const val FIRST_DETENT_TIME_RATIO = 1.50
    private const val FIRST_DETENT_DISTANCE_RATIO = 1.65
    private const val MAX_TIME_RATIO = 2.00
    private const val MAX_DISTANCE_RATIO = 2.25

    private const val MAX_LOCAL_DETOUR_RATIO = 2.25
    private const val MIN_LOCAL_EXTRA_DISTANCE = 1_500.0
    private const val MIN_LOCAL_BASELINE_DISTANCE = 250.0
    private const val MAX_LOCAL_BASELINE_DISTANCE = 15_000.0

    fun fallbackTier(
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

    fun routeDiversity(
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

    fun isMeaningfullyDifferent(
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

    fun longestDistinctStretch(
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

    fun cachedRoute(path: ResponsePath): CachedRoute {
        val sections = edgeSections(path)
        val distances = mutableMapOf<Int, Double>()
        sections.forEach { section ->
            distances[section.edgeId] = distances.getOrDefault(section.edgeId, 0.0) + section.distance
        }
        return CachedRoute(path, distances, sections)
    }

    fun edgeSections(path: ResponsePath): List<EdgeSection> {
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
     * Checks an alternative against the primary route for its own detent.
     * The budget must not use the faster route from another detent.
     */
    fun withinGlobalDetourBudget(
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

    /** Rejects a bounded local divergence and rejoin bubble. */
    fun hasExcessiveLocalDetour(
        candidate: List<EdgeSection>,
        reference: List<EdgeSection>,
    ): Boolean {
        if (candidate.isEmpty() || reference.isEmpty()) return false
        val referencePositions = reference.indices.groupBy {
            EdgeTraversal(reference[it].edgeId, reference[it].forward)
        }
        data class Match(val candidateIndex: Int, val referenceIndex: Int)

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
            if (extraDistance >= MIN_LOCAL_EXTRA_DISTANCE && ratio > MAX_LOCAL_DETOUR_RATIO) return true
        }
        return false
    }

    fun sharedRoadFraction(a: Map<Int, Double>, b: Map<Int, Double>): Double {
        val denominator = min(a.values.sum(), b.values.sum())
        if (denominator <= 0.0) return 1.0
        val shared = a.entries.sumOf { (edgeId, distance) ->
            min(distance, b[edgeId] ?: 0.0)
        }
        return shared / denominator
    }

    fun maxAlternativeWeight(detent: Int): Double =
        min(BASE_MAX_ALTERNATIVE_WEIGHT, 1.50 + detent * 0.25)

    fun previousRoadPenalty(maxRoadShare: Double, detent: Int, attempt: Int): Double =
        (1.0 - maxRoadShare) *
            (BASE_PREVIOUS_ROAD_PENALTY + attempt * PREVIOUS_ROAD_PENALTY_STEP) *
            (1.0 + detent * PREVIOUS_ROAD_DETENT_STEP)
}

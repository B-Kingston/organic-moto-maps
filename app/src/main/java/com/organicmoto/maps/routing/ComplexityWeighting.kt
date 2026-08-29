package com.organicmoto.maps.routing

import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.EnumEncodedValue
import com.graphhopper.routing.ev.Surface
import com.graphhopper.routing.weighting.TurnCostProvider
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.FetchMode
import com.graphhopper.util.PointList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Request-hint key for the unbounded ride-complexity dial. 0 is Fastest,
 * 1 applies one full step of motorcycle and curve preference, and values above
 * 1 keep strengthening those penalties.
 */
internal const val MOTO_COMPLEXITY = "moto_complexity"

/** Previous-detent edge IDs to softly discourage when finding a distinct ride. */
internal const val MOTO_PREVIOUS_EDGES = "moto_previous_edges"

/** Added fastest-time multiples for edges in [MOTO_PREVIOUS_EDGES]. */
internal const val MOTO_PREVIOUS_EDGE_PENALTY = "moto_previous_edge_penalty"

/** Request hint that hard-blocks roads with a known unpaved surface. */
internal const val BLOCK_UNPAVED = "block_unpaved"

/**
 * Weighting for the unbounded motorcycle ride-complexity dial.
 *
 * Complexity 0 is pure fastest over the motorcycle model's accessible roads.
 * Positive values add two non-negative costs:
 * the fixed motorcycle custom-model penalty and a straight-road penalty derived
 * from each edge's actual OSM geometry. The result is
 * `w_fastest + complexity * (w_custom - w_fastest + w_curvePenalty)`, so the
 * preference can be strengthened indefinitely without producing negative edge
 * weights. Curvy edges receive less added cost than geometrically straight ones.
 * Positive detents can also add a soft, non-negative penalty to roads used by
 * earlier detents. This encourages a genuinely different corridor without
 * hard-blocking those roads when the graph has no viable alternative.
 *
 * Both components use seconds as their edge-weight unit. This is important:
 * GraphHopper's `car_average_speed` encoded value is in km/h, while its
 * `SpeedWeighting` divides metres by that value without the km/h-to-m/s factor.
 * Using `SpeedWeighting` here therefore made both route weights and reported
 * times 3.6 times too small (the reported two-minute journey that motivated
 * this implementation). [FastestWeighting] performs the conversion explicitly.
 *
 * [calcMinWeightPerDistance] uses Fastest's lower bound. Every added term is
 * non-negative, so that remains admissible at every complexity.
 *
 * Access is a hard constraint at every dial position and is enforced HERE,
 * not by a graph filter: the flexible (non-CH) solver applies no access
 * filter — its directed edge filter only checks `!inSubnetwork` and
 * `Double.isFinite(weighting.calcEdgeWeight(...))` — so the weighting itself
 * must return [Double.POSITIVE_INFINITY] for edges where `car_access` is
 * false AND for edges the custom model prices at priority 0 (high track
 * grades, private roads). Reading the EVs on virtual edges is safe: the
 * QueryGraph forwards EV reads to the original edge.
 *
 * [calcEdgeMillis] always reports travel time from the fastest component.
 * Fastest and custom use the same motorcycle-model speed mapping; only their
 * route-selection weights differ, so ETA stays a physical travel time rather
 * than changing with a subjective route-preference blend.
 */
class ComplexityWeighting(
    private val custom: Weighting,
    private val fastest: Weighting,
    private val complexity: Double,
    private val accessEnc: BooleanEncodedValue,
    private val surfaceEnc: EnumEncodedValue<Surface>,
    private val blockUnpaved: Boolean,
    private val previousEdgeIds: Set<Int>,
    private val previousEdgePenalty: Double,
) : Weighting {

    override fun calcMinWeightPerDistance(): Double = fastest.calcMinWeightPerDistance()

    override fun calcEdgeWeight(edgeState: EdgeIteratorState, reverse: Boolean): Double {
        // Hard access block: a road closed to motorcycles stays closed no
        // matter where the dial sits (see class KDoc — the flexible solver
        // has no access filter, so this is the enforcement point).
        val accessible = if (reverse) edgeState.getReverse(accessEnc) else edgeState.get(accessEnc)
        if (!accessible) return Double.POSITIVE_INFINITY
        if (blockUnpaved) {
            val surface = if (reverse) edgeState.getReverse(surfaceEnc) else edgeState.get(surfaceEnc)
            if (surface in UNPAVED_SURFACES) return Double.POSITIVE_INFINITY
        }
        val wFastest = fastest.calcEdgeWeight(edgeState, reverse)
        if (!wFastest.isFinite()) return Double.POSITIVE_INFINITY
        val reusePenalty = if (edgeState.edge in previousEdgeIds) {
            wFastest * previousEdgePenalty
        } else {
            0.0
        }
        val wCustom = custom.calcEdgeWeight(edgeState, reverse)
        // The custom model's priority-0 edges (no car access, high track
        // grades, private roads) are hard blocks at EVERY dial position; the
        // dial only scales the soft preference above that floor. Skipping
        // this check at Fastest let detent 0 route over roads every positive
        // detent forbids.
        if (!wCustom.isFinite()) return Double.POSITIVE_INFINITY
        // Avoid `0 * Infinity`: the soft custom preference joins the blend
        // only at positive complexity.
        if (complexity == 0.0) return wFastest + reusePenalty
        val customPenalty = (wCustom - wFastest).coerceAtLeast(0.0)
        val curvePenalty = wFastest * STRAIGHT_ROAD_PENALTY * curvePenaltyFactor(edgeState)
        return wFastest + complexity * (customPenalty + curvePenalty) + reusePenalty
    }

    override fun calcEdgeMillis(edgeState: EdgeIteratorState, reverse: Boolean): Long =
        fastest.calcEdgeMillis(edgeState, reverse)

    override fun calcTurnWeight(inEdge: Int, viaNode: Int, outEdge: Int): Double {
        val fastestWeight = fastest.calcTurnWeight(inEdge, viaNode, outEdge)
        if (complexity == 0.0 || !fastestWeight.isFinite()) return fastestWeight
        val customWeight = custom.calcTurnWeight(inEdge, viaNode, outEdge)
        if (!customWeight.isFinite()) return customWeight
        return fastestWeight + complexity * (customWeight - fastestWeight)
    }

    override fun calcTurnMillis(inEdge: Int, viaNode: Int, outEdge: Int): Long =
        fastest.calcTurnMillis(inEdge, viaNode, outEdge)

    override fun hasTurnCosts(): Boolean = fastest.hasTurnCosts() || custom.hasTurnCosts()

    /**
     * The radius score adds corner tightness without discarding the existing
     * sustained-bend signal that keeps broad, curved roads competitive.
     */
    private fun curvePenaltyFactor(edgeState: EdgeIteratorState): Double {
        val exposure = combinedCurveExposure(
            edgeState.fetchWayGeometry(FetchMode.ALL),
            edgeState.distance,
        )
        return 1.0 - exposure
    }

    // Must match Weighting.isValidName's regex [|_a-z]+.
    override fun getName(): String = "moto_complexity"

    private companion object {
        /** Added multiplier for a perfectly straight edge at complexity 1. */
        const val STRAIGHT_ROAD_PENALTY = 1.5

        /**
         * Explicitly unpaved GraphHopper surfaces. MISSING and OTHER stay
         * routable because they do not prove a road is unpaved; cobblestone,
         * paving stones, and wood are surfaced roads rather than unpaved ones.
         */
        val UNPAVED_SURFACES = setOf(
            Surface.UNPAVED,
            Surface.COMPACTED,
            Surface.FINE_GRAVEL,
            Surface.GRAVEL,
            Surface.GROUND,
            Surface.DIRT,
            Surface.GRASS,
            Surface.SAND,
        )
    }
}

/**
 * Returns a length-weighted curve exposure in the range 0..1.
 *
 * Each three-point window defines a circumcircle. Its radius classifies the
 * two adjacent segments. A segment shared by two windows uses the tighter
 * radius, as a tight turn commonly meets a longer straight segment. Curves
 * below 30 m radius get full exposure. Curves from 30 m through 175 m get
 * progressively smaller fixed weights. Straighter geometry gets zero.
 *
 * Very short adjacent segments do not define a curve. This removes isolated
 * OSM digitizing jiggles before they can outweigh sustained road geometry.
 */
internal fun radiusCurveExposure(points: PointList): Double =
    curveExposure(points, Double.NaN)

private fun combinedCurveExposure(points: PointList, edgeDistanceMeters: Double): Double =
    curveExposure(points, edgeDistanceMeters)

private fun curveExposure(points: PointList, edgeDistanceMeters: Double): Double {
    if (points.size() < 3) return 0.0

    val longitudeScale = cos(
        Math.toRadians((points.getLat(0) + points.getLat(points.size() - 1)) / 2.0),
    )

    fun projectedX(first: Int, second: Int): Double {
        var deltaLongitude = points.getLon(second) - points.getLon(first)
        if (deltaLongitude > 180.0) deltaLongitude -= 360.0
        if (deltaLongitude < -180.0) deltaLongitude += 360.0
        return deltaLongitude * longitudeScale
    }

    fun projectedY(first: Int, second: Int): Double =
        points.getLat(second) - points.getLat(first)

    fun distance(first: Int, second: Int): Double =
        hypot(projectedX(first, second), projectedY(first, second)) * METERS_PER_DEGREE

    fun triangleWeight(start: Int, first: Double, second: Double): Double {
        if (first < MIN_CURVE_SEGMENT_METERS || second < MIN_CURVE_SEGMENT_METERS) return 0.0
        val chord = distance(start, start + 2)
        if (chord <= 0.0) return 0.0
        val denominatorSquared =
            (first + second + chord) *
                (second + chord - first) *
                (chord + first - second) *
                (first + second - chord)
        if (!denominatorSquared.isFinite() || denominatorSquared <= MIN_AREA_TERM) return 0.0
        val radius = first * second * chord / sqrt(denominatorSquared)
        return when {
            radius < 30.0 -> 2.0
            radius < 60.0 -> 1.6
            radius < 100.0 -> 1.3
            radius < 175.0 -> 1.0
            else -> 0.0
        }
    }

    val includeHeading = edgeDistanceMeters.isFinite()
    var previousHeading = Double.NaN
    var totalTurn = 0.0
    fun addHeading(segmentStart: Int) {
        if (!includeHeading) return
        val x = projectedX(segmentStart, segmentStart + 1)
        val y = projectedY(segmentStart, segmentStart + 1)
        if (abs(x) + abs(y) < 1e-12) return
        val heading = atan2(y, x)
        if (previousHeading.isFinite()) {
            var turn = abs(heading - previousHeading)
            if (turn > PI) turn = 2.0 * PI - turn
            totalTurn += turn
        }
        previousHeading = heading
    }

    val firstLength = distance(0, 1)
    var secondLength = distance(1, 2)
    var previousWeight = triangleWeight(0, firstLength, secondLength)
    var totalLength = firstLength + secondLength
    var weightedCurvature = firstLength * previousWeight
    addHeading(0)
    addHeading(1)

    for (triangleStart in 1 until points.size() - 2) {
        val nextLength = distance(triangleStart + 1, triangleStart + 2)
        val nextWeight = triangleWeight(triangleStart, secondLength, nextLength)
        weightedCurvature += secondLength * maxOf(previousWeight, nextWeight)
        totalLength += nextLength
        secondLength = nextLength
        previousWeight = nextWeight
        addHeading(triangleStart + 1)
    }
    weightedCurvature += secondLength * previousWeight

    val radiusExposure = if (totalLength <= 0.0) {
        0.0
    } else {
        (weightedCurvature / (totalLength * MAX_CURVE_WEIGHT)).coerceIn(0.0, 1.0)
    }
    if (!includeHeading) return radiusExposure
    val distanceKm = max(edgeDistanceMeters / 1_000.0, 0.05)
    val headingExposure = (totalTurn / distanceKm / PI).coerceIn(0.0, 1.0)
    return maxOf(radiusExposure, headingExposure)
}

private const val MIN_CURVE_SEGMENT_METERS = 5.0
private const val MIN_AREA_TERM = 1e-6
private const val MAX_CURVE_WEIGHT = 2.0
private const val METERS_PER_DEGREE = 111_195.0

/**
 * Travel-time weighting used by the Fastest stop of the complexity dial.
 *
 * [speedForEdge] returns the motorcycle custom model's effective speed in
 * kilometres per hour. The model's speed limits remain part of ETA (including
 * its 0.9× average-speed adjustment and rough-surface cap), while motorcycle
 * road preferences live only in the custom component. Consequently Fastest
 * means "minimise expected riding time", not "interpret km/h as m/s".
 */
internal class FastestWeighting(
    private val speedForEdge: (EdgeIteratorState, Boolean) -> Double,
    private val maxSpeedKmh: Double,
    private val turnCostProvider: TurnCostProvider,
    private val accessEnc: BooleanEncodedValue,
) : Weighting {

    override fun calcMinWeightPerDistance(): Double = KMH_TO_SECONDS_PER_METRE / maxSpeedKmh

    override fun calcEdgeWeight(edgeState: EdgeIteratorState, reverse: Boolean): Double {
        val accessible = if (reverse) edgeState.getReverse(accessEnc) else edgeState.get(accessEnc)
        if (!accessible) return Double.POSITIVE_INFINITY
        val speedKmh = speedForEdge(edgeState, reverse)
        if (speedKmh <= 0.0) return Double.POSITIVE_INFINITY
        return edgeState.distance * KMH_TO_SECONDS_PER_METRE / speedKmh
    }

    override fun calcEdgeMillis(edgeState: EdgeIteratorState, reverse: Boolean): Long {
        val seconds = calcEdgeWeight(edgeState, reverse)
        return if (seconds.isFinite()) (seconds * 1_000.0).roundToLong() else Long.MAX_VALUE
    }

    override fun calcTurnWeight(inEdge: Int, viaNode: Int, outEdge: Int): Double =
        turnCostProvider.calcTurnWeight(inEdge, viaNode, outEdge)

    override fun calcTurnMillis(inEdge: Int, viaNode: Int, outEdge: Int): Long =
        turnCostProvider.calcTurnMillis(inEdge, viaNode, outEdge)

    override fun hasTurnCosts(): Boolean = turnCostProvider !== TurnCostProvider.NO_TURN_COST_PROVIDER

    override fun getName(): String = "fastest"

    private companion object {
        /** Converts distance(m) / speed(km/h) to seconds. */
        const val KMH_TO_SECONDS_PER_METRE = 3.6
    }
}

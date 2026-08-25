package com.organicmoto.maps.routing

import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.EnumEncodedValue
import com.graphhopper.routing.ev.Surface
import com.graphhopper.routing.weighting.TurnCostProvider
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.FetchMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Request-hint key for the unbounded ride-complexity dial. 0 is Fastest,
 * 1 applies one full step of motorcycle and curve preference, and values above
 * 1 keep strengthening those penalties.
 */
internal const val MOTO_COMPLEXITY = "moto_complexity"

/** Request hint that hard-blocks roads with a known unpaved surface. */
internal const val BLOCK_UNPAVED = "block_unpaved"

/**
 * Weighting for the unbounded motorcycle ride-complexity dial.
 *
 * Complexity 0 is pure fastest. Positive values add two non-negative costs:
 * the fixed motorcycle custom-model penalty and a straight-road penalty derived
 * from each edge's actual OSM geometry. The result is
 * `w_fastest + complexity * (w_custom - w_fastest + w_curvePenalty)`, so the
 * preference can be strengthened indefinitely without producing negative edge
 * weights. Curvy edges receive less added cost than geometrically straight ones.
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
 * false. Reading the EV on virtual edges is safe: the QueryGraph forwards EV
 * reads to the original edge.
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
        // Avoid `0 * Infinity` at Fastest. At every positive complexity an
        // infinite custom weight must stay blocked.
        if (complexity == 0.0) return wFastest
        if (!wFastest.isFinite()) return Double.POSITIVE_INFINITY
        val wCustom = custom.calcEdgeWeight(edgeState, reverse)
        if (!wCustom.isFinite()) return Double.POSITIVE_INFINITY
        val customPenalty = (wCustom - wFastest).coerceAtLeast(0.0)
        val curvePenalty = wFastest * STRAIGHT_ROAD_PENALTY * straightness(edgeState)
        return wFastest + complexity * (customPenalty + curvePenalty)
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
     * 1 for a straight edge, approaching 0 as its geometry accumulates bends.
     * About 180 degrees of heading change per kilometre counts as fully curvy.
     */
    private fun straightness(edgeState: EdgeIteratorState): Double {
        val points = edgeState.fetchWayGeometry(FetchMode.ALL)
        if (points.size() < 3) return 1.0
        var previousHeading: Double? = null
        var totalTurn = 0.0
        for (index in 1 until points.size()) {
            val lat1 = Math.toRadians(points.getLat(index - 1))
            val lat2 = Math.toRadians(points.getLat(index))
            val deltaLon = Math.toRadians(points.getLon(index) - points.getLon(index - 1))
            val x = deltaLon * cos((lat1 + lat2) / 2.0)
            val y = lat2 - lat1
            if (abs(x) + abs(y) < 1e-12) continue
            val heading = atan2(y, x)
            previousHeading?.let {
                var turn = abs(heading - it)
                if (turn > PI) turn = 2.0 * PI - turn
                totalTurn += turn
            }
            previousHeading = heading
        }
        val distanceKm = max(edgeState.distance / 1_000.0, 0.05)
        val curveScore = (totalTurn / distanceKm / PI).coerceIn(0.0, 1.0)
        return 1.0 - curveScore
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

package com.organicmoto.maps.routing

import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.weighting.TurnCostProvider
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.util.EdgeIteratorState
import kotlin.math.roundToLong

/**
 * Request-hint key for the motorcycle route blend slider, shared by
 * [GraphHopperRouter] (which sets it on the request) and
 * [MotorcycleWeightingFactory] (which reads it). Value is the blend position
 * `t` in [0,1], default 1.0.
 */
internal const val MOTO_BLEND = "moto_blend"

/**
 * Weighting that linearly blends two edge weightings for the motorcycle route
 * "blend" slider.
 *
 * The per-request hint [MOTO_BLEND] selects the position `t` in [0,1]:
 *  - `t = 1.0` (default): the pure motorcycle [com.graphhopper.routing.weighting.custom.CustomWeighting] —
 *    the model's priorities and speed caps apply, routed via CH (which bakes
 *    exactly this weighting into the graph at import time).
 *  - `t = 0.0`: pure fastest routing using the motorcycle model's travel
 *    speeds, but none of its road-preference priority or distance influence.
 *  - `0 < t < 1`: an edge-weight blend `(1-t) * w_fastest + t * w_custom`.
 *
 * Both components use seconds as their edge-weight unit. This is important:
 * GraphHopper's `car_average_speed` encoded value is in km/h, while its
 * `SpeedWeighting` divides metres by that value without the km/h-to-m/s factor.
 * Using `SpeedWeighting` here therefore made both route weights and reported
 * times 3.6 times too small (the reported two-minute journey that motivated
 * this implementation). [FastestWeighting] performs the conversion explicitly.
 *
 * [calcMinWeightPerDistance] is the same convex combination of the two
 * components' minima. Each component's min weight per meter is a per-meter
 * lower bound on all of its own edge weights, so the blended min is a lower
 * bound on the blended edge weights — the A* heuristic stays admissible.
 *
 * Access is a hard constraint at every slider position and is enforced HERE,
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
class BlendedWeighting(
    private val custom: Weighting,
    private val fastest: Weighting,
    private val t: Double,
    private val accessEnc: BooleanEncodedValue,
) : Weighting {

    override fun calcMinWeightPerDistance(): Double =
        (1.0 - t) * fastest.calcMinWeightPerDistance() + t * custom.calcMinWeightPerDistance()

    override fun calcEdgeWeight(edgeState: EdgeIteratorState, reverse: Boolean): Double {
        // Hard access block: a road closed to motorcycles stays closed no
        // matter where the slider sits (see class KDoc — the flexible solver
        // has no access filter, so this is the enforcement point).
        val accessible = if (reverse) edgeState.getReverse(accessEnc) else edgeState.get(accessEnc)
        if (!accessible) return Double.POSITIVE_INFINITY
        val wFastest = fastest.calcEdgeWeight(edgeState, reverse)
        // Short-circuit at t == 0.0: custom.calcEdgeWeight can be infinite on
        // model-blocked edges and `0 * Infinity` is NaN. For t > 0 an infinite
        // wCustom propagates naturally, which is correct: model-blocked roads
        // stay blocked until exactly t == 0.0.
        if (t == 0.0) return wFastest
        val wCustom = custom.calcEdgeWeight(edgeState, reverse)
        return (1.0 - t) * wFastest + t * wCustom
    }

    override fun calcEdgeMillis(edgeState: EdgeIteratorState, reverse: Boolean): Long =
        fastest.calcEdgeMillis(edgeState, reverse)

    override fun calcTurnWeight(inEdge: Int, viaNode: Int, outEdge: Int): Double =
        (1.0 - t) * fastest.calcTurnWeight(inEdge, viaNode, outEdge) +
            t * custom.calcTurnWeight(inEdge, viaNode, outEdge)

    override fun calcTurnMillis(inEdge: Int, viaNode: Int, outEdge: Int): Long =
        ((1.0 - t) * fastest.calcTurnMillis(inEdge, viaNode, outEdge) +
            t * custom.calcTurnMillis(inEdge, viaNode, outEdge)).roundToLong()

    override fun hasTurnCosts(): Boolean = fastest.hasTurnCosts() || custom.hasTurnCosts()

    // Must match Weighting.isValidName's regex [|_a-z]+.
    override fun getName(): String = "moto_blend"
}

/**
 * Travel-time weighting used by the Fastest end of the route-style slider.
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

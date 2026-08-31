package com.organicmoto.maps.routing

import com.graphhopper.GHRequest
import com.graphhopper.util.Parameters
import com.graphhopper.util.shapes.GHPoint

/**
 * Builds the request used for one route-search attempt.
 *
 * Keep the profile, path details, and hint order aligned with the production
 * router. GraphHopper reads these hints when it selects the weighting and
 * alternative algorithm.
 */
internal fun buildGhRequest(
    from: GHPoint,
    to: GHPoint,
    detent: Int,
    blockUnpaved: Boolean,
    maxRoadShare: Double,
    attempt: Int,
    previousEdgeIds: Set<Int>,
    viaPoints: List<GHPoint> = emptyList(),
): GHRequest {
    val request = GHRequest(listOf(from) + viaPoints + to)
        .setProfile(MOTORCYCLE_PROFILE)
        .setPathDetails(listOf("edge_id"))
    request.putHint(Parameters.CH.DISABLE, true)
    request.putHint(MOTO_COMPLEXITY, detent.toDouble())
    request.putHint(BLOCK_UNPAVED, blockUnpaved)
    if (detent > 0) {
        request.putHint(
            MOTO_PREVIOUS_EDGES,
            previousEdgeIds,
        )
        request.putHint(
            MOTO_PREVIOUS_EDGE_PENALTY,
            AlternativePolicy.previousRoadPenalty(maxRoadShare, detent, attempt),
        )
        // GraphHopper's alternative-route algorithm only supports two-point
        // requests. Imported GPX rides use the normal flexible algorithm so
        // every via point becomes a routed leg with turn instructions.
        if (viaPoints.isNotEmpty()) return request
        request.setAlgorithm(Parameters.Algorithms.ALT_ROUTE)
        request.putHint(
            Parameters.Algorithms.AltRoute.MAX_PATHS,
            (8 + detent * 3 + attempt * 6).coerceAtMost(40),
        )
        request.putHint(
            Parameters.Algorithms.AltRoute.MAX_SHARE,
            minOf(AlternativePolicy.MAX_CANDIDATE_SHARE, maxRoadShare + attempt * AlternativePolicy.CANDIDATE_SHARE_STEP),
        )
        request.putHint(
            Parameters.Algorithms.AltRoute.MAX_WEIGHT,
            minOf(
                AlternativePolicy.MAX_ALTERNATIVE_WEIGHT,
                AlternativePolicy.maxAlternativeWeight(detent) + attempt * AlternativePolicy.CANDIDATE_WEIGHT_STEP,
            ),
        )
        request.putHint(
            "alternative_route.max_exploration_factor",
            1.2 + detent * 0.15 + attempt * 0.75,
        )
        request.putHint("alternative_route.min_plateau_factor", AlternativePolicy.MIN_PLATEAU_FACTOR)
    }
    return request
}

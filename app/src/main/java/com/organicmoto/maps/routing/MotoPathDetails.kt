package com.organicmoto.maps.routing

import com.graphhopper.routing.Path
import com.graphhopper.routing.ev.EncodedValueLookup
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.storage.Graph
import com.graphhopper.util.details.KVStringDetails
import com.graphhopper.util.details.PathDetailsBuilder
import com.graphhopper.util.details.PathDetailsBuilderFactory

/**
 * Edge KV key written by `tools/gh/MotoGraphImport.java`: per-direction lane
 * count and turn arrows of the road (format documented there and parsed by
 * [com.organicmoto.maps.routing.navigation.LaneGuidance.parse]).
 */
const val MOTO_LANES_DETAIL = "moto_lanes"

/**
 * GraphHopper's factory only knows its built-in KV keys, so this adds
 * [MOTO_LANES_DETAIL]. [KVStringDetails] reads the edge in travel direction,
 * which is what makes the forward/backward lane values come out right.
 */
internal class MotoPathDetailsBuilderFactory : PathDetailsBuilderFactory() {
    override fun createPathDetailsBuilders(
        requestedPathDetails: List<String>,
        path: Path,
        evl: EncodedValueLookup,
        weighting: Weighting,
        graph: Graph,
    ): List<PathDetailsBuilder> {
        val builtIn = requestedPathDetails.filter { it != MOTO_LANES_DETAIL }
        val builders = super.createPathDetailsBuilders(builtIn, path, evl, weighting, graph).toMutableList()
        if (MOTO_LANES_DETAIL in requestedPathDetails) builders += KVStringDetails(MOTO_LANES_DETAIL)
        return builders
    }
}

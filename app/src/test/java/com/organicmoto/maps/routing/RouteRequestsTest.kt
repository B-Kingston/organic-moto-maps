package com.organicmoto.maps.routing

import com.graphhopper.util.Parameters
import com.graphhopper.util.shapes.GHPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteRequestsTest {

    @Test
    fun fastestRequestUsesOnlyTheBaseRoutingHints() {
        val request = buildGhRequest(
            from = GHPoint(-27.0, 153.0),
            to = GHPoint(-28.0, 152.0),
            detent = 0,
            blockUnpaved = false,
            maxRoadShare = 0.70,
            attempt = 0,
            previousEdgeIds = setOf(3, 7),
        )
        assertEquals(MOTORCYCLE_PROFILE, request.profile)
        assertEquals(listOf("edge_id", "road_class", MOTO_LANES_DETAIL), request.pathDetails)
        assertTrue(request.hints.getBool(Parameters.CH.DISABLE, false))
        assertEquals(0.0, request.hints.getDouble(MOTO_COMPLEXITY, -1.0), 0.0)
        assertFalse(request.hints.has(Parameters.Algorithms.ALT_ROUTE))
        assertFalse(request.hints.has(MOTO_PREVIOUS_EDGES))
        assertFalse(request.hints.has(MOTO_PREVIOUS_EDGE_PENALTY))
    }

    @Test
    fun positiveRequestUsesDetentAlternativeExplorationHints() {
        val share = 0.55
        val request = buildGhRequest(
            from = GHPoint(-27.0, 153.0),
            to = GHPoint(-28.0, 152.0),
            detent = 2,
            blockUnpaved = true,
            maxRoadShare = share,
            attempt = 1,
            previousEdgeIds = setOf(3, 7),
        )
        assertEquals(MOTORCYCLE_PROFILE, request.profile)
        assertEquals(listOf("edge_id", "road_class", MOTO_LANES_DETAIL), request.pathDetails)
        assertTrue(request.hints.getBool(Parameters.CH.DISABLE, false))
        assertEquals(2.0, request.hints.getDouble(MOTO_COMPLEXITY, -1.0), 0.0)
        assertEquals(Parameters.Algorithms.ALT_ROUTE, request.algorithm)
        assertEquals(20, request.hints.getInt(Parameters.Algorithms.AltRoute.MAX_PATHS, -1))
        assertEquals(
            minOf(0.98, share + 0.20),
            request.hints.getDouble(Parameters.Algorithms.AltRoute.MAX_SHARE, -1.0),
            0.0,
        )
        assertEquals(
            minOf(4.00, AlternativePolicy.maxAlternativeWeight(2) + 0.75),
            request.hints.getDouble(Parameters.Algorithms.AltRoute.MAX_WEIGHT, -1.0),
            0.0,
        )
        assertEquals(
            1.2 + 2 * 0.15 + 1 * 0.75,
            request.hints.getDouble("alternative_route.max_exploration_factor", -1.0),
            0.0,
        )
        assertEquals(
            0.10,
            request.hints.getDouble("alternative_route.min_plateau_factor", -1.0),
            0.0,
        )
        assertEquals(
            AlternativePolicy.previousRoadPenalty(share, 2, 1),
            request.hints.getDouble(MOTO_PREVIOUS_EDGE_PENALTY, -1.0),
            0.0,
        )
        assertEquals(setOf(3, 7), request.hints.getObject(MOTO_PREVIOUS_EDGES, emptySet<Int>()))
        assertTrue(request.hints.getBool(BLOCK_UNPAVED, false))
    }

    @Test
    fun viaPointRequestRoutesEveryLegWithoutAlternativeAlgorithm() {
        val via = GHPoint(-27.5, 152.5)
        val request = buildGhRequest(
            from = GHPoint(-27.0, 153.0),
            to = GHPoint(-28.0, 152.0),
            detent = 2,
            blockUnpaved = false,
            maxRoadShare = 0.70,
            attempt = 0,
            previousEdgeIds = emptySet(),
            viaPoints = listOf(via),
        )

        assertEquals(3, request.points.size)
        assertEquals(via, request.points[1])
        assertEquals(2.0, request.hints.getDouble(MOTO_COMPLEXITY, -1.0), 0.0)
        assertNotEquals(Parameters.Algorithms.ALT_ROUTE, request.algorithm)
    }
}

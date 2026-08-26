package com.organicmoto.maps.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimilarityPropertyTest {

    @Test
    fun identicalRoutesHaveZeroDistance() {
        val route = corridor(0.0)
        assertEquals(0.0, RouteSimilarity.meanDistanceMeters(route, route), 0.0)
    }

    @Test
    fun shiftedCorridorsBecomeMonotonicallyMoreDifferent() {
        val reference = corridor(0.0)
        val scores = listOf(0.1, 0.5, 1.0, 5.0).map { kilometres ->
            RouteSimilarity.meanDistanceMeters(reference, corridor(kilometres / 111.195))
        }
        scores.zipWithNext().forEach { (smaller, larger) ->
            assertTrue("scores were not monotonic: $scores", larger >= smaller)
        }
    }

    @Test
    fun reversedGeometryIsDifferentBecauseDirectionMatters() {
        val route = corridor(0.0)
        assertTrue(RouteSimilarity.meanDistanceMeters(route, route.asReversed()) > 0.0)
    }

    @Test
    fun emptyAndSinglePointInputsHaveDefinedResults() {
        val route = corridor(0.0)
        assertEquals(Double.MAX_VALUE, RouteSimilarity.meanDistanceMeters(emptyList(), route), 0.0)
        assertEquals(Double.MAX_VALUE, RouteSimilarity.meanDistanceMeters(route, emptyList()), 0.0)
        assertEquals(null, RouteSimilarity.bestMatchIndex(emptyList(), listOf(route)))
        assertTrue(RouteSimilarity.meanDistanceMeters(route, listOf(route.first())).isFinite())
    }

    @Test
    fun finiteCandidatesBeatNaNGeometry() {
        val route = corridor(0.0)
        val invalid = listOf(GeoPoint(Double.NaN, 153.0), GeoPoint(-27.0, 153.1))
        assertEquals(1, RouteSimilarity.bestMatchIndex(route, listOf(invalid, route)))
    }

    private fun corridor(latOffset: Double): List<GeoPoint> =
        (0..20).map { index ->
            GeoPoint(-27.0 + latOffset + index * 0.01, 153.0 + index * 0.01)
        }
}

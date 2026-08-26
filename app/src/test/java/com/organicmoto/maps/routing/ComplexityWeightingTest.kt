package com.organicmoto.maps.routing

import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.EnumEncodedValue
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue
import com.graphhopper.routing.ev.Surface
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.routing.weighting.TurnCostProvider
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.storage.BaseGraph
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.FetchMode
import com.graphhopper.util.PointList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplexityWeightingTest {

    @Test
    fun accessIsAHardConstraintAtEveryDialPositionAndDirection() {
        val fixture = fixture()
        val edge = fixture.edge(forwardAccess = true, reverseAccess = false)
        val fastest = FakeWeighting(edgeWeight = 4.0, millis = 4000L)
        val custom = FakeWeighting(edgeWeight = 6.0)
        val zero = weighting(fixture, custom, fastest, complexity = 0.0)
        val positive = weighting(fixture, custom, fastest, complexity = 2.0)
        assertTrue(zero.calcEdgeWeight(edge, false).isFinite())
        assertTrue(positive.calcEdgeWeight(edge, false).isFinite())
        assertEquals(Double.POSITIVE_INFINITY, zero.calcEdgeWeight(edge, true), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, positive.calcEdgeWeight(edge, true), 0.0)

        val closed = fixture.edge(forwardAccess = false, reverseAccess = false)
        assertEquals(Double.POSITIVE_INFINITY, zero.calcEdgeWeight(closed, false), 0.0)
        assertEquals(Double.POSITIVE_INFINITY, positive.calcEdgeWeight(closed, false), 0.0)
    }

    @Test
    fun blockUnpavedRejectsOnlyKnownUnpavedSurfaces() {
        val fixture = fixture()
        val fastest = FakeWeighting(edgeWeight = 4.0)
        val custom = FakeWeighting(edgeWeight = 4.0)
        val weighting = weighting(fixture, custom, fastest, complexity = 0.0, blockUnpaved = true)
        listOf(
            Surface.UNPAVED,
            Surface.COMPACTED,
            Surface.FINE_GRAVEL,
            Surface.GRAVEL,
            Surface.GROUND,
            Surface.DIRT,
            Surface.GRASS,
            Surface.SAND,
        ).forEach { surface ->
            val edge = fixture.edge(surface = surface)
            assertEquals(surface.toString(), Double.POSITIVE_INFINITY, weighting.calcEdgeWeight(edge, false), 0.0)
        }
        listOf(Surface.COBBLESTONE, Surface.PAVING_STONES).forEach { surface ->
            val edge = fixture.edge(surface = surface)
            assertTrue(weighting.calcEdgeWeight(edge, false).isFinite())
        }
    }

    @Test
    fun zeroComplexityIgnoresCustomDifferenceButKeepsReusePenalty() {
        val fixture = fixture()
        val edge = fixture.edge()
        val weighting = weighting(
            fixture,
            custom = FakeWeighting(edgeWeight = 99.0),
            fastest = FakeWeighting(edgeWeight = 4.0),
            complexity = 0.0,
            previousEdgeIds = setOf(edge.edge),
            previousEdgePenalty = 0.25,
        )
        assertEquals(5.0, weighting.calcEdgeWeight(edge, false), 0.0)
    }

    @Test
    fun positiveComplexityUsesNonNegativeCustomCurveAndReuseTerms() {
        val fixture = fixture()
        val edge = fixture.edge()
        val weighting = weighting(
            fixture,
            custom = FakeWeighting(edgeWeight = 7.0),
            fastest = FakeWeighting(edgeWeight = 4.0),
            complexity = 2.0,
            previousEdgeIds = setOf(edge.edge),
            previousEdgePenalty = 0.25,
        )
        // Straight geometry: curve penalty is 4 * 1.5.
        assertEquals(23.0, weighting.calcEdgeWeight(edge, false), 1e-9)
    }

    @Test
    fun straightAndFullyCurvedGeometryProduceExpectedCurveTerms() {
        val fixture = fixture()
        val fastest = FakeWeighting(edgeWeight = 4.0, millis = 4321L, minWeight = 0.75)
        val custom = FakeWeighting(edgeWeight = 4.0)
        val straight = fixture.edge(
            distance = 1_000.0,
            geometry = listOf(0.0 to 0.5),
        )
        val curved = fixture.edge(
            distance = 1_000.0,
            geometry = listOf(0.0 to 0.0045, 0.0045 to 0.0045, 0.0045 to 0.0),
        )
        val weighting = weighting(fixture, custom, fastest, complexity = 1.0)
        assertEquals(10.0, weighting.calcEdgeWeight(straight, false), 1e-9)
        assertEquals(4.0, weighting.calcEdgeWeight(curved, false), 1e-6)
        assertEquals(4321L, weighting.calcEdgeMillis(straight, false))
        assertEquals(0.75, weighting.calcMinWeightPerDistance(), 0.0)
    }

    @Test
    fun reversingGeometryKeepsTheSameTurnMagnitude() {
        val fixture = fixture()
        val original = fixture.edge(
            distance = 1_000.0,
            geometry = listOf(0.0 to 0.0045, 0.0045 to 0.0045, 0.0045 to 0.0),
        )
        val reversedGeometry = fixture.edge(
            distance = 1_000.0,
            geometry = listOf(0.0045 to 0.0, 0.0045 to 0.0045, 0.0 to 0.0045),
        )
        val weighting = weighting(
            fixture,
            custom = FakeWeighting(edgeWeight = 4.0),
            fastest = FakeWeighting(edgeWeight = 4.0),
            complexity = 3.0,
        )
        assertEquals(
            weighting.calcEdgeWeight(original, false),
            weighting.calcEdgeWeight(reversedGeometry, false),
            1e-6,
        )

        val duplicate = fixture.edge(
            distance = 1_000.0,
            geometry = listOf(0.0 to 0.5, 0.0 to 0.5, 0.0 to 0.5),
        )
        assertTrue(weighting.calcEdgeWeight(duplicate, false).isFinite())
    }

    private fun weighting(
        fixture: Fixture,
        custom: Weighting,
        fastest: Weighting,
        complexity: Double,
        blockUnpaved: Boolean = false,
        previousEdgeIds: Set<Int> = emptySet(),
        previousEdgePenalty: Double = 0.0,
    ) = ComplexityWeighting(
        custom,
        fastest,
        complexity,
        fixture.access,
        fixture.surface,
        blockUnpaved,
        previousEdgeIds,
        previousEdgePenalty,
    )

    private fun fixture(): Fixture {
        val access = SimpleBooleanEncodedValue("car_access", true)
        val surface = EnumEncodedValue("surface", Surface::class.java, true)
        val encodingManager = EncodingManager.start().add(access).add(surface).build()
        val graph = BaseGraph.Builder(encodingManager).create()
        graph.nodeAccess.setNode(0, 0.0, 0.0)
        graph.nodeAccess.setNode(1, 0.0, 1.0)
        graph.nodeAccess.setNode(2, 1.0, 1.0)
        graph.nodeAccess.setNode(3, 1.0, 0.0)
        return Fixture(graph, access, surface)
    }

    private data class Fixture(
        val graph: BaseGraph,
        val access: BooleanEncodedValue,
        val surface: EnumEncodedValue<Surface>,
    ) {
        fun edge(
            forwardAccess: Boolean = true,
            reverseAccess: Boolean = forwardAccess,
            surface: Surface = Surface.ASPHALT,
            distance: Double = 1_000.0,
            geometry: List<Pair<Double, Double>> = listOf(0.0 to 0.5),
        ): EdgeIteratorState {
            val edge = graph.edge(0, 1)
                .setDistance(distance)
                .setWayGeometry(PointList().apply { geometry.forEach { (lat, lon) -> add(lat, lon) } })
                .set(access, forwardAccess)
                .setReverse(access, reverseAccess)
                .set(this.surface, surface)
            return edge
        }
    }

    private class FakeWeighting(
        private val edgeWeight: Double,
        private val millis: Long = 0L,
        private val minWeight: Double = 0.0,
    ) : Weighting {
        override fun calcMinWeightPerDistance(): Double = minWeight
        override fun calcEdgeWeight(edgeState: EdgeIteratorState, reverse: Boolean): Double = edgeWeight
        override fun calcEdgeMillis(edgeState: EdgeIteratorState, reverse: Boolean): Long = millis
        override fun calcTurnWeight(inEdge: Int, viaNode: Int, outEdge: Int): Double = 0.0
        override fun calcTurnMillis(inEdge: Int, viaNode: Int, outEdge: Int): Long = 0L
        override fun hasTurnCosts(): Boolean = false
        override fun getName(): String = "fake"
    }
}

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
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
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
    fun straightAndTightRadiusGeometryProduceExpectedCurveTerms() {
        val fixture = fixture()
        val fastest = FakeWeighting(edgeWeight = 4.0, millis = 4321L, minWeight = 0.75)
        val custom = FakeWeighting(edgeWeight = 4.0)
        val straight = fixture.edge(
            distance = 60.0,
            geometry = listOf(0.0 to 0.00027),
        )
        val curved = fixture.edge(
            distance = 120.0,
            geometry = listOf(
                0.0 to 0.00027,
                0.00027 to 0.00027,
                0.00027 to 0.00054,
            ),
        )
        val weighting = weighting(fixture, custom, fastest, complexity = 1.0)
        assertEquals(10.0, weighting.calcEdgeWeight(straight, false), 1e-9)
        assertEquals(4.0, weighting.calcEdgeWeight(curved, false), 1e-6)
        assertEquals(4321L, weighting.calcEdgeMillis(straight, false))
        assertEquals(0.75, weighting.calcMinWeightPerDistance(), 0.0)
    }

    @Test
    fun repeatedDirectionalReadsReuseTheCachedCurveFactor() {
        val fixture = fixture()
        val geometryReads = intArrayOf(0)
        val edge = countingGeometryReads(fixture.edge(), geometryReads)
        val weighting = weighting(
            fixture,
            custom = FakeWeighting(edgeWeight = 4.0),
            fastest = FakeWeighting(edgeWeight = 4.0),
            complexity = 1.0,
        )

        val forward = weighting.calcEdgeWeight(edge, reverse = false)
        val repeatedForward = weighting.calcEdgeWeight(edge, reverse = false)
        val reverse = weighting.calcEdgeWeight(edge, reverse = true)

        assertEquals(forward, repeatedForward, 0.0)
        assertEquals(forward, reverse, 0.0)
        assertEquals("one geometry read for both directions of one edge", 1, geometryReads[0])
    }

    @Test
    fun circumradiusBucketsWeightTighterCurvesMoreStrongly() {
        val straight = pointListMeters(0.0 to 0.0, 100.0 to 0.0, 200.0 to 0.0)
        val broad = pointListMeters(0.0 to 0.0, 150.0 to 0.0, 150.0 to 150.0)
        val medium = pointListMeters(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0)
        val sharp = pointListMeters(0.0 to 0.0, 50.0 to 0.0, 50.0 to 50.0)
        val tight = pointListMeters(0.0 to 0.0, 30.0 to 0.0, 30.0 to 30.0)

        assertEquals(0.0, radiusCurveExposure(straight), 0.0)
        assertEquals(0.5, radiusCurveExposure(broad), 1e-6)
        assertEquals(0.65, radiusCurveExposure(medium), 1e-6)
        assertEquals(0.8, radiusCurveExposure(sharp), 1e-6)
        assertEquals(1.0, radiusCurveExposure(tight), 1e-6)
    }

    @Test
    fun aSharedSegmentUsesTheTighterAdjacentRadius() {
        val geometry = pointListMeters(
            0.0 to 200.0,
            0.0 to 0.0,
            30.0 to 0.0,
            30.0 to 30.0,
        )
        assertEquals(320.0 / 520.0, radiusCurveExposure(geometry), 1e-5)
    }

    @Test
    fun curveExposureIsDirectionIndependentAndRejectsShortJiggles() {
        val curve = listOf(0.0 to 0.0, 30.0 to 0.0, 30.0 to 30.0, 60.0 to 30.0)
        val forward = radiusCurveExposure(pointListMeters(*curve.toTypedArray()))
        val reverse = radiusCurveExposure(pointListMeters(*curve.reversed().toTypedArray()))
        assertEquals(forward, reverse, 1e-9)

        val shortJiggle = pointListMeters(
            0.0 to 0.0,
            100.0 to 0.0,
            102.0 to 2.0,
            104.0 to 0.0,
            204.0 to 0.0,
        )
        assertEquals(0.0, radiusCurveExposure(shortJiggle), 0.0)

        val duplicate = pointListMeters(0.0 to 0.0, 100.0 to 0.0, 100.0 to 0.0)
        assertTrue(radiusCurveExposure(duplicate).isFinite())
        assertEquals(0.0, radiusCurveExposure(pointListMeters(0.0 to 0.0, 100.0 to 0.0)), 0.0)
    }

    private fun pointListMeters(vararg points: Pair<Double, Double>) =
        PointList().apply {
            points.forEach { (x, y) -> add(y / METERS_PER_DEGREE, x / METERS_PER_DEGREE) }
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
        graph.nodeAccess.setNode(1, 0.0, 0.00054)
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
            geometry: List<Pair<Double, Double>> = listOf(0.0 to 0.00027),
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

    private fun countingGeometryReads(
        edge: EdgeIteratorState,
        reads: IntArray,
    ): EdgeIteratorState = Proxy.newProxyInstance(
        EdgeIteratorState::class.java.classLoader,
        arrayOf(EdgeIteratorState::class.java),
    ) { _, method, args ->
        if (method.name == "fetchWayGeometry") reads[0]++
        try {
            method.invoke(edge, *(args ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    } as EdgeIteratorState

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
}

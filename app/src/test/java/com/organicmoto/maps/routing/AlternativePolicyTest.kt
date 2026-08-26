package com.organicmoto.maps.routing

import com.graphhopper.ResponsePath
import com.graphhopper.util.PointList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlternativePolicyTest {

    @Test
    fun `shared road fraction handles identical disjoint partial and empty maps`() {
        assertEquals(1.0, AlternativePolicy.sharedRoadFraction(mapOf(1 to 100.0), mapOf(1 to 100.0)), 0.0)
        assertEquals(0.0, AlternativePolicy.sharedRoadFraction(mapOf(1 to 100.0), mapOf(2 to 100.0)), 0.0)
        assertEquals(0.5, AlternativePolicy.sharedRoadFraction(mapOf(1 to 100.0, 2 to 100.0), mapOf(1 to 100.0, 3 to 100.0)), 0.0)
        assertEquals(1.0, AlternativePolicy.sharedRoadFraction(emptyMap(), emptyMap()), 0.0)
    }

    @Test
    fun `longest distinct stretch sums each consecutive run`() {
        val sections = listOf(
            AlternativePolicy.EdgeSection(1, true, 100.0),
            AlternativePolicy.EdgeSection(2, true, 200.0),
            AlternativePolicy.EdgeSection(9, true, 300.0),
            AlternativePolicy.EdgeSection(3, true, 400.0),
            AlternativePolicy.EdgeSection(4, true, 500.0),
        )
        assertEquals(500.0, AlternativePolicy.longestDistinctStretch(sections, setOf(1, 2, 3)), 0.0)
        assertEquals(0.0, AlternativePolicy.longestDistinctStretch(sections, setOf(1, 2, 9, 3, 4)), 0.0)
        assertEquals(1_500.0, AlternativePolicy.longestDistinctStretch(sections, emptySet()), 0.0)
    }

    @Test
    fun `meaningful stretch uses clamped five percent threshold`() {
        val previous = listOf(cached(20_000.0, 1_000L))
        val candidate = cached(10_000.0, 1_100L)
        assertFalse(
            AlternativePolicy.isMeaningfullyDifferent(
                candidate,
                AlternativePolicy.RouteDiversity(0.9, 749.99),
                previous,
            ),
        )
        assertTrue(
            AlternativePolicy.isMeaningfullyDifferent(
                candidate,
                AlternativePolicy.RouteDiversity(0.9, 750.0),
                previous,
            ),
        )

        val longCandidate = cached(100_000.0, 1_100L)
        assertFalse(
            AlternativePolicy.isMeaningfullyDifferent(
                longCandidate,
                AlternativePolicy.RouteDiversity(0.9, 2_999.99),
                previous,
            ),
        )
        assertTrue(
            AlternativePolicy.isMeaningfullyDifferent(
                longCandidate,
                AlternativePolicy.RouteDiversity(0.9, 3_000.0),
                previous,
            ),
        )
        assertTrue(
            AlternativePolicy.isMeaningfullyDifferent(
                candidate,
                AlternativePolicy.RouteDiversity(1.0, 0.0),
                emptyList(),
            ),
        )
    }

    @Test
    fun `global detour budget uses detent primary and hard caps`() {
        val reference = path(1_000.0, 1_000L)
        assertTrue(AlternativePolicy.withinGlobalDetourBudget(path(1_650.0, 1_500L), reference, 1))
        assertFalse(AlternativePolicy.withinGlobalDetourBudget(path(1_650.1, 1_500L), reference, 1))
        assertTrue(AlternativePolicy.withinGlobalDetourBudget(path(2_250.0, 2_000L), reference, 6))
        assertFalse(AlternativePolicy.withinGlobalDetourBudget(path(2_250.1, 2_000L), reference, 5))
        assertTrue(AlternativePolicy.withinGlobalDetourBudget(path(1_000.0, 1_000L), path(0.0, 0L), 3))
    }

    @Test
    fun `local detour rejects only pronounced bounded bubbles`() {
        val reference = listOf(
            section(1, 100.0),
            section(2, 250.0),
            section(3, 100.0),
        )
        assertFalse(AlternativePolicy.hasExcessiveLocalDetour(listOf(section(1, 100.0)), reference))
        assertFalse(
            AlternativePolicy.hasExcessiveLocalDetour(
                listOf(section(1, 100.0), section(4, 2_000.0), section(3, 100.0)),
                listOf(section(1, 100.0), section(2, 16_000.0), section(3, 100.0)),
            ),
        )
        assertTrue(
            AlternativePolicy.hasExcessiveLocalDetour(
                listOf(section(1, 100.0), section(4, 1_750.0), section(3, 100.0)),
                reference,
            ),
        )
        assertTrue(
            AlternativePolicy.hasExcessiveLocalDetour(
                listOf(section(1, 100.0), section(4, 1_750.0), section(3, 100.0)),
                listOf(section(1, 100.0), section(2, 250.0), section(3, 100.0)),
            ),
        )
        assertFalse(
            AlternativePolicy.hasExcessiveLocalDetour(
                listOf(section(1, 100.0), section(4, 812.5), section(3, 100.0)),
                listOf(section(1, 100.0), section(2, 250.0), section(3, 100.0)),
            ),
        )
    }

    @Test
    fun `fallback tiers prefer target then budget then meaningfulness`() {
        val previous = listOf(cached(20_000.0, 1_000L))
        val route = cached(20_000.0, 1_000L)
        fun candidate(overlap: Double, within: Boolean, primary: Boolean, stretch: Double) =
            AlternativePolicy.FallbackCandidate(
                route = route,
                diversity = AlternativePolicy.RouteDiversity(overlap, stretch),
                withinDetourBudget = within,
                isPrimary = primary,
            )

        assertEquals(0, AlternativePolicy.fallbackTier(candidate(0.5, true, false, 1_000.0), 0.7, previous))
        assertEquals(1, AlternativePolicy.fallbackTier(candidate(0.8, true, false, 1_000.0), 0.7, previous))
        assertEquals(2, AlternativePolicy.fallbackTier(candidate(0.8, false, false, 1_000.0), 0.7, previous))
        assertEquals(3, AlternativePolicy.fallbackTier(candidate(0.8, false, true, 100.0), 0.7, previous))
        assertEquals(4, AlternativePolicy.fallbackTier(candidate(0.8, false, false, 100.0), 0.7, previous))
    }

    @Test
    fun `alternative request formulas stay stable`() {
        assertEquals(1.5, AlternativePolicy.maxAlternativeWeight(0), 0.0)
        assertEquals(2.0, AlternativePolicy.maxAlternativeWeight(2), 0.0)
        assertEquals(2.5, AlternativePolicy.maxAlternativeWeight(99), 0.0)
        assertEquals((1 - 0.7) * 2.0 * 1.0, AlternativePolicy.previousRoadPenalty(0.7, 0, 0), 0.0)
        assertEquals((1 - 0.55) * 8.0 * 1.2, AlternativePolicy.previousRoadPenalty(0.55, 2, 1), 0.0)
    }

    private fun section(id: Int, distance: Double) =
        AlternativePolicy.EdgeSection(id, true, distance)

    private fun cached(distance: Double, time: Long): AlternativePolicy.CachedRoute =
        AlternativePolicy.CachedRoute(path(distance, time), emptyMap(), emptyList())

    private fun path(distance: Double, time: Long): ResponsePath =
        ResponsePath()
            .setPoints(PointList().apply { add(0.0, 0.0); add(0.01, 0.01) })
            .setDistance(distance)
            .setTime(time)
}

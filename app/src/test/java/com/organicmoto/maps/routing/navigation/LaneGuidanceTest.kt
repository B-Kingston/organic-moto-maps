package com.organicmoto.maps.routing.navigation

import com.graphhopper.ResponsePath
import com.graphhopper.util.Instruction
import com.graphhopper.util.InstructionList
import com.graphhopper.util.PointList
import com.graphhopper.util.details.PathDetail
import com.organicmoto.maps.routing.MOTO_LANES_DETAIL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LaneGuidanceTest {

    private fun picks(value: String, sign: Int): List<Int>? =
        LaneGuidance.recommend(LaneGuidance.parse(value), sign)?.recommendedIndices

    @Test
    fun parsesCountOnlyAndMarkedValues() {
        val countOnly = LaneGuidance.parse("L:?|?|?")!!
        assertEquals(3, countOnly.laneCount)
        assertTrue(countOnly.leftHandTraffic)
        assertFalse(countOnly.marked)

        val marked = LaneGuidance.parse("R:|sr|lsru")!!
        assertFalse(marked.leftHandTraffic)
        assertEquals(
            listOf(emptySet(), setOf(LaneArrow.STRAIGHT, LaneArrow.RIGHT), LaneArrow.entries.toSet()),
            marked.arrows,
        )
    }

    @Test
    fun rejectsMalformedValues() {
        for (bad in listOf(null, "", "L", "L:", "X:?|?", "L?|?", "L:?|l", "L:x|s")) {
            if (bad == "L:") {
                // One empty lane token is a well-formed single unmarked lane.
                assertEquals(1, LaneGuidance.parse(bad)!!.laneCount)
                continue
            }
            assertNull(bad, LaneGuidance.parse(bad))
        }
    }

    @Test
    fun markedArrowsPickEveryLaneCarryingTheTurn() {
        // Real Brisbane approaches from the lane-aware Queensland graph.
        assertEquals(listOf(0, 1), picks("L:l|ls", Instruction.TURN_LEFT)) // onto Merivale Street
        assertEquals(listOf(1, 2), picks("L:|sr|r", Instruction.TURN_RIGHT)) // onto Logan Road
        assertEquals(listOf(3), picks("L:|||r", Instruction.TURN_RIGHT)) // onto Upper Roma Street
        assertEquals(listOf(0), picks("L:l|s|", Instruction.KEEP_LEFT)) // onto Milton Road
    }

    @Test
    fun keepAlongAThroughOnlyForkUsesTheThroughLanes() {
        assertEquals(listOf(0, 1), picks("L:s|s|r", Instruction.KEEP_LEFT))
        // A full turn never borrows the through lanes.
        assertNull(picks("L:s|s|r", Instruction.TURN_LEFT))
    }

    @Test
    fun unmarkedOuterLaneTakesTheRemainingTurn() {
        assertEquals(listOf(0), picks("L:|s|sr", Instruction.TURN_LEFT))
        assertEquals(listOf(2), picks("R:l|s|", Instruction.TURN_RIGHT))
    }

    @Test
    fun countOnlyRoadsSuggestTheOuterLaneOnTheTurnSide() {
        assertEquals(listOf(0), picks("L:?|?|?", Instruction.TURN_LEFT))
        assertEquals(listOf(2), picks("L:?|?|?", Instruction.TURN_SHARP_RIGHT))
        assertEquals(listOf(0), picks("R:?|?", Instruction.KEEP_LEFT))
        assertEquals(listOf(1), picks("R:?|?", Instruction.TURN_SLIGHT_RIGHT))
        val guidance = LaneGuidance.recommend(LaneGuidance.parse("L:?|?|?"), Instruction.TURN_RIGHT)!!
        assertFalse(guidance.marked)
        // Only the suggested lane shows an arrow, and it is the maneuver's.
        assertEquals(listOf(emptySet(), emptySet(), setOf(LaneArrow.RIGHT)), guidance.lanes.map { it.arrows })
    }

    @Test
    fun uTurnUsesTheOncomingSideForTheDrivingSide() {
        assertEquals(listOf(1), picks("L:?|?", Instruction.U_TURN_UNKNOWN))
        assertEquals(listOf(0), picks("R:?|?", Instruction.U_TURN_UNKNOWN))
        assertEquals(listOf(2), picks("L:l|s|ru", Instruction.U_TURN_UNKNOWN))
    }

    @Test
    fun noGuidanceForSingleLanesOrManeuversWithoutALaneChoice() {
        assertNull(picks("L:?", Instruction.TURN_LEFT))
        assertNull(picks("L:l", Instruction.TURN_LEFT))
        for (sign in listOf(
            Instruction.CONTINUE_ON_STREET, Instruction.USE_ROUNDABOUT, Instruction.LEAVE_ROUNDABOUT,
            Instruction.FINISH, Instruction.REACHED_VIA, Instruction.FERRY,
        )) {
            assertNull("sign $sign", picks("L:l|s|r", sign))
        }
        assertNull(LaneGuidance.recommend(null, Instruction.TURN_LEFT))
    }

    @Test
    fun descriptionNamesTheLanesInPlainWords() {
        fun describe(value: String, sign: Int) =
            LaneGuidance.recommend(LaneGuidance.parse(value), sign)!!.description()
        assertEquals("use the left lane of 3, suggested", describe("L:?|?|?", Instruction.TURN_LEFT))
        assertEquals("use the right lane of 4", describe("L:|||r", Instruction.TURN_RIGHT))
        assertEquals("use lanes 2 and 3 of 3", describe("L:|sr|r", Instruction.TURN_RIGHT))
        assertEquals("use lane 2 of 3", describe("L:s|l|s", Instruction.TURN_LEFT))
        assertEquals("use any lane of 2", describe("L:l|ls", Instruction.TURN_LEFT))
    }

    /** 100 m segments: vertex v sits at v * 100 m. */
    private val distances = DoubleArray(12) { it * 100.0 }

    @Test
    fun forTurnReadsTheSegmentEnteringTheManeuver() {
        val lanes = MutableList<String?>(11) { null }
        lanes[4] = "L:?|?"
        val guidance = LaneGuidance.forTurn(lanes, distances, 5, 0, Instruction.TURN_RIGHT)
        assertEquals(listOf(1), guidance!!.recommendedIndices)
    }

    @Test
    fun forTurnLooksBackOnlyWithinTheLimitAndAfterThePreviousTurn() {
        val lanes = MutableList<String?>(11) { null }
        lanes[3] = "L:?|?"
        // Segment 3 ends 100 m before vertex 5: inside the look-back window.
        assertNotNull(LaneGuidance.forTurn(lanes, distances, 5, 0, Instruction.TURN_LEFT))
        // Ends 200 m before vertex 6: beyond LOOKBACK_M.
        assertNull(LaneGuidance.forTurn(lanes, distances, 6, 0, Instruction.TURN_LEFT))
        // A previous maneuver at vertex 4 means segment 3 belongs to another road.
        assertNull(LaneGuidance.forTurn(lanes, distances, 5, 4, Instruction.TURN_LEFT))
    }

    @Test
    fun aKnownSingleLaneApproachStopsTheLookBack() {
        val lanes = MutableList<String?>(11) { null }
        lanes[3] = "L:?|?|?"
        lanes[4] = "L:?"
        assertNull(LaneGuidance.forTurn(lanes, distances, 5, 0, Instruction.TURN_LEFT))
    }

    @Test
    fun forTurnToleratesGraphsWithoutLaneData() {
        assertNull(LaneGuidance.forTurn(emptyList(), distances, 5, 0, Instruction.TURN_LEFT))
        assertNull(LaneGuidance.forTurn(listOf("L:?|?"), distances, 0, 0, Instruction.TURN_LEFT))
    }

    @Test
    fun recommendationInvariantsHoldForRandomRoads() {
        val random = Random(42)
        val signs = listOf(
            Instruction.TURN_SHARP_LEFT, Instruction.TURN_LEFT, Instruction.TURN_SLIGHT_LEFT, Instruction.KEEP_LEFT,
            Instruction.TURN_SHARP_RIGHT, Instruction.TURN_RIGHT, Instruction.TURN_SLIGHT_RIGHT, Instruction.KEEP_RIGHT,
            Instruction.U_TURN_LEFT, Instruction.U_TURN_RIGHT, Instruction.U_TURN_UNKNOWN,
        )
        repeat(5_000) {
            val count = random.nextInt(1, 11)
            val marked = random.nextBoolean()
            val tokens = List(count) {
                if (!marked) "?" else "lsru".filter { random.nextInt(4) == 0 }
            }
            val value = (if (random.nextBoolean()) "L:" else "R:") + tokens.joinToString("|")
            val road = LaneGuidance.parse(value)!!
            val sign = signs.random(random)
            val guidance = LaneGuidance.recommend(road, sign) ?: run {
                assertTrue("only marked or single-lane roads may decline: $value", marked || count < 2)
                return@repeat
            }
            assertTrue(count >= 2)
            assertEquals(count, guidance.lanes.size)
            val picks = guidance.recommendedIndices
            assertTrue("a recommendation names at least one lane: $value", picks.isNotEmpty())
            assertTrue(picks.all { it in 0 until count })
            if (!marked) {
                assertEquals("count-only roads suggest exactly one outer lane: $value", 1, picks.size)
                assertTrue(picks.single() == 0 || picks.single() == count - 1)
            } else {
                // Marked recommendations never alter the painted arrows.
                assertEquals(road.arrows, guidance.lanes.map { it.arrows })
            }
        }
    }

    @Test
    fun factoryExpandsTheLaneDetailOntoTheTrackTurns() {
        val points = PointList().apply { for (i in 0..4) add(-27.0 - i * 0.0009, 153.0) }
        val instructions = InstructionList(null).apply {
            add(Instruction(Instruction.CONTINUE_ON_STREET, "Approach", slice(points, 0, 3)).setTime(30_000L))
            add(Instruction(Instruction.TURN_RIGHT, "Logan Road", slice(points, 3, 4)).setTime(10_000L))
            add(Instruction(Instruction.FINISH, "", slice(points, 4, 5)))
        }
        val path = ResponsePath().setPoints(points).also { it.instructions = instructions }
        path.addPathDetails(
            mapOf(
                MOTO_LANES_DETAIL to listOf(
                    PathDetail(null).apply { first = 0; last = 2 },
                    PathDetail("L:|sr|r").apply { first = 2; last = 4 },
                ),
            ),
        )

        assertEquals(listOf(null, null, "L:|sr|r", "L:|sr|r"), RouteTrackFactory.segmentLanes(path, 5))
        val turn = RouteTrackFactory.fromPath(path).turns.single()
        assertEquals("Logan Road", turn.streetName)
        assertEquals(listOf(1, 2), turn.lanes!!.recommendedIndices)
        assertTrue(turn.lanes!!.marked)
    }

    @Test
    fun factoryWithoutLaneDetailsLeavesTurnsUnchanged() {
        val points = PointList().apply { for (i in 0..2) add(-27.0 - i * 0.0009, 153.0) }
        val instructions = InstructionList(null).apply {
            add(Instruction(Instruction.CONTINUE_ON_STREET, "A", slice(points, 0, 1)).setTime(10_000L))
            add(Instruction(Instruction.TURN_LEFT, "B", slice(points, 1, 2)).setTime(10_000L))
            add(Instruction(Instruction.FINISH, "", slice(points, 2, 3)))
        }
        val path = ResponsePath().setPoints(points).also { it.instructions = instructions }
        assertEquals(emptyList<String?>(), RouteTrackFactory.segmentLanes(path, 3))
        assertNull(RouteTrackFactory.fromPath(path).turns.single().lanes)
    }

    private fun slice(points: PointList, from: Int, to: Int) = PointList().apply {
        for (i in from until to) add(points.getLat(i), points.getLon(i))
    }
}

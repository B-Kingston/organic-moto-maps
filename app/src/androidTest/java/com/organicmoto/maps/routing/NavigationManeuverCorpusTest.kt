package com.organicmoto.maps.routing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.graphhopper.util.Instruction
import com.organicmoto.maps.routing.navigation.RouteTrackFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real graph coverage for the less common turn signs shown by the navigation HUD. */
@RunWith(AndroidJUnit4::class)
class NavigationManeuverCorpusTest {
    @Test(timeout = 600_000)
    fun committedRoutesContainEverySupportedTurnShape() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val router = GraphHopperRouter(context)
        val turns = ROUTE_CORPUS.take(10).flatMap { entry ->
            RouteTrackFactory.fromPath(router.route(entry.from, entry.to).routes.first()).turns
        }
        val signs = turns.map { it.sign }.toSet()

        assertTrue("corpus should exercise left turns", Instruction.TURN_LEFT in signs)
        assertTrue("corpus should exercise right turns", Instruction.TURN_RIGHT in signs)
        assertTrue("corpus should exercise sharp left turns", Instruction.TURN_SHARP_LEFT in signs)
        assertTrue("corpus should exercise sharp right turns", Instruction.TURN_SHARP_RIGHT in signs)
        assertTrue("corpus should exercise keep-left forks", Instruction.KEEP_LEFT in signs)
        assertTrue("corpus should exercise keep-right forks", Instruction.KEEP_RIGHT in signs)
        assertTrue(
            "corpus should exercise left veers with measured geometry",
            turns.any { it.sign == Instruction.TURN_SLIGHT_LEFT && it.turnAngleDeg?.isFinite() == true },
        )
        assertTrue(
            "corpus should exercise right veers with measured geometry",
            turns.any { it.sign == Instruction.TURN_SLIGHT_RIGHT && it.turnAngleDeg?.isFinite() == true },
        )
        assertTrue(
            "corpus should exercise roundabouts with numbered exits",
            turns.any { it.sign == Instruction.USE_ROUNDABOUT && (it.roundaboutExitNumber ?: 0) > 0 },
        )
        assertTrue("corpus should exercise clockwise roundabouts", turns.any {
            it.sign == Instruction.USE_ROUNDABOUT && it.roundaboutClockwise == true
        })
        assertTrue("corpus should retain ambiguous roundabout direction", turns.any {
            it.sign == Instruction.USE_ROUNDABOUT && it.roundaboutClockwise == null
        })
        assertTrue("corpus should exercise U-turns", Instruction.U_TURN_RIGHT in signs)
    }
}

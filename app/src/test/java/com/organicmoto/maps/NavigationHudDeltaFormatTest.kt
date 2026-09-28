package com.organicmoto.maps

import com.graphhopper.util.Instruction
import com.organicmoto.maps.routing.navigation.NavigationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/** Contract of the motorsport delta formatting used by the HUD cell. */
class NavigationHudDeltaFormatTest {

    @Test
    fun `nan renders as dash`() {
        assertEquals("—", NavigationHudFormat.deltaText(Double.NaN))
    }

    @Test
    fun `lost time carries a plus sign`() {
        assertEquals("+12.3 s", NavigationHudFormat.deltaText(12.34))
        assertEquals("+1.0 s", NavigationHudFormat.deltaText(1.0))
    }

    @Test
    fun `gained time carries a minus sign`() {
        assertEquals("-1.0 s", NavigationHudFormat.deltaText(-1.0))
        assertEquals("-35.7 s", NavigationHudFormat.deltaText(-35.68))
    }

    @Test
    fun `near zero collapses to plain seconds`() {
        assertEquals("0.0 s", NavigationHudFormat.deltaText(0.0))
        assertEquals("0.0 s", NavigationHudFormat.deltaText(0.04))
        assertEquals("0.0 s", NavigationHudFormat.deltaText(-0.04))
    }

    @Test
    fun `maneuver descriptions use GraphHopper sign constants`() {
        fun turn(sign: Int) = NavigationSnapshot.TurnInfo(sign, "", 120.0)

        assertEquals("make a U-turn in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.U_TURN_RIGHT)))
        assertEquals("make a U-turn in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.U_TURN_UNKNOWN)))
        assertEquals("enter the roundabout in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.USE_ROUNDABOUT)))
        assertEquals("exit the roundabout in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.LEAVE_ROUNDABOUT)))
        assertEquals("veer slightly left in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.TURN_SLIGHT_LEFT)))
        assertEquals("veer slightly right in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.TURN_SLIGHT_RIGHT)))
        assertEquals("keep left in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.KEEP_LEFT)))
        assertEquals("keep right in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.KEEP_RIGHT)))
    }

    @Test
    fun `roundabout description names the selected exit`() {
        val secondExit = NavigationSnapshot.TurnInfo(
            sign = Instruction.USE_ROUNDABOUT,
            streetName = "",
            distanceM = 120.0,
            roundaboutExitNumber = 2,
        )
        val twelfthExit = secondExit.copy(roundaboutExitNumber = 12)

        assertEquals(
            "take the 2nd exit at the roundabout in 120 m",
            NavigationHudFormat.turnDescription(secondExit),
        )
        assertEquals(
            "take the 12th exit at the roundabout in 120 m",
            NavigationHudFormat.turnDescription(twelfthExit),
        )
    }

    @Test
    fun `slight veer angle follows route geometry and falls back safely`() {
        assertEquals(
            18f,
            NavigationHudFormat.slightVeerAngleDeg(Instruction.TURN_SLIGHT_RIGHT, 18.0),
        )
        assertEquals(
            -24f,
            NavigationHudFormat.slightVeerAngleDeg(Instruction.TURN_SLIGHT_LEFT, -24.0),
        )
        assertEquals(
            35f,
            NavigationHudFormat.slightVeerAngleDeg(Instruction.TURN_SLIGHT_RIGHT, -24.0),
        )
        assertEquals(
            -35f,
            NavigationHudFormat.slightVeerAngleDeg(Instruction.TURN_SLIGHT_LEFT, Double.NaN),
        )
    }

    @Test
    fun `unknown u turn uses route geometry only when its side is clear`() {
        assertEquals(-1, NavigationHudFormat.uTurnSide(Instruction.U_TURN_LEFT, 90.0))
        assertEquals(1, NavigationHudFormat.uTurnSide(Instruction.U_TURN_RIGHT, -90.0))
        assertEquals(-1, NavigationHudFormat.uTurnSide(Instruction.U_TURN_UNKNOWN, -90.0))
        assertEquals(1, NavigationHudFormat.uTurnSide(Instruction.U_TURN_UNKNOWN, 90.0))
        assertEquals(0, NavigationHudFormat.uTurnSide(Instruction.U_TURN_UNKNOWN, 180.0))
        assertEquals(0, NavigationHudFormat.uTurnSide(Instruction.U_TURN_UNKNOWN, Double.NaN))
    }

    @Test
    fun `delta accessibility text states direction`() {
        assertEquals("pace delta unavailable", NavigationHudFormat.deltaDescription(Double.NaN))
        assertEquals("pace +1.0 s lost", NavigationHudFormat.deltaDescription(1.0))
        assertEquals("pace -1.0 s gained", NavigationHudFormat.deltaDescription(-1.0))
    }
}

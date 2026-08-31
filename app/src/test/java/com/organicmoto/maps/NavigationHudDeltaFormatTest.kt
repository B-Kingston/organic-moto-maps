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
        assertEquals("keep left in 120 m", NavigationHudFormat.turnDescription(turn(Instruction.KEEP_LEFT)))
    }

    @Test
    fun `delta accessibility text states direction`() {
        assertEquals("pace delta unavailable", NavigationHudFormat.deltaDescription(Double.NaN))
        assertEquals("pace +1.0 s lost", NavigationHudFormat.deltaDescription(1.0))
        assertEquals("pace -1.0 s gained", NavigationHudFormat.deltaDescription(-1.0))
    }
}

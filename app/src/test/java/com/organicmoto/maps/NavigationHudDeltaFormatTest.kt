package com.organicmoto.maps

import com.graphhopper.util.Instruction
import com.organicmoto.maps.routing.navigation.NavigationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            "enter the roundabout in 120 m, then take the 2nd exit",
            NavigationHudFormat.turnDescription(secondExit),
        )
        assertEquals(
            "enter the roundabout in 120 m, then take the 12th exit",
            NavigationHudFormat.turnDescription(twelfthExit),
        )
    }

    @Test
    fun voiceSettingsDescriptionSeparatesPreferenceFromAvailability() {
        assertEquals(
            "Voice guidance settings, enabled, checking offline voice",
            NavigationHudFormat.voiceSettingsDescription(true, "checking offline voice"),
        )
        assertEquals(
            "Voice guidance settings, enabled",
            NavigationHudFormat.voiceSettingsDescription(true),
        )
        assertEquals(
            "Voice guidance settings, disabled",
            NavigationHudFormat.voiceSettingsDescription(false, "checking offline voice"),
        )
    }

    @Test
    fun darkRideMapDescriptionStatesItsCurrentSetting() {
        assertEquals("Dark ride map, on", NavigationHudFormat.darkRideMapDescription(true))
        assertEquals("Dark ride map, off", NavigationHudFormat.darkRideMapDescription(false))
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
    fun maneuverAccessibilityDescriptionIncludesTheRoadName() {
        assertEquals(
            "turn left in 120 m onto Queen Street",
            NavigationHudFormat.turnDescription(
                NavigationSnapshot.TurnInfo(Instruction.TURN_LEFT, "Queen Street", 120.0),
            ),
        )
    }

    @Test
    fun `compact eta keeps the data bar narrow`() {
        assertEquals("1h 19m", NavigationHudFormat.etaCompactText(79 * 60.0))
        assertEquals("2h", NavigationHudFormat.etaCompactText(2 * 3600.0))
        assertEquals("45m", NavigationHudFormat.etaCompactText(45 * 60.0))
        assertEquals("<1m", NavigationHudFormat.etaCompactText(30.0))
        assertEquals("<1m", NavigationHudFormat.etaCompactText(0.0))
        assertEquals("12h 5m", NavigationHudFormat.etaCompactText((12 * 60 + 5) * 60.0))
    }

    @Test
    fun `delta accessibility text states direction`() {
        assertEquals("pace delta unavailable", NavigationHudFormat.deltaDescription(Double.NaN))
        assertEquals("pace +1.0 s lost", NavigationHudFormat.deltaDescription(1.0))
        assertEquals("pace -1.0 s gained", NavigationHudFormat.deltaDescription(-1.0))
    }

    @Test
    fun narrowDataBarUsesTwoRowsInsteadOfEllipsis() {
        val values = listOf("88", "1h 19m", "12.3 km", "+12.3 s")
        // 320 dp cannot fit four cells plus the glove-sized media opener and
        // END at any font scale: the bar must switch to the two-row grid.
        assertEquals(
            DataBarLayout.TWO_ROW,
            NavigationHudFormat.dataBarLayout(320f, 1f, values),
        )
        assertEquals(
            DataBarLayout.TWO_ROW,
            NavigationHudFormat.dataBarLayout(320f, 1.5f, values),
        )
        assertEquals(
            DataBarLayout.TWO_ROW,
            NavigationHudFormat.dataBarLayout(320f, 2f, values),
        )
    }

    @Test
    fun wideDataBarKeepsTheCompactSingleRow() {
        val values = listOf("88", "1h 19m", "12.3 km", "+12.3 s")
        assertEquals(
            DataBarLayout.SINGLE_ROW,
            NavigationHudFormat.dataBarLayout(465f, 1f, values),
        )
        // A wide screen at a large font scale still needs the grid.
        assertEquals(
            DataBarLayout.TWO_ROW,
            NavigationHudFormat.dataBarLayout(465f, 2f, values),
        )
    }

    @Test
    fun metricCellWidthGrowsWithValueLengthAndFontScale() {
        val short = NavigationHudFormat.metricCellWidthDp("8", 1f)
        val long = NavigationHudFormat.metricCellWidthDp("12.3 km", 1f)
        assertTrue("longer value must need more room", long > short)
        assertTrue(
            "2x font scale must need more room",
            NavigationHudFormat.metricCellWidthDp("12.3 km", 2f) > long * 1.5f,
        )
    }
}

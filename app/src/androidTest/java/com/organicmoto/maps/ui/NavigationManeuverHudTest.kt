package com.organicmoto.maps.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.graphhopper.util.Instruction
import com.organicmoto.maps.NavigationHud
import com.organicmoto.maps.routing.navigation.NavigationSnapshot
import com.organicmoto.maps.routing.navigation.NavigationState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationManeuverHudTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun roundaboutHudShowsItsExitNumber() {
        val snapshot = NavigationSnapshot(
            state = NavigationState.OnRoute,
            lat = -27.0,
            lon = 153.0,
            bearingDeg = 0.0,
            speedMps = 0.0,
            speedLimitMps = Double.NaN,
            remainingDistanceM = 500.0,
            remainingTimeS = 600.0,
            completionPercent = 0,
            paceDeltaS = Double.NaN,
            turn = NavigationSnapshot.TurnInfo(
                sign = Instruction.USE_ROUNDABOUT,
                streetName = "Abbotsford Road",
                distanceM = 120.0,
                roundaboutExitNumber = 2,
                roundaboutClockwise = true,
            ),
        )
        composeRule.setContent {
            NavigationHud(
                snapshot,
                voiceGuidanceReady = false,
                voiceGuidanceStatusDescription = "checking offline voice",
            )
        }

        composeRule.onNodeWithContentDescription(
            "Navigation guidance: enter the roundabout in 120 m, then take the 2nd exit onto Abbotsford Road",
        ).assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Voice guidance settings, enabled, checking offline voice")
            .assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun keepLeftAndSlightVeerHaveSpecificGuidanceLabels() {
        val keepLeft = NavigationSnapshot(
            state = NavigationState.OnRoute,
            lat = -27.0,
            lon = 153.0,
            bearingDeg = 0.0,
            speedMps = 0.0,
            speedLimitMps = Double.NaN,
            remainingDistanceM = 500.0,
            remainingTimeS = 600.0,
            completionPercent = 0,
            paceDeltaS = Double.NaN,
            turn = NavigationSnapshot.TurnInfo(
                sign = Instruction.KEEP_LEFT,
                streetName = "Samford Road",
                distanceM = 80.0,
            ),
        )
        val slightVeer = keepLeft.copy(
            turn = NavigationSnapshot.TurnInfo(
                sign = Instruction.TURN_SLIGHT_RIGHT,
                streetName = "Mount Glorious Road",
                distanceM = 350.0,
                turnAngleDeg = 42.0,
            ),
        )
        composeRule.setContent {
            Column {
                NavigationHud(keepLeft)
                NavigationHud(slightVeer)
            }
        }

        composeRule
            .onNodeWithContentDescription("Navigation guidance: keep left in 80 m onto Samford Road")
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Navigation guidance: veer slightly right in 350 m onto Mount Glorious Road")
            .assertIsDisplayed()
    }

    @Test
    fun keepRightSlightLeftAndUnknownUTurnRenderTheirOwnGuidance() {
        fun snapshot(sign: Int, distanceM: Double, angle: Double? = null) = NavigationSnapshot(
            state = NavigationState.OnRoute,
            lat = -27.0,
            lon = 153.0,
            bearingDeg = 0.0,
            speedMps = 0.0,
            speedLimitMps = Double.NaN,
            remainingDistanceM = 500.0,
            remainingTimeS = 600.0,
            completionPercent = 0,
            paceDeltaS = Double.NaN,
            turn = NavigationSnapshot.TurnInfo(
                sign = sign,
                streetName = "",
                distanceM = distanceM,
                turnAngleDeg = angle,
            ),
        )
        val keepRight = snapshot(Instruction.KEEP_RIGHT, 70.0)
        val slightLeft = snapshot(Instruction.TURN_SLIGHT_LEFT, 180.0, -24.0)
        val unknownUTurn = snapshot(Instruction.U_TURN_UNKNOWN, 35.0, 180.0)

        composeRule.setContent {
            Column {
                NavigationHud(keepRight)
                NavigationHud(slightLeft)
                NavigationHud(unknownUTurn)
            }
        }

        composeRule
            .onNodeWithContentDescription("Navigation guidance: keep right in 70 m")
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Navigation guidance: veer slightly left in 180 m")
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Navigation guidance: make a U-turn in 35 m")
            .assertIsDisplayed()
    }
}

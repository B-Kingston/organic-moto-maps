package com.organicmoto.maps.ui

import android.Manifest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.RouteGenerationKey
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComplexityDialTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    @Test(timeout = 300_000)
    fun dialStopsAtZeroCountsClicksAndSurvivesRecreation() {
        // Positive control for both rotation directions: if a synthesized
        // gesture were silently dropped, everything below would be vacuous.
        KnobRobot.performDetentClicks(composeRule, 3)
        KnobRobot.waitForLevel(composeRule, 3) // clockwise detents must register
        KnobRobot.performDetentClicks(composeRule, -1)
        KnobRobot.waitForLevel(composeRule, 2) // counter-clockwise detents must register while away from the stop

        // A deep counter-clockwise run clamps hard at Fastest (0) and stays there.
        KnobRobot.performDetentClicks(composeRule, -12, clampAtZero = true)
        KnobRobot.waitForLevel(composeRule, 0) // dial must clamp at zero

        // Clicks past one revolution keep counting without an upper bound.
        KnobRobot.performDetentClicks(composeRule, 17)
        KnobRobot.waitForLevel(composeRule, 17)

        composeRule.activityRule.scenario.recreate()
        KnobRobot.waitForLevel(composeRule, 17) // level must survive activity recreation
    }

    @Test(timeout = 300_000)
    fun releasingDialAfterSuccessStartsANewGeneration() {
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        UiTestWaits.waitForState(composeRule, "success")
        val generation = composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteGenerationKey))
            .fetchSemanticsNode()
            .config[RouteGenerationKey]
        KnobRobot.performDetentClicks(composeRule, 1)
        KnobRobot.waitForLevel(composeRule, 1)
        UiTestWaits.waitForState(composeRule, "success")
        val nextGeneration = composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteGenerationKey))
            .fetchSemanticsNode()
            .config[RouteGenerationKey]
        assertTrue(nextGeneration > generation)
    }

}

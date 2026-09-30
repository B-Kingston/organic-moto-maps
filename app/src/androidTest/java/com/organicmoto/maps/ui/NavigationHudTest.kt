package com.organicmoto.maps.ui

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guidance flow on the real screen: a routed plan switches the action to
 * RIDE; tapping it enters ride mode (top HUD, bottom data bar with the red
 * END button); tapping END cancels navigation and returns to the planner
 * (START), so a new route can be searched immediately.
 */
@RunWith(AndroidJUnit4::class)
class NavigationHudTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun routeSomethingValid() {
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(120_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher
                .expectValue(com.organicmoto.maps.RouteUiStateKey, "success"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun routedPlanShowsRideAction() {
        routeSomethingValid()
        composeRule.onNodeWithText("RIDE").assertIsDisplayed()
        org.junit.Assert.assertTrue(
            "dark ride map toggle must stay inside guidance",
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Dark ride map", substring = true),
            ).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun endButtonCancelsGuidanceAndReturnsToPlanner() {
        routeSomethingValid()
        composeRule.onNodeWithText("RIDE").performClick()
        composeRule.onNodeWithText("END").assertIsDisplayed()
        composeRule.onNodeWithText("END").performClick()
        composeRule.onNodeWithText("START").assertIsDisplayed()
    }

    @Test
    fun centreOnMeButtonIsAvailable() {
        composeRule.onNodeWithContentDescription("Centre on me").assertIsDisplayed()
    }

    @Test
    fun voiceSettingsRemainReachableDuringGuidance() {
        routeSomethingValid()
        composeRule.onNodeWithText("RIDE").performClick()
        composeRule.onNodeWithContentDescription("Voice guidance settings", substring = true)
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("Voice guidance").assertIsDisplayed()
    }

    @Test
    fun darkRideMapPreferenceIsRememberedForTheNextRide() {
        routeSomethingValid()
        composeRule.onNodeWithText("RIDE").performClick()
        val darkMapOff = composeRule.onAllNodes(
            androidx.compose.ui.test.hasContentDescription("Dark ride map, off"),
        ).fetchSemanticsNodes()
        if (darkMapOff.isNotEmpty()) {
            composeRule.onNodeWithContentDescription("Dark ride map, off").performClick()
        }
        composeRule.onNodeWithContentDescription("Dark ride map, on").assertIsDisplayed()
        waitForDarkGuidanceStyle()
        composeRule.onNodeWithContentDescription("Dark ride map, on").performClick()
        composeRule.onNodeWithContentDescription("Dark ride map, off").performClick()
        waitForDarkGuidanceStyle()
        composeRule.onNodeWithContentDescription("Dark ride map, on").assertIsDisplayed()

        composeRule.onNodeWithText("END").performClick()
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(120_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher
                .expectValue(com.organicmoto.maps.RouteUiStateKey, "success"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("RIDE").performClick()
        composeRule.onNodeWithContentDescription("Dark ride map, on").assertIsDisplayed()
        waitForDarkGuidanceStyle()
        composeRule.onNodeWithText("END").performClick()
    }

    private fun waitForDarkGuidanceStyle() {
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.SemanticsMatcher.expectValue(
                    com.organicmoto.maps.DarkGuidanceStyleReadyKey,
                    true,
                ),
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

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
 * Guidance action-button flow on the real screen: a routed plan switches the
 * action to RIDE; tapping it enters guidance (STOP GUIDANCE); tapping again
 * returns to RIDE.
 */
@RunWith(AndroidJUnit4::class)
class NavigationHudTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
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
    }

    @Test
    fun togglingGuidanceShowsStopThenReturnsToRide() {
        routeSomethingValid()
        composeRule.onNodeWithText("RIDE").performClick()
        composeRule.onNodeWithText("STOP GUIDANCE").assertIsDisplayed()
        composeRule.onNodeWithText("STOP GUIDANCE").performClick()
        composeRule.onNodeWithText("RIDE").assertIsDisplayed()
    }
}

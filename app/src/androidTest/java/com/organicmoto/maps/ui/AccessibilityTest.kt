package com.organicmoto.maps.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.RouteUiStateKey
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun idleControlsExposeLabelsAndReachableTargets() {
        val descriptions = listOf(
            "Ride complexity level 0",
            "Saved routes",
            "Route settings",
            "From",
            "To",
        )
        descriptions.forEach { description ->
            composeRule.onNodeWithContentDescription(description).assertExists()
        }
        assertTargetAtLeast48Dp("Ride complexity level 0", requiresClick = false)
        assertTargetAtLeast48Dp("Saved routes")
        assertTargetAtLeast48Dp("Route settings")
        val start = composeRule.onNodeWithText("START")
        start.assertHasClickAction()
        assertTrue(start.fetchSemanticsNode().boundsInRoot.width >= 48f * composeRule.density.density)
    }

    @Test
    fun settingsAndRouteCardsExposeLabels() {
        composeRule.onNodeWithContentDescription("Route settings").performClick()
        composeRule.onNodeWithContentDescription("Maximum shared roads 70 percent").assertExists()
        composeRule.onNodeWithContentDescription("Block unpaved roads").assertExists()
        composeRule.onNodeWithText("CANCEL").performClick()

        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(300_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "success"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNode(hasContentDescription("Route 1:", substring = true)).assertExists()
        assertTargetAtLeast48Dp("Route 1:")
    }

    private fun assertTargetAtLeast48Dp(description: String, requiresClick: Boolean = true) {
        val node = composeRule.onNode(hasContentDescription(description, substring = true))
        if (requiresClick) node.assertHasClickAction()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val minimum = 48f * composeRule.density.density
        assertTrue("$description width ${bounds.width} < $minimum", bounds.width >= minimum)
        assertTrue("$description height ${bounds.height} < $minimum", bounds.height >= minimum)
    }
}

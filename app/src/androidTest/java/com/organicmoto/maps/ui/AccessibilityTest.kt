package com.organicmoto.maps.ui

import android.Manifest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.RouteUiStateKey
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun idleControlsExposeLabelsAndReachableTargets() {
        val descriptions = listOf(
            "Ride complexity level 0",
            "Saved routes",
            "Route settings",
            "Voice guidance settings",
            "From",
            "To",
        )
        descriptions.forEach { description ->
            composeRule.onNodeWithContentDescription(description).assertExists()
        }
        assertTargetAtLeast48Dp("Ride complexity level 0", requiresClick = false)
        assertTargetAtLeast48Dp("Saved routes")
        assertTargetAtLeast48Dp("Route settings")
        assertTargetAtLeast48Dp("Voice guidance settings")
        val start = composeRule.onNodeWithText("START")
        start.assertHasClickAction()
        val minimum = 48f * composeRule.density.density
        val startBounds = start.fetchSemanticsNode().boundsInRoot
        assertTrue("START width ${startBounds.width} < $minimum", startBounds.width >= minimum)
        assertTrue("START height ${startBounds.height} < $minimum", startBounds.height >= minimum)
    }

    @Test
    fun voiceSettingsExposeAccessibleControlsAndOfflineStatus() {
        composeRule.onNodeWithContentDescription("Voice guidance settings", substring = true)
            .performClick()
        composeRule.onNodeWithText("Voice guidance").assertExists()
        composeRule.onNodeWithContentDescription("Spoken turn guidance").assertExists()
        composeRule.onNodeWithContentDescription("Announcement interval", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Preview next", substring = true).assertExists()
        composeRule.onNodeWithText("offline voice", substring = true, ignoreCase = true).assertExists()
        assertTargetAtLeast48Dp("Spoken turn guidance")
        assertTargetAtLeast48Dp("Announcement interval", requiresClick = false)
        assertTargetAtLeast48Dp("Preview next", requiresClick = false)
    }

    @Test
    fun darkRideMapToggleHasAnAccessibleTargetDuringGuidance() {
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(300_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "success"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("RIDE").performClick()
        composeRule.onNodeWithContentDescription("Dark ride map", substring = true).assertExists()
        assertTargetAtLeast48Dp("Dark ride map")
    }

    @Test
    fun settingsAndRouteCardsExposeLabels() {
        composeRule.onNodeWithContentDescription("Route settings").performClick()
        // The dialog must expose its road-share control, but the exact percent
        // is persisted across runs — match the label shape, not a literal value.
        val roadShareLabels = composeRule.onAllNodes(
            hasContentDescription("Maximum shared roads", substring = true),
        ).fetchSemanticsNodes()
        assertTrue(
            "expected a 'Maximum shared roads N percent' content description",
            roadShareLabels.any { node ->
                node.config.getOrElse(
                    androidx.compose.ui.semantics.SemanticsProperties.ContentDescription,
                ) { emptyList() }.any { it.matches(Regex("Maximum shared roads \\d+ percent")) }
            },
        )
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

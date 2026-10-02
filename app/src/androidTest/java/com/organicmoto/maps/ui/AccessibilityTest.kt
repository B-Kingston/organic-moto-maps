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

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    @Test
    fun idleControlsExposeLabelsAndReachableTargets() {
        val descriptions = listOf(
            "Ride complexity level 0",
            "Saved routes",
            "Planning settings",
            "From",
            "To",
            "Use current location",
        )
        descriptions.forEach { description ->
            composeRule.onNodeWithContentDescription(description).assertExists()
        }
        assertTargetAtLeast48Dp("Ride complexity level 0", requiresClick = false)
        assertTargetAtLeast48Dp("Saved routes")
        assertTargetAtLeast48Dp("Planning settings")
        assertTargetAtLeast48Dp("Use current location")
        composeRule.onNodeWithText("Route settings").assertDoesNotExist()
        composeRule.onNodeWithText("Sound settings").assertDoesNotExist()
        val start = composeRule.onNodeWithText("START")
        start.assertHasClickAction()
        val minimum = 48f * composeRule.density.density
        val startBounds = start.fetchSemanticsNode().boundsInRoot
        assertTrue("START width ${startBounds.width} < $minimum", startBounds.width >= minimum)
        assertTrue("START height ${startBounds.height} < $minimum", startBounds.height >= minimum)
    }

    @Test
    fun voiceSettingsExposeAccessibleControlsAndOfflineStatus() {
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        val soundSettings = composeRule.onNodeWithText("Sound settings")
        soundSettings.assertExists()
        val stateDescription = soundSettings.fetchSemanticsNode().config[
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription
        ]
        assertTrue(
            "Sound settings should announce offline voice readiness",
            stateDescription.contains("offline voice", ignoreCase = true) || stateDescription == "Disabled",
        )
        soundSettings.performClick()
        composeRule.onNodeWithText("Voice guidance").assertExists()
        composeRule.onNodeWithContentDescription("Spoken turn guidance").assertExists()
        composeRule.onNodeWithContentDescription("Announcement interval", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Preview next", substring = true).assertExists()
        composeRule.onNodeWithText("offline voice", substring = true, ignoreCase = true).assertExists()
        assertTargetAtLeast48Dp("Spoken turn guidance")
        assertTargetAtLeast48Dp("Announcement interval", requiresClick = false)
        assertTargetAtLeast48Dp("Preview next", requiresClick = false)
        // The voice screen is also exited with the shared back button.
        composeRule.onNodeWithContentDescription("Back").assertExists()
        assertTargetAtLeast48Dp("Back")
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Spoken turn guidance").assertDoesNotExist()
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
        assertTargetAtLeastDp("Dark ride map", 64f)
        assertTargetAtLeastDp("Voice guidance settings", 64f)
        composeRule.onNodeWithContentDescription("Ride settings").assertExists()
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Rider lock on; tap to release"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        assertTargetAtLeast48Dp("Rider lock on; tap to release")
        composeRule.onNodeWithContentDescription("Rider lock on; tap to release").performClick()
        composeRule.onNodeWithContentDescription("Rider lock off; tap to follow").assertExists()
        assertTargetAtLeast48Dp("Rider lock off; tap to follow")
    }

    @Test
    fun mediaControlsHaveAccessibleTargetsDuringGuidance() {
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(300_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "success"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("RIDE").performClick()
        // Glove-sized targets: 64 dp for every control, 80 dp for play/pause.
        assertTargetAtLeastDp("Media controls", 64f)
        assertTargetAtLeastDp("End navigation", 64f)
        composeRule.onNodeWithContentDescription("Media controls").performClick()
        assertTargetAtLeastDp("Close media controls", 64f)
        assertTargetAtLeastDp("Previous track", 64f)
        assertTargetAtLeastDp("Next track", 64f)
        // The unknown-state panel (no player here) offers both directions.
        listOf("Play", "Pause").forEach { direction ->
            val nodes = composeRule.onAllNodes(
                hasContentDescription(direction),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            assertTrue("missing $direction control", nodes.isNotEmpty())
            val minimum = 80f * composeRule.density.density
            nodes.forEach { node ->
                assertTrue(
                    "$direction target ${node.boundsInRoot.width}x${node.boundsInRoot.height} < $minimum",
                    node.boundsInRoot.width >= minimum && node.boundsInRoot.height >= minimum,
                )
            }
        }
        composeRule.onNodeWithText("END").assertExists()
        composeRule.onNodeWithContentDescription("Close media controls").performClick()
    }

    @Test
    fun settingsAndRouteCardsExposeLabels() {
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Route settings").performClick()
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
        // Every screen opened from the settings cog is exited with Back.
        composeRule.onNodeWithContentDescription("Back").assertExists()
        assertTargetAtLeast48Dp("Back")
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onNodeWithText("Target maximum shared roads").assertDoesNotExist()

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
        assertTargetAtLeastDp(description, 48f, requiresClick)
    }

    private fun assertTargetAtLeastDp(
        description: String,
        dp: Float,
        requiresClick: Boolean = true,
    ) {
        val node = composeRule.onNode(hasContentDescription(description, substring = true))
        if (requiresClick) node.assertHasClickAction()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val minimum = dp * composeRule.density.density
        assertTrue("$description width ${bounds.width} < $minimum", bounds.width >= minimum)
        assertTrue("$description height ${bounds.height} < $minimum", bounds.height >= minimum)
    }
}

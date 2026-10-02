package com.organicmoto.maps.ui

import android.Manifest
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The open media control center and the ride data bar must read as one
 * continuous surface: full-bleed on both sides with no step, and fused at the
 * seam rather than merely adjacent. The JVM
 * [com.organicmoto.maps.media.MediaPanelJoinTest] pins the constant
 * relationships; this suite proves the real laid-out geometry on screen.
 */
@RunWith(AndroidJUnit4::class)
class MediaPanelJoinTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    private fun openPanelOverTheBar() {
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(120_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.SemanticsMatcher.expectValue(
                    com.organicmoto.maps.RouteUiStateKey,
                    "success",
                ),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("RIDE").performClick()
        composeRule.onNodeWithContentDescription("Media controls").performClick()
        composeRule.waitForIdle()
    }

    private fun bounds(description: String, substring: Boolean = false) =
        composeRule.onNodeWithContentDescription(description, substring = substring)
            .fetchSemanticsNode().boundsInRoot

    @Test
    fun thePanelAndTheBarShareTheScreensFullWidth() {
        openPanelOverTheBar()
        val panel = bounds("Media control center")
        val bar = bounds("Ride data:", substring = true)
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val epsilon = composeRule.density.density
        assertTrue("bar must cover the navigation inset", bar.bottom >= root.bottom - epsilon)
        // Any inset on either side shows as a step at the join, which is what
        // made the panel read as a separate card.
        assertTrue(
            "panel left ${panel.left} vs bar left ${bar.left}",
            kotlin.math.abs(panel.left - bar.left) <= epsilon,
        )
        assertTrue(
            "panel right ${panel.right} vs bar right ${bar.right}",
            kotlin.math.abs(panel.right - bar.right) <= epsilon,
        )
        // And both reach the actual screen edges.
        assertTrue(
            "panel left ${panel.left} leaves the screen ${root.left}",
            panel.left <= root.left + epsilon,
        )
        assertTrue(
            "panel right ${panel.right} leaves the screen ${root.right}",
            panel.right >= root.right - epsilon,
        )
    }

    @Test
    fun thePanelOverlapsTheBarRatherThanStoppingAboveIt() {
        openPanelOverTheBar()
        val panel = bounds("Media control center")
        val bar = bounds("Ride data:", substring = true)
        val epsilon = composeRule.density.density
        // The panel reaches down into the bar's empty top padding, so the two
        // surfaces overlap instead of meeting at a possibly-antialiased seam.
        assertTrue(
            "panel bottom ${panel.bottom} stops short of the bar top ${bar.top}",
            panel.bottom > bar.top + epsilon,
        )
        // ...but only into that padding: never onto a metric or a control.
        val opener = bounds("Media controls")
        val end = bounds("End navigation")
        assertTrue(
            "the fused edge covers the media opener: panel ${panel.bottom} vs ${opener.top}",
            panel.bottom <= opener.top + epsilon,
        )
        assertTrue(
            "the fused edge covers END: panel ${panel.bottom} vs ${end.top}",
            panel.bottom <= end.top + epsilon,
        )
        // END and the opener both stay live under the fused edge.
        composeRule.onNodeWithText("END").assertExists()
        composeRule.onNodeWithContentDescription("Media controls").assertExists()
    }

    @Test
    fun inactivityTimeoutClosesThePanelWithoutEndingTheRideAndReopeningStartsFresh() {
        openPanelOverTheBar()
        Thread.sleep(7_000)
        composeRule.onNodeWithContentDescription("Media control center").performTouchInput {
            val tap = Offset(visibleSize.width / 2f, 2f)
            down(tap)
            up()
        }
        Thread.sleep(8_500)
        composeRule.onNodeWithContentDescription("Media control center").assertExists()
        composeRule.waitUntil(8_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Media control center"),
            ).fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("END").assertExists()
        composeRule.onNodeWithContentDescription("Media controls").performClick()
        val inactivityTimer =
            composeRule.onNodeWithContentDescription("Media controls inactivity timer")
                .fetchSemanticsNode().config[
                    androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo
                ]
        assertTrue("fresh opening should restart the inactivity timer", inactivityTimer.current > 0.9f)
        composeRule.onNodeWithContentDescription("Close media controls").performClick()
    }

    @Test
    fun closingThePanelRestoresTheBarsOwnRoundedSilhouette() {
        openPanelOverTheBar()
        composeRule.onNodeWithContentDescription("Close media controls").performClick()
        composeRule.waitForIdle()
        val bar = bounds("Ride data:", substring = true)
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val epsilon = composeRule.density.density
        // Closed, the bar is a standalone rounded surface again, and the panel
        // is gone rather than left fused to it.
        assertTrue(
            "closed bar left ${bar.left} vs screen ${root.left}",
            kotlin.math.abs(bar.left - root.left) <= epsilon,
        )
        assertTrue(
            "the panel must be gone when closed",
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Media control center"),
            ).fetchSemanticsNodes().isEmpty(),
        )
    }
}

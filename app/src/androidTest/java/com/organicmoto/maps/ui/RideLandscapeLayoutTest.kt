package com.organicmoto.maps.ui

import android.Manifest
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Landscape ride layout: the media panel is bounded between the directions HUD
 * and the measured data bar, so it can never cover the next-turn guidance or
 * the END button; the panel content scrolls inside that bound.
 */
@RunWith(AndroidJUnit4::class)
class RideLandscapeLayoutTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    @After
    fun restoreOrientation() {
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun routeAndRide() {
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
        composeRule.onNodeWithText("END").assertIsDisplayed()
    }

    @Test
    fun landscapePanelStaysBetweenDirectionsHudAndDataBar() {
        // Rotate before starting the ride: the activity is recreated on the
        // configuration change, and ride state is intentionally transient.
        composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        composeRule.waitUntil(20_000) {
            val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
            root.width > root.height
        }
        routeAndRide()

        composeRule.onNodeWithContentDescription("Media controls").performClick()
        composeRule.onNodeWithContentDescription("Media control center").assertExists()
        composeRule.waitForIdle()

        val panel = composeRule.onNodeWithContentDescription("Media control center")
            .fetchSemanticsNode().boundsInRoot
        val end = composeRule.onNodeWithContentDescription("End navigation")
            .fetchSemanticsNode().boundsInRoot
        val hud = composeRule.onAllNodes(
            hasContentDescription("Navigation guidance:", substring = true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().first().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot

        assertTrue(
            "panel bottom ${panel.bottom} covers END top ${end.top}",
            panel.bottom <= end.top + 1f,
        )
        assertTrue(
            "panel top ${panel.top} covers the directions HUD bottom ${hud.bottom}",
            panel.top >= hud.bottom - 1f,
        )
        assertTrue("panel left ${panel.left} leaves the screen", panel.left >= -1f)
        assertTrue(
            "panel right ${panel.right} leaves the screen",
            panel.right <= root.right + 1f,
        )
        // END stays tappable while the panel is open, and closing restores the map.
        composeRule.onNodeWithText("END").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close media controls").performClick()
    }
}

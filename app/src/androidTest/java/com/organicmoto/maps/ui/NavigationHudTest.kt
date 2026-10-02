package com.organicmoto.maps.ui

import android.Manifest
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.RideSurfaceColor
import kotlin.math.abs
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

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

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
    fun rideModeActionAndZoomPillsShareVerticalAxis() {
        routeSomethingValid()
        composeRule.onNodeWithText("RIDE").performClick()

        val zoomIn = composeRule.onNodeWithContentDescription("Zoom in")
        val zoomCenterX = zoomIn.fetchSemanticsNode().boundsInRoot.center.x
        val zoomIconPixel = zoomIn.captureToImage().toPixelMap().let { pixels ->
            pixels[pixels.width / 2, pixels.height / 2]
        }
        org.junit.Assert.assertEquals("ride zoom glyph color", RideSurfaceColor, zoomIconPixel)
        listOf("Voice guidance settings", "Dark ride map").forEach { description ->
            val centerX = composeRule.onNodeWithContentDescription(description, substring = true)
                .fetchSemanticsNode().boundsInRoot.center.x
            org.junit.Assert.assertTrue(
                "$description and the zoom pill should share a vertical axis: $centerX vs $zoomCenterX",
                abs(centerX - zoomCenterX) <= composeRule.density.density,
            )
        }
        composeRule.onNodeWithText("END").performClick()
    }

    @Test
    fun rideCameraLockCanReleaseAndRelock() {
        routeSomethingValid()
        composeRule.onNodeWithText("RIDE").performClick()
        composeRule.onNodeWithContentDescription("Rider lock on; tap to release").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Rider lock on; tap to release").performClick()
        composeRule.onNodeWithContentDescription("Rider lock off; tap to follow").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Rider lock off; tap to follow").performClick()
        composeRule.onNodeWithContentDescription("Rider lock on; tap to release").assertIsDisplayed()
        composeRule.onNodeWithText("END").assertIsDisplayed()
        composeRule.onNodeWithText("END").performClick()
        composeRule.onNodeWithText("START").assertIsDisplayed()
    }


    @Test
    fun mediaControlsOpenAboveTheDataBarAndKeepEndReachable() {
        routeSomethingValid()
        composeRule.onNodeWithText("RIDE").performClick()
        composeRule.onNodeWithText("END").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Media controls").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Media control center").assertExists()
        // END stays reachable while the panel is open, above the bar.
        composeRule.onNodeWithText("END").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Close media controls").performClick()
        org.junit.Assert.assertTrue(
            "media panel must close",
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Media control center"),
            ).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun systemBackClosesTheMediaPanelInsteadOfLeavingTheApp() {
        routeSomethingValid()
        composeRule.onNodeWithText("RIDE").performClick()
        composeRule.onNodeWithContentDescription("Media controls").performClick()
        composeRule.onNodeWithContentDescription("Media control center").assertExists()

        androidx.test.espresso.Espresso.pressBack()

        org.junit.Assert.assertTrue(
            "system Back must dismiss the media panel",
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Media control center"),
            ).fetchSemanticsNodes().isEmpty(),
        )
        // The ride is still on screen: Back did not leave the screen or the app.
        composeRule.onNodeWithText("END").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Media controls").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Media controls").performClick()
        composeRule.onNodeWithContentDescription("Media control center").assertExists()
        composeRule.onNodeWithContentDescription("Close media controls").performClick()
    }

    @Test
    fun dataBarControlsMeetGloveMinimumTargets() {
        routeSomethingValid()
        composeRule.onNodeWithText("RIDE").performClick()
        composeRule.onNodeWithText("END").assertIsDisplayed()
        val glove = 64f * composeRule.density.density
        listOf("Media controls", "End navigation").forEach { description ->
            val target = composeRule.onNodeWithContentDescription(description)
                .fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(
                "$description too small for gloves: ${target.width}x${target.height}",
                target.width >= glove && target.height >= glove,
            )
        }
    }

    @Test
    fun mediaPanelControlsMeetGloveMinimumTargetsOnDevice() {
        routeSomethingValid()
        composeRule.onNodeWithText("RIDE").performClick()
        composeRule.onNodeWithContentDescription("Media controls").performClick()
        composeRule.onNodeWithContentDescription("Media control center").assertExists()
        val glove = 64f * composeRule.density.density
        val preferred = 80f * composeRule.density.density
        listOf("Close media controls", "Previous track", "Next track").forEach { description ->
            val target = composeRule.onNodeWithContentDescription(description)
                .fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(
                "$description too small: ${target.width}x${target.height}",
                target.width >= glove && target.height >= glove,
            )
        }
        // The state is either known (one explicit toggle) or unknown (both
        // directions); either way every present play/pause target is 80 dp.
        val playPauseTargets = listOf("Play", "Pause").flatMap { description ->
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription(description),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().map { description to it.boundsInRoot }
        }
        org.junit.Assert.assertTrue("no play/pause target found", playPauseTargets.isNotEmpty())
        playPauseTargets.forEach { (description, target) ->
            org.junit.Assert.assertTrue(
                "$description too small: ${target.width}x${target.height}",
                target.width >= preferred && target.height >= preferred,
            )
        }
    }

    @Test
    fun voiceSettingsRemainReachableDuringGuidance() {
        routeSomethingValid()
        composeRule.onNodeWithText("RIDE").performClick()
        // The ride HUD keeps its independent speaker shortcut; this is not the
        // planner's consolidated settings menu.
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
        try {
            // MapLibre finishes the local style swap asynchronously; on a busy
            // emulator the dark ride style can take a while to become current,
            // so this waits generously while still requiring the exact state.
            composeRule.waitUntil(90_000) {
                composeRule.onAllNodes(
                    androidx.compose.ui.test.SemanticsMatcher.expectValue(
                        com.organicmoto.maps.DarkGuidanceStyleReadyKey,
                        true,
                    ),
                ).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            // Report which precondition is missing: no installed map, the
            // toggle state, or MapLibre never finishing the style load.
            fun flag(key: androidx.compose.ui.semantics.SemanticsPropertyKey<Boolean>): Boolean =
                composeRule.onAllNodes(
                    androidx.compose.ui.test.SemanticsMatcher.expectValue(key, true),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
            throw AssertionError(
                "dark guidance style never became ready: " +
                    "mapReady=${flag(com.organicmoto.maps.MapReadyKey)} " +
                    "mapInstalled=${flag(com.organicmoto.maps.MapInstalledKey)} " +
                    "darkRideMapEnabled=${flag(com.organicmoto.maps.DarkRideMapEnabledKey)} " +
                    "guidanceActive=${flag(com.organicmoto.maps.GuidanceActiveKey)}",
                timeout,
            )
        }
    }
}

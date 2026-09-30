package com.organicmoto.maps.ui

import android.Manifest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.CAROUSEL_SLOT_HEIGHT
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.MapRouteCountKey
import com.organicmoto.maps.RouteUiStateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerPanelTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun startIsReachableAndEnabledWhileIdle() {
        val start = composeRule.onNodeWithText("START")
        start.assertExists()
        start.assertIsEnabled()
        start.assertHasClickAction()
        val bounds = start.fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= root.left)
        assertTrue(bounds.right <= root.right)
        assertTrue(bounds.top >= root.top)
        assertTrue(bounds.bottom <= root.bottom)
    }

    @Test
    fun planningActionsFormAReachableVerticalPill() {
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val import = composeRule.onNodeWithContentDescription("Import GPX route")
            .fetchSemanticsNode().boundsInRoot
        val settings = composeRule.onNodeWithContentDescription("Route settings")
            .fetchSemanticsNode().boundsInRoot
        val voiceSettings = composeRule.onNodeWithContentDescription("Voice guidance settings")
            .fetchSemanticsNode().boundsInRoot
        val saved = composeRule.onNodeWithContentDescription("Saved routes")
            .fetchSemanticsNode().boundsInRoot
        val zoomIn = composeRule.onNodeWithContentDescription("Zoom in")
            .fetchSemanticsNode().boundsInRoot

        assertTrue(import.top < settings.top)
        assertTrue(settings.top < voiceSettings.top)
        assertTrue(voiceSettings.top < saved.top)
        assertTrue(import.left >= root.center.x)
        assertEquals(import.width, zoomIn.width, 1f)
        assertEquals(import.right, zoomIn.right, 1f)
        assertTrue(saved.right <= root.right)
        assertTrue(saved.bottom <= root.bottom)
    }

    @Test
    fun invalidInputShowsErrorAndReenablesStart() {
        composeRule.onNodeWithContentDescription("From").performTextInput("not a real place")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "error"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("START").assertIsEnabled()
        composeRule.onNodeWithText("No match", substring = true).assertExists()
    }
    @Test
    fun rapidEmptySubmissionsNeverLeaveStartLoading() {
        repeat(5) { composeRule.onNodeWithText("START").performClick() }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "error"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("START").assertIsEnabled()
        composeRule.onNode(SemanticsMatcher.expectValue(RouteUiStateKey, "loading")).assertDoesNotExist()
    }

    @Test(timeout = 300_000)
    fun validCoordinatesKeepPanelFrameFixedAcrossRouting() {
        // Routeless frame contract: the panel is bottom-anchored, so START
        // and the From/To rows sit at the same spot in every state that has
        // no route candidates (idle, typing, loading, error).
        val idleBounds = composeRule.onNodeWithText("START").fetchSemanticsNode().boundsInRoot
        val idleFromTop = composeRule.onNodeWithContentDescription("From")
            .fetchSemanticsNode().boundsInRoot.top
        val idleMapBounds = composeRule.onNode(SemanticsMatcher.keyIsDefined(MapRouteCountKey))
            .fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        var observedSuccess = false
        composeRule.waitUntil(300_000) {
            val states = composeRule.onAllNodes(
                SemanticsMatcher.keyIsDefined(RouteUiStateKey),
            ).fetchSemanticsNodes()
            val success = states.any {
                it.config[RouteUiStateKey] == "success"
            }
            observedSuccess = observedSuccess || success
            observedSuccess
        }
        // The waitUntil above can only exit when a success frame is observed,
        // so asserting anything weaker here would be unreachable-false.
        assertTrue(observedSuccess)
        // Success swaps the frame: the carousel bar (one card per candidate)
        // appears above the fields. The panel grows upward only: START and
        // the From/To rows never move, and the panel's top edge — the map
        // viewport's bottom — rises by exactly the carousel slot height.
        val carouselPx = with(composeRule.density) { CAROUSEL_SLOT_HEIGHT.toPx() }
        val successBounds = composeRule.onNodeWithText("RIDE").fetchSemanticsNode().boundsInRoot
        val fromBounds = composeRule.onNodeWithContentDescription("From")
            .fetchSemanticsNode().boundsInRoot
        val successMapBounds = composeRule.onNode(SemanticsMatcher.keyIsDefined(MapRouteCountKey))
            .fetchSemanticsNode().boundsInRoot
        assertEquals(idleBounds, successBounds)
        assertEquals(idleFromTop, fromBounds.top, 1f)
        assertEquals(carouselPx, idleMapBounds.bottom - successMapBounds.bottom, 1f)
    }
}

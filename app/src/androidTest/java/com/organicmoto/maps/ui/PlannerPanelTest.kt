package com.organicmoto.maps.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.RouteUiStateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerPanelTest {

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
        val idleBounds = composeRule.onNodeWithText("START").fetchSemanticsNode().boundsInRoot
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
        val successBounds = composeRule.onNodeWithText("START").fetchSemanticsNode().boundsInRoot
        assertEquals(idleBounds.left, successBounds.left, 1f)
        assertEquals(idleBounds.right, successBounds.right, 1f)
        assertEquals(idleBounds.top, successBounds.top, 1f)
        assertEquals(idleBounds.bottom, successBounds.bottom, 1f)
    }
}

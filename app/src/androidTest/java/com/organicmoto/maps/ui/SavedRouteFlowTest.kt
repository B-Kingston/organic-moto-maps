package com.organicmoto.maps.ui

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.storage.SAVED_ROUTES_DB_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SavedRouteFlowTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSavedRoutes() {
        composeRule.activity.deleteDatabase(SAVED_ROUTES_DB_NAME)
    }

    @Test(timeout = 300_000)
    fun saveCommentDeleteAndReloadPreservesPlanningState() {
        val from = "-27.4698,153.0251"
        val to = "-27.3353,152.7720"
        enterCoordinates(from, to)
        composeRule.onNodeWithText("START").performClick()
        UiTestWaits.waitForState(composeRule, "success")

        composeRule.onNode(hasContentDescription("Route 1:", substring = true))
            .performTouchInput { longClick() }
        composeRule.onNodeWithContentDescription("Save route").performClick()
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasText("Saved")).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Saved routes").performClick()
        waitForRouteRow(from, to)
        composeRule.onNodeWithContentDescription(
            "Comments for saved route: $from to $to",
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasText("Add a comment")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Add a comment").performTextInput("test note")
        composeRule.onNodeWithContentDescription("Add comment").performClick()
        composeRule.onNodeWithText("test note").assertExists()

        composeRule.onNodeWithContentDescription("Delete saved route").performClick()
        composeRule.onNodeWithText("Delete saved route?").assertExists()
        composeRule.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(
                hasContentDescription("Saved route: $from to $to"),
            ).fetchSemanticsNodes().isEmpty()
        }

        closeSheet()
        saveCurrentRouteAgain()
        composeRule.onNodeWithContentDescription("Saved routes").performClick()
        waitForRouteRow(from, to)
        composeRule.onNodeWithContentDescription(
            "Saved route: $from to $to",
            useUnmergedTree = true,
        ).performClick()
        UiTestWaits.waitForState(composeRule, "success")
        assertEquals(from, fieldText("From"))
        assertEquals(to, fieldText("To"))
        composeRule.onNodeWithContentDescription("Ride complexity level 0").assertExists()
        composeRule.onNode(hasContentDescription("Route 1:", substring = true)).assertExists()
        assertTrue(composeRule.onAllNodes(hasText("min", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    private fun enterCoordinates(from: String, to: String) {
        composeRule.onNodeWithContentDescription("From").performTextInput(from)
        composeRule.onNodeWithContentDescription("To").performTextInput(to)
    }

    private fun saveCurrentRouteAgain() {
        composeRule.onNode(hasContentDescription("Route 1:", substring = true))
            .performTouchInput { longClick() }
        composeRule.onNodeWithContentDescription("Save route").performClick()
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasText("Saved")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForRouteRow(from: String, to: String) {
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(
                hasContentDescription("Saved route: $from to $to"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun closeSheet() {
        composeRule.onNodeWithContentDescription("Close saved routes").performClick()
        composeRule.waitForIdle()
    }

    private fun fieldText(label: String): String =
        composeRule.onNodeWithContentDescription(label)
            .fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText]
            .text

}

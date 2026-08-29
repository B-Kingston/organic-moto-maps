package com.organicmoto.maps.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.organicmoto.maps.SavedRoutesSheet
import com.organicmoto.maps.storage.GeoPoint
import com.organicmoto.maps.storage.SavedRoute
import com.organicmoto.maps.storage.SavedRouteComment
import com.organicmoto.maps.storage.SavedRouteSummary
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SavedRoutesSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun successfulCommentClearsDraftAndListsComment() {
        val comments = mutableListOf<SavedRouteComment>()
        composeSheet(
            commentsProvider = { comments.toList() },
            onAddComment = { routeId, text -> comments += SavedRouteComment(1L, routeId, text, 1L) },
        )
        openComments()
        composeRule.onNodeWithText("Add a comment").performTextInput("test note")
        composeRule.onNodeWithContentDescription("Add comment").performClick()
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasText("test note")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Add a comment").assertExists()
        // Prove the draft was really cleared: hint presence alone would also
        // match a node that kept its text.
        val draft = composeRule.onNodeWithText("Add a comment").fetchSemanticsNode()
        assertEquals("", draft.config[SemanticsProperties.EditableText].text)
    }

    @Test
    fun failedCommentKeepsDraftAndReenablesAddButton() {
        composeSheet(
            commentsProvider = { emptyList() },
            onAddComment = { _, _ -> error("comment failed") },
        )
        openComments()
        composeRule.onNodeWithText("Add a comment").performTextInput("keep draft")
        composeRule.onNodeWithContentDescription("Add comment").performClick()
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasText("keep draft")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Add comment").assertIsEnabled()
    }

    @Test
    fun commentInputIsCappedAtFiveHundredCharacters() {
        composeSheet(
            commentsProvider = { emptyList() },
            onAddComment = { _, _ -> Unit },
        )
        openComments()
        val text = "x".repeat(600)
        composeRule.onNodeWithText("Add a comment").performTextInput(text)
        val editable = composeRule.onNodeWithText("x".repeat(500)).fetchSemanticsNode()
        assertEquals(500, editable.config[SemanticsProperties.EditableText].text.length)
    }

    @Test
    fun deleteConfirmCallsProviderAndFailureLeavesSheetOpen() {
        var deleteCalls = 0
        var refreshes = 0
        composeRule.setContent {
            MaterialTheme {
                SavedRoutesSheet(
                    summariesProvider = {
                        refreshes++
                        listOf(summary())
                    },
                    commentsProvider = { emptyList() },
                    onAddComment = { _, _ -> Unit },
                    onLoadRoute = {},
                    onDeleteRoute = {
                        deleteCalls++
                        error("delete failed")
                    },
                    onDismiss = {},
                )
            }
        }
        waitForSummary()
        composeRule.onNodeWithContentDescription("Delete saved route").performClick()
        composeRule.onNodeWithText("Delete saved route?").assertExists()
        composeRule.onNodeWithText("Delete", useUnmergedTree = true).performClick()
        composeRule.waitUntil(2_000) { deleteCalls == 1 && refreshes >= 2 }
        composeRule.onNodeWithText("Saved routes").assertExists()
        assertTrue(refreshes >= 2)
    }

    private fun composeSheet(
        commentsProvider: suspend (Long) -> List<SavedRouteComment>,
        onAddComment: suspend (Long, String) -> Unit,
    ) {
        composeRule.setContent {
            MaterialTheme {
                SavedRoutesSheet(
                    summariesProvider = { listOf(summary()) },
                    commentsProvider = commentsProvider,
                    onAddComment = onAddComment,
                    onLoadRoute = {},
                    onDeleteRoute = {},
                    onDismiss = {},
                )
            }
        }
        waitForSummary()
    }

    private fun openComments() {
        composeRule.onNodeWithContentDescription(
            "Comments for saved route: Brisbane to Toowoomba",
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasText("Add a comment")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForSummary() {
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasText("Brisbane")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun summary() = SavedRouteSummary(
        route = SavedRoute(
            id = 1L,
            fromName = "Brisbane",
            fromLat = -27.0,
            fromLon = 153.0,
            toName = "Toowoomba",
            toLat = -27.5,
            toLon = 151.9,
            distanceMeters = 100_000.0,
            durationMillis = 3_600_000L,
            complexity = 0f,
            maxRoadSharePercent = 70f,
            blockUnpaved = false,
            geometry = "_p~iF~ps|U_ulLnnqC",
            createdAtMillis = 1L,
        ),
        commentCount = 0,
    )
}

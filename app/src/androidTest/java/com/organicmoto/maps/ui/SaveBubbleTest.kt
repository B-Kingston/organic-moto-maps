package com.organicmoto.maps.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.organicmoto.maps.CONFIRM_LINGER_MS
import com.organicmoto.maps.SaveRouteBubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SaveBubbleTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun successfulSaveShowsConfirmationThenDismisses() {
        var visible by mutableStateOf(true)
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                SaveRouteBubble(
                    visible = visible,
                    metrics = "1 h · 45 km",
                    onSave = { true },
                    onDismiss = {
                        dismissed = true
                        visible = false
                    },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Save route").performClick()
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodes(hasText("Saved")).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.mainClock.advanceTimeBy(CONFIRM_LINGER_MS)
        composeRule.waitForIdle()
        assertTrue(dismissed)
        composeRule.onNodeWithText("Saved").assertDoesNotExist()
    }

    @Test
    fun failedSaveStaysOpenAndRetries() {
        var attempts = 0
        composeRule.setContent {
            MaterialTheme {
                SaveRouteBubble(
                    visible = true,
                    metrics = "1 h · 45 km",
                    onSave = {
                        attempts++
                        false
                    },
                    onDismiss = {},
                )
            }
        }
        repeat(2) {
            composeRule.onNodeWithContentDescription("Save route").performClick()
            composeRule.waitUntil(2_000) {
                composeRule.onAllNodes(hasText("Couldn't save this route")).fetchSemanticsNodes().isNotEmpty()
            }
        }
        assertEquals(2, attempts)
        composeRule.onNodeWithText("Couldn't save this route").assertExists()
        composeRule.onNodeWithContentDescription("Save route").assertExists()
    }
}

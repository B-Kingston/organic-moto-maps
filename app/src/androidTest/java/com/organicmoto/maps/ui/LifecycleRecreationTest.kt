package com.organicmoto.maps.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.RouteUiStateKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifecycleRecreationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test(timeout = 300_000)
    fun editableFieldsSurviveLoadingButRouteStateResets() {
        enterCoordinates()
        composeRule.onNodeWithText("START").performClick()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        assertEquals("-27.4698,153.0251", fieldText("From"))
        assertEquals("-27.3353,152.7720", fieldText("To"))
        waitForState("idle")
        composeRule.onNodeWithText("START").assertIsEnabled()
    }

    @Test(timeout = 300_000)
    fun fieldsAndComplexitySurviveSuccessRecreation() {
        enterCoordinates()
        composeRule.onNodeWithText("START").performClick()
        waitForState("success")
        dragOneClockwiseClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Ride complexity level 1"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        assertEquals("-27.4698,153.0251", fieldText("From"))
        assertEquals("-27.3353,152.7720", fieldText("To"))
        waitForState("idle")
        composeRule.onNodeWithContentDescription("Ride complexity level 1").assertExists()
    }

    @Test
    fun openSheetAndDialogDoNotSurviveRecreation() {
        composeRule.onNodeWithContentDescription("Saved routes").performClick()
        composeRule.onNodeWithText("Saved routes").assertExists()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Saved routes").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Route settings").performClick()
        composeRule.onNodeWithText("Route settings").assertExists()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Route settings").assertDoesNotExist()
    }

    private fun enterCoordinates() {
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
    }

    private fun fieldText(label: String): String =
        composeRule.onNodeWithContentDescription(label)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    private fun waitForState(state: String) {
        composeRule.waitUntil(300_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, state))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun dragOneClockwiseClick() {
        val knob = composeRule.onNode(
            androidx.compose.ui.test.hasContentDescription("Ride complexity level", substring = true),
        )
        val bounds = knob.fetchSemanticsNode().boundsInRoot
        knob.performTouchInput {
            val center = androidx.compose.ui.geometry.Offset(bounds.width / 2f, bounds.height / 2f)
            val radius = bounds.width.coerceAtMost(bounds.height) * 0.45f
            val startAngle = Math.PI / 2.0
            down(center + androidx.compose.ui.geometry.Offset(
                (radius * kotlin.math.cos(startAngle)).toFloat(),
                (radius * kotlin.math.sin(startAngle)).toFloat(),
            ))
            advanceEventTime(20)
            repeat(8) { step ->
                val angle = startAngle + (step + 1) * Math.PI / 32.0
                moveTo(center + androidx.compose.ui.geometry.Offset(
                    (radius * kotlin.math.cos(angle)).toFloat(),
                    (radius * kotlin.math.sin(angle)).toFloat(),
                ))
                advanceEventTime(20)
            }
            up()
        }
    }
}

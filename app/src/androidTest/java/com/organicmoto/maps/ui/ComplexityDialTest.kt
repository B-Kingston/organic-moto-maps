package com.organicmoto.maps.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.RouteGenerationKey
import com.organicmoto.maps.RouteUiStateKey
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class ComplexityDialTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test(timeout = 300_000)
    fun dialStopsAtZeroCountsClicksAndSurvivesRecreation() {
        dragClicks(1)
        waitForLevel(1)
        dragClicks(-1)
        waitForLevel(0)
        dragClicks(17)
        waitForLevel(17)
        composeRule.activityRule.scenario.recreate()
        waitForLevel(17)
    }

    @Test(timeout = 300_000)
    fun releasingDialAfterSuccessStartsANewGeneration() {
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        waitForState("success")
        val generation = composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteGenerationKey))
            .fetchSemanticsNode()
            .config[RouteGenerationKey]
        dragClicks(1)
        waitForLevel(1)
        waitForState("success")
        val nextGeneration = composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteGenerationKey))
            .fetchSemanticsNode()
            .config[RouteGenerationKey]
        assertTrue(nextGeneration > generation)
    }

    private fun dragClicks(clicks: Int) {
        val direction = if (clicks >= 0) 1 else -1
        repeat(kotlin.math.abs(clicks)) {
            val knob = composeRule.onNode(hasContentDescription("Ride complexity level", substring = true))
            val bounds = knob.fetchSemanticsNode().boundsInRoot
            knob.performTouchInput {
                val center = Offset(bounds.width / 2f, bounds.height / 2f)
                val radius = bounds.width.coerceAtMost(bounds.height) * 0.45f
                val startAngle = Math.PI / 2.0
                down(center + polar(radius, startAngle))
                advanceEventTime(20)
                repeat(8) { step ->
                    val angle = startAngle + direction * (step + 1) * Math.PI / 32.0
                    moveTo(center + polar(radius, angle))
                    advanceEventTime(20)
                }
                up()
            }
            composeRule.waitForIdle()
        }
    }

    private fun waitForLevel(level: Int) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(
                hasContentDescription("Ride complexity level $level"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Ride complexity level $level").assertExists()
    }

    private fun waitForState(state: String) {
        composeRule.waitUntil(300_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, state))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun polar(radius: Float, angle: Double): Offset =
        Offset((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())
}

package com.organicmoto.maps.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.RouteGenerationKey
import com.organicmoto.maps.RouteUiStateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.cos
import kotlin.math.sin

/**
 * The road-share Apply path had no dedicated coverage: the dialog existence
 * was asserted in AccessibilityTest, but nothing proved that APPLY persists
 * the percentage into route_preferences and triggers a re-route when a plan
 * is active. This exercises the whole loop on real storage.
 *
 * The target share is computed relative to whatever is currently persisted so
 * the test stays deterministic across emulator restarts — route_preferences
 * survives both the process and the suite itself.
 */
@RunWith(AndroidJUnit4::class)
class RoadShareApplyTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test(timeout = 300_000)
    fun applyingRoadSharePersistsPreferenceAndReroutes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Mirrors ROAD_SHARE_PREF / BLOCK_UNPAVED_PREF in RouteScreen.kt
        // (they stay private there because production reads its own copy).
        val preferences = context.getSharedPreferences("route_preferences", android.content.Context.MODE_PRIVATE)
        val startGeneration = composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteGenerationKey))
            .fetchSemanticsNode()
            .config[RouteGenerationKey]

        // Reach success once so activePlan exists and Apply really re-routes.
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        UiTestWaits.waitForState(composeRule, "success")

        composeRule.onNodeWithContentDescription("Route settings").performClick()
        composeRule.onNodeWithText("Route settings", substring = true).assertExists()

        val currentPercent = readDialogPercent()
        val target = if (currentPercent <= 50) currentPercent + 10 else currentPercent - 10
        assertTrue(target in 10..90)

        dragKnobToPercent(currentPercent, target)
        composeRule.waitUntil(5_000) {
            readDialogPercent() == target
        }
        assertEquals(target, readDialogPercent())

        composeRule.onNodeWithText("APPLY").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(hasText("Route settings")).fetchSemanticsNodes().isEmpty()
        }

        // 1) Persisted exactly as applied (float equality against an integer snap).
        assertTrue(
            "route_preferences must contain max_road_share_percent=$target",
            preferences.contains("max_road_share_percent"),
        )
        assertEquals(
            target.toFloat(),
            preferences.getFloat("max_road_share_percent", -1f),
            0.01f,
        )
        assertTrue(
            "block_unpaved_roads must be explicitly persisted",
            preferences.contains("block_unpaved_roads"),
        )
        assertFalse(preferences.getBoolean("block_unpaved_roads", true))

        // 2) A live plan existed, so Apply must have re-routed (new generation).
        composeRule.waitUntil(300_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "success"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val nextGeneration = composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteGenerationKey))
            .fetchSemanticsNode()
            .config[RouteGenerationKey]
        assertTrue(nextGeneration > startGeneration)
    }

    private fun readDialogPercent(): Int {
        val node = composeRule.onNode(
            androidx.compose.ui.test.hasContentDescription(
                "Maximum shared roads",
                substring = true,
            ),
        ).fetchSemanticsNode()
        val description = node.config.getOrElse(
            androidx.compose.ui.semantics.SemanticsProperties.ContentDescription,
        ) { emptyList() }.first()
        // "Maximum shared roads 70 percent" → 70
        return description.substringAfter("roads ").substringBefore(" ").toInt()
    }

    /**
     * Drags the arc knob between angles derived from the RoadShareKnob math:
     * sweep = (percent - 10) / 80 * 270 measured clockwise from the bottom-left
     * stop (135°). Start slightly below the target angle so the drag crosses
     * Compose's touch slop and detectDragGestures actually engages; both points
     * sit inside the same 5% snap bracket, so onDragStart cannot jump past it.
     */
    private fun dragKnobToPercent(from: Int, to: Int) {
        val epsilonSnapDegrees = 3.0
        fun angleFor(percent: Int): Double =
            Math.toRadians(135.0 + (percent - 10) / 80.0 * 270.0)

        val livePercent = maxOf(minOf(from, 90), 10)
        val startAngle = Math.toRadians(135.0 + ((livePercent - 10) / 80.0 * 270.0) - epsilonSnapDegrees)
        val endAngle = angleFor(to)
        composeRule.onNode(
            androidx.compose.ui.test.hasContentDescription(
                "Maximum shared roads",
                substring = true,
            ),
        ).performTouchInput {
            val center = Offset(width / 2f, height / 2f)
            val radius = width.coerceAtMost(height) * 0.40f
            down(center + Offset((radius * cos(startAngle)).toFloat(), (radius * sin(startAngle)).toFloat()))
            advanceEventTime(30)
            moveTo(center + Offset((radius * cos(endAngle)).toFloat(), (radius * sin(endAngle)).toFloat()))
            advanceEventTime(60)
            up()
        }
        composeRule.waitForIdle()
    }

}

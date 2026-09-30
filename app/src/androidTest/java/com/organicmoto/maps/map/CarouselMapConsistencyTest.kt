package com.organicmoto.maps.map

import android.Manifest
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.FocusedRouteIndexKey
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.MapRouteCountKey
import com.organicmoto.maps.RouteCountKey
import com.organicmoto.maps.RouteUiStateKey
import com.organicmoto.maps.SelectedRouteKey
import com.organicmoto.maps.ui.KnobRobot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarouselMapConsistencyTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test(timeout = 300_000)
    fun selectedCardAndFocusedMapRouteStayInSync() {
        // Same pre-flight gate the fuzz campaign uses: tolerate slow cold
        // launches before driving the panel.
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(RouteUiStateKey))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Rotate the dial BEFORE any typing: focused planner fields swap the
        // ride-controls content for their search suggestions, so reaching for
        // the knob after typing races field/search state. Dial-first keeps
        // every interaction on a settled, search-free panel. Detent 0 only
        // ever produces the primary candidate, so alternative sync would be
        // tested conditionally without these clicks; this corridor is the
        // corpus's alternativesExpected entry and must surface a distinct
        // second candidate at detent 2.
        KnobRobot.performDetentClicks(composeRule, 2)
        KnobRobot.waitForLevel(composeRule, 2) // knob must be at detent 2 before submitting
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(300_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "success"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(hasContentDescription("Route 2:", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertTrue(
            "detent-2 search on the alternatives corridor must produce a second candidate",
            rootNode().config[RouteCountKey] >= 2,
        )

        assertSelectionMatchesMap()
        val before = rootNode().config[SelectedRouteKey]
        assertEquals(0, before)
        composeRule.onNode(hasContentDescription("Route 2:", substring = true)).performClick()
        composeRule.waitUntil(30_000) { rootNode().config[SelectedRouteKey] != before }
        assertSelectionMatchesMap()
        // Selecting the "Route 2" card must land exactly on alternative index 1.
        assertEquals(1, rootNode().config[SelectedRouteKey])

        // Exercise the MapView stop/start path after selection. The route set
        // and focus stay selected, and the resumed map must become ready again.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(MapRouteCountKey))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertSelectionMatchesMap()
        assertEquals(1, rootNode().config[SelectedRouteKey])
    }

    private fun assertSelectionMatchesMap() {
        val root = rootNode()
        val map = composeRule.onNode(
            SemanticsMatcher.expectValue(MapRouteCountKey, root.config[RouteCountKey]),
        ).fetchSemanticsNode()
        assertEquals(root.config[SelectedRouteKey], map.config[FocusedRouteIndexKey])
        assertEquals(root.config[RouteCountKey], map.config[MapRouteCountKey])
        composeRule.onNode(hasText("Route ${root.config[SelectedRouteKey] + 1}", substring = true)).assertExists()
    }

    private fun rootNode() =
        composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteCountKey)).fetchSemanticsNode()
}

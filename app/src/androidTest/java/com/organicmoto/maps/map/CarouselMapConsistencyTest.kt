package com.organicmoto.maps.map

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.organicmoto.maps.FocusedRouteIndexKey
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.MapRouteCountKey
import com.organicmoto.maps.RouteCountKey
import com.organicmoto.maps.RouteUiStateKey
import com.organicmoto.maps.SelectedRouteKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarouselMapConsistencyTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test(timeout = 300_000)
    fun selectedCardAndFocusedMapRouteStayInSync() {
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(300_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "success"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        assertSelectionMatchesMap()
        val before = rootNode().config[SelectedRouteKey]
        val alternatives = composeRule.onAllNodes(hasContentDescription("Route 2:", substring = true))
            .fetchSemanticsNodes()
        if (alternatives.isNotEmpty()) {
            composeRule.onNode(hasContentDescription("Route 2:", substring = true)).performClick()
            composeRule.waitUntil(30_000) { rootNode().config[SelectedRouteKey] != before }
            assertSelectionMatchesMap()
        }
        assertTrue(rootNode().config[RouteCountKey] >= 1)
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

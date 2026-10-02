package com.organicmoto.maps.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.graphhopper.util.Instruction
import com.organicmoto.maps.NavigationHud
import com.organicmoto.maps.RecommendedLanesKey
import com.organicmoto.maps.routing.navigation.LaneGuidance
import com.organicmoto.maps.routing.navigation.NavigationSnapshot
import com.organicmoto.maps.routing.navigation.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaneGuidanceHudTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun snapshot(sign: Int, lanes: String?, street: String = "Logan Road") = NavigationSnapshot(
        state = NavigationState.OnRoute,
        lat = -27.0,
        lon = 153.0,
        bearingDeg = 0.0,
        speedMps = 0.0,
        speedLimitMps = Double.NaN,
        remainingDistanceM = 900.0,
        remainingTimeS = 120.0,
        completionPercent = 10,
        paceDeltaS = Double.NaN,
        turn = NavigationSnapshot.TurnInfo(
            sign = sign,
            streetName = street,
            distanceM = 180.0,
            lanes = LaneGuidance.recommend(LaneGuidance.parse(lanes), sign),
        ),
    )

    private fun laneNode() = composeRule.onNode(SemanticsMatcher.keyIsDefined(RecommendedLanesKey))

    @Test
    fun markedLanesHighlightEveryLaneCarryingTheTurn() {
        composeRule.setContent { NavigationHud(snapshot(Instruction.TURN_RIGHT, "L:|sr|r")) }

        composeRule.onNodeWithContentDescription("Lane guidance: use lanes 2 and 3 of 3").assertIsDisplayed()
        assertEquals("1,2/3", laneNode().fetchSemanticsNode().config[RecommendedLanesKey])
        // The lane strip lives inside the guidance card, under the street name.
        val card = composeRule.onNodeWithContentDescription("Navigation guidance", substring = true)
            .fetchSemanticsNode().boundsInRoot
        val strip = laneNode().fetchSemanticsNode().boundsInRoot
        assertTrue("lane strip $strip must sit inside the card $card", card.contains(strip.topLeft) &&
            strip.right <= card.right && strip.bottom <= card.bottom)
    }

    @Test
    fun countOnlyLanesSuggestTheOuterLane() {
        composeRule.setContent { NavigationHud(snapshot(Instruction.TURN_LEFT, "L:?|?|?")) }

        composeRule.onNodeWithContentDescription("Lane guidance: use the left lane of 3, suggested")
            .assertIsDisplayed()
        assertEquals("0/3 suggested", laneNode().fetchSemanticsNode().config[RecommendedLanesKey])
    }

    @Test
    fun singleLaneOrUnknownRoadsShowNoLaneStrip() {
        composeRule.setContent { NavigationHud(snapshot(Instruction.TURN_LEFT, "L:?")) }
        composeRule.onNodeWithContentDescription("Navigation guidance", substring = true).assertIsDisplayed()
        laneNode().assertDoesNotExist()
    }

    @Test
    fun widestRoadFitsTheCardAtDoubleFontScaleOnANarrowScreen() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.width(320.dp)) {
                    NavigationHud(snapshot(Instruction.KEEP_LEFT, "L:l|s|s|s|s|s|s|s|s|sr", street = "Pacific Motorway"))
                }
            }
        }
        val strip = laneNode().fetchSemanticsNode().boundsInRoot
        val card = composeRule.onNodeWithContentDescription("Navigation guidance", substring = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals("0/10", laneNode().fetchSemanticsNode().config[RecommendedLanesKey])
        assertTrue("10-lane strip $strip overflows the card $card", strip.left >= card.left && strip.right <= card.right)
        assertTrue("card must stay on a 320 dp screen", card.right <= 320f * composeRule.density.density + 0.5f)
    }
}

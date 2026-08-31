package com.organicmoto.maps.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.organicmoto.maps.GpxRoutePreviewSheet
import com.organicmoto.maps.storage.GeoPoint
import com.organicmoto.maps.storage.GpxMilestone
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GpxRoutePreviewSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun centredMilestoneIsAccessibleAndDrivesPreviewFocus() {
        val milestones = List(8) { index ->
            GpxMilestone(
                point = GeoPoint(-26.6 - index * 0.05, 152.9),
                distanceMeters = index * 10_000.0,
                progress = index / 7.0,
                placeName = "Place $index",
                placeDetail = "Queensland",
            )
        }
        var focused = milestones.first()
        composeRule.setContent {
            MaterialTheme {
                GpxRoutePreviewSheet(
                    routeName = "Coast ride",
                    milestones = milestones,
                    onFocusMilestone = { focused = it },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Preview route").assertExists()
        composeRule.onNodeWithContentDescription("GPX route milestones").performTouchInput { swipeUp() }
        composeRule.waitUntil(5_000) { focused != milestones.first() }
        composeRule.onNode(hasContentDescription("focused on map", substring = true)).assertExists()

        val closeBounds = composeRule.onNodeWithContentDescription("Close route preview")
            .fetchSemanticsNode().boundsInRoot
        val minimum = 48f * composeRule.density.density
        assertTrue(closeBounds.width >= minimum)
        assertTrue(closeBounds.height >= minimum)
    }
}

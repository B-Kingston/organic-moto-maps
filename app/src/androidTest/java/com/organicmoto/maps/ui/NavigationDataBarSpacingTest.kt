package com.organicmoto.maps.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.organicmoto.maps.NavigationDataBar
import com.organicmoto.maps.routing.navigation.NavigationSnapshot
import com.organicmoto.maps.routing.navigation.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Keeps the ride row centered even when the surface includes the system inset. */
@RunWith(AndroidJUnit4::class)
class NavigationDataBarSpacingTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun singleRowHasEqualSpaceAboveAndBelowIncludingNavigationInset() {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.requiredSize(width = 465.dp, height = 180.dp)) {
                    NavigationDataBar(
                        snapshot = NavigationSnapshot(
                            state = NavigationState.OnRoute,
                            lat = -27.46,
                            lon = 153.02,
                            bearingDeg = 0.0,
                            speedMps = 25.0,
                            speedLimitMps = Double.NaN,
                            remainingDistanceM = 9_850.0,
                            remainingTimeS = 79 * 60.0,
                            completionPercent = 40,
                            paceDeltaS = 12.3,
                            turn = null,
                        ),
                        onEnd = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        val bar = bounds("Ride data:", substring = true)
        val media = bounds("Media controls")
        val end = bounds("End navigation")
        val rowTop = minOf(media.top, end.top)
        val rowBottom = maxOf(media.bottom, end.bottom)
        assertEquals("test needs the compact single row", media.top, end.top, 1f)
        assertEquals("test needs the compact single row", media.bottom, end.bottom, 1f)

        val spaceAbove = rowTop - bar.top
        val spaceBelow = bar.bottom - rowBottom
        assertEquals(
            "ride row spacing should match above and below, including the system navigation inset",
            spaceAbove,
            spaceBelow,
            1f * composeRule.density.density,
        )
    }

    private fun bounds(description: String, substring: Boolean = false) =
        composeRule.onNodeWithContentDescription(description, substring = substring)
            .fetchSemanticsNode().boundsInRoot
}

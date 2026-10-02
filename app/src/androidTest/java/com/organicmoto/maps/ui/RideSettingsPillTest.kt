package com.organicmoto.maps.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.organicmoto.maps.OfflineSpeechStatus
import com.organicmoto.maps.RouteActionsPill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Ride-mode actions keep their small icons while exposing glove-sized targets. */
@RunWith(AndroidJUnit4::class)
class RideSettingsPillTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun soundAndDarkMapStayReachableInOneCompactPill() {
        var soundSettingsOpened = 0
        composeRule.setContent {
            var darkMapEnabled by remember { mutableStateOf(false) }
            MaterialTheme {
                Box(Modifier.requiredSize(width = 320.dp, height = 360.dp).background(Color.Black)) {
                    RouteActionsPill(
                        onLoadMap = {},
                        onImportGpx = {},
                        onRouteSettings = {},
                        onMapsSettings = {},
                        onVoiceSettings = { soundSettingsOpened++ },
                        voiceGuidanceEnabled = true,
                        voiceSpeechStatus = OfflineSpeechStatus.Ready("English"),
                        onOpenSavedRoutes = {},
                        settingsMenuExpanded = false,
                        onSettingsMenuExpandedChange = {},
                        rideMode = true,
                        darkRideMapEnabled = darkMapEnabled,
                        onDarkRideMapToggle = { darkMapEnabled = !darkMapEnabled },
                        monochrome = true,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("Ride settings").assertExists()
        val gloveTarget = 64f * composeRule.density.density
        val sound = bounds("Voice guidance settings", substring = true)
        val voiceControl = composeRule.onNodeWithContentDescription(
            "Voice guidance settings, enabled, English offline voice ready",
        )
        voiceControl.assertHasClickAction()
        assertEquals(
            "English offline voice ready",
            voiceControl.fetchSemanticsNode().config[
                androidx.compose.ui.semantics.SemanticsProperties.StateDescription
            ],
        )
        val darkOff = bounds("Dark ride map, off")
        listOf("Voice guidance settings" to sound, "Dark ride map, off" to darkOff).forEach { (label, rect) ->
            composeRule.onNodeWithContentDescription(label, substring = label == "Voice guidance settings")
                .assertHasClickAction()
            assertTrue("$label width ${rect.width} < $gloveTarget", rect.width >= gloveTarget)
            assertTrue("$label height ${rect.height} < $gloveTarget", rect.height >= gloveTarget)
        }
        assertTrue("ride settings controls overlap: $sound / $darkOff", sound.bottom <= darkOff.top)
        assertTrue("the pill should stay narrow", bounds("Ride settings").width <= 72f * composeRule.density.density)

        // The white rail is only 48 dp wide; the outer 8 dp remains touchable.
        val pixels = composeRule.onNodeWithContentDescription("Ride settings").captureToImage().toPixelMap()
        val row = pixels.height / 2
        val white = (0 until pixels.width).filter { pixels[it, row].red > 0.9f }
        val visibleWidth = white.last() - white.first() + 1
        // The 1 dp gray border on each side is excluded by the white threshold.
        assertEquals(46f * composeRule.density.density, visibleWidth.toFloat(), 2f)
        assertEquals(128f * composeRule.density.density, pixels.height.toFloat(), 2f)
        composeRule.onNodeWithContentDescription("Voice guidance settings", substring = true)
            .performTouchInput { click(Offset(2.dp.toPx(), center.y)) }
        assertEquals(1, soundSettingsOpened)
        composeRule.onNodeWithContentDescription("Dark ride map, off")
            .performTouchInput { click(Offset(width - 2.dp.toPx(), center.y)) }
        composeRule.onNodeWithContentDescription("Dark ride map, on").assertExists()
    }

    private fun bounds(description: String, substring: Boolean = false): Rect =
        composeRule.onNodeWithContentDescription(description, substring = substring)
            .fetchSemanticsNode().boundsInRoot
}

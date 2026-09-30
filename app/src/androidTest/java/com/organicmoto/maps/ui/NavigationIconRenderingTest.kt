package com.organicmoto.maps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.organicmoto.maps.DarkRideMapIcon
import com.organicmoto.maps.VoiceGuidanceIcon
import com.organicmoto.maps.drawTurnArrow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pixel invariants catch missing arrowheads and clipped glyphs that semantics cannot see. */
@RunWith(AndroidJUnit4::class)
class NavigationIconRenderingTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun pendingInstructionHasAnArrowheadRatherThanABareStem() {
        composeRule.setContent {
            Canvas(Modifier.size(44.dp).background(Color.Black).testTag("arrow")) {
                drawTurnArrow(null, size.width, size.height)
            }
        }
        val pixels = composeRule.onNodeWithTag("arrow").captureToImage().toPixelMap()
        fun spanAt(fraction: Float): Int {
            val y = (pixels.height * fraction).toInt()
            val lit = (0 until pixels.width).filter { pixels[it, y].red > 0.5f }
            return if (lit.isEmpty()) 0 else lit.last() - lit.first() + 1
        }
        assertTrue("The head must visibly spread beyond the shaft", spanAt(0.33f) > spanAt(0.6f) * 1.8f)
    }

    @Test
    fun bothSpeakerAndMoonStatesStayInsideTheirCanvas() {
        composeRule.setContent {
            Row {
                for (enabled in listOf(false, true)) {
                    Box(Modifier.background(Color.Black).testTag("speaker-$enabled")) {
                        VoiceGuidanceIcon(enabled)
                    }
                    Box(Modifier.background(Color.Black).testTag("moon-$enabled")) {
                        DarkRideMapIcon(enabled)
                    }
                }
            }
        }
        val inkCounts = mutableMapOf<String, Int>()
        for (name in listOf("speaker-false", "speaker-true", "moon-false", "moon-true")) {
            val pixels = composeRule.onNodeWithTag(name).captureToImage().toPixelMap()
            var ink = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (pixels[x, y].red > 0.5f) {
                    ink++
                    assertTrue("$name touches the canvas edge", x > 0 && y > 0 && x < pixels.width - 1 && y < pixels.height - 1)
                }
            }
            assertTrue("$name must render visible ink", ink > 20)
            inkCounts[name] = ink
        }
        assertTrue("An active moon must have a visibly filled silhouette", inkCounts.getValue("moon-true") > inkCounts.getValue("moon-false") * 1.3f)
    }
}

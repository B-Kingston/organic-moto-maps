package com.organicmoto.maps.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.organicmoto.maps.NavigationDataBar
import com.organicmoto.maps.NavigationHudFormat
import com.organicmoto.maps.routing.navigation.NavigationSnapshot
import com.organicmoto.maps.routing.navigation.NavigationState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Responsive contract of the ride data bar: on a 320 dp screen at 2x font
 * scale the bar must lay the metrics out as a readable grid (two rows) with
 * full values, glove-sized media opener and END, and no overlapping targets —
 * never a single squeezed row of ellipsised 10 sp values.
 */
@RunWith(AndroidJUnit4::class)
class DataBarResponsiveTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun snapshot() = NavigationSnapshot(
        state = NavigationState.OnRoute,
        lat = -27.46,
        lon = 153.02,
        bearingDeg = 0.0,
        speedMps = 25.0, // 90 km/h
        speedLimitMps = Double.NaN,
        remainingDistanceM = 9_850.0, // "9.9 km"
        remainingTimeS = 79 * 60.0,
        completionPercent = 40,
        paceDeltaS = 12.3,
        turn = null,
    )

    private fun show(width: androidx.compose.ui.unit.Dp, fontScale: Float) {
        val density = composeRule.density
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                MaterialTheme {
                    Box(Modifier.width(width)) {
                        NavigationDataBar(
                            snapshot = snapshot(),
                            onEnd = {},
                            mediaPanelOpen = false,
                            onMediaToggle = {},
                        )
                    }
                }
            }
        }
    }

    private fun bounds(description: String) =
        composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot

    /**
     * Every metric value must be laid out with real size. A node that exists
     * but measured to nothing is exactly the squeezed-away failure this suite
     * exists to catch, so report which values failed instead of a bare
     * "component is not displayed".
     */
    private fun assertMetricValuesDisplayed(values: List<String>) {
        val report = values.joinToString("; ") { value ->
            val nodes = composeRule.onAllNodes(
                androidx.compose.ui.test.hasText(value),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            val sizes = nodes.map { "${it.boundsInRoot.width}x${it.boundsInRoot.height}" }
            "$value=${if (nodes.isEmpty()) "missing" else sizes.joinToString()}"
        }
        val blank = values.filter { value ->
            composeRule.onAllNodes(androidx.compose.ui.test.hasText(value), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .none { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
        }
        assertTrue("metric values missing or squeezed to nothing: $blank ($report)", blank.isEmpty())
    }

    @Test
    fun narrowLargeFontShowsEveryFullMetricValue() {
        show(320.dp, fontScale = 2f)
        assertMetricValuesDisplayed(listOf("90", "1h 19m", "9.9 km", "+12.3 s"))
        val density = composeRule.density.density
        // The two-row grid gives each cell half the bar. The long values must
        // render at (at least) the bar's minimum font, which the old squeezed
        // single row could not do: there the cell was ~45 dp wide and the value
        // collapsed to an ellipsis.
        val eta = composeRule.onNodeWithText("1h 19m").fetchSemanticsNode().boundsInRoot
        val distance = composeRule.onNodeWithText("9.9 km").fetchSemanticsNode().boundsInRoot
        val etaFloor = 6 * NavigationHudFormat.MIN_METRIC_SP * 0.5f * 2f * density
        val distanceFloor = 7 * NavigationHudFormat.MIN_METRIC_SP * 0.5f * 2f * density
        assertTrue("time value squeezed: ${eta.width} < $etaFloor", eta.width >= etaFloor)
        assertTrue(
            "distance value squeezed: ${distance.width} < $distanceFloor",
            distance.width >= distanceFloor,
        )
        // Two rows means the second metric row sits below the first.
        assertTrue("metrics still on one squeezed row", distance.top >= eta.bottom - 1f)
        // Nothing leaves the bar (the value row spans the full bar width; END
        // sits on its own row below).
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(
            "a value clipped off the bar: ${eta.right} vs ${root.right}",
            eta.right <= root.right + 1f,
        )
        assertTrue("metric row clipped on the left: ${eta.left}", eta.left >= root.left - 1f)
    }

    @Test
    fun narrowLargeFontKeepsGloveSizedMediaAndEndTargets() {
        show(320.dp, fontScale = 2f)
        val glove = 64f * composeRule.density.density
        val media = bounds("Media controls")
        val end = bounds("End navigation")
        assertTrue("media opener too small: ${media.width}x${media.height}",
            media.width >= glove && media.height >= glove)
        assertTrue("END too small: ${end.width}x${end.height}",
            end.width >= glove && end.height >= glove)
        assertTrue(
            "media and END overlap: ${media.right} vs ${end.left}",
            end.left - media.right >= 8f * composeRule.density.density,
        )
        assertTrue("media leaves the bar: ${media.left}", media.left >= -1f)
        assertTrue("END leaves the bar: ${end.right}", end.right <= 320f * composeRule.density.density + 1f)
    }

    @Test
    fun wideScreenKeepsTheSingleCompactRow() {
        show(465.dp, fontScale = 1f)
        assertMetricValuesDisplayed(listOf("90", "1h 19m", "9.9 km", "+12.3 s"))
        val speed = composeRule.onNodeWithText("90").fetchSemanticsNode().boundsInRoot
        val distance = composeRule.onNodeWithText("9.9 km").fetchSemanticsNode().boundsInRoot
        // One row: both values share a baseline band.
        assertTrue(
            "wide bar unexpectedly wrapped to two rows",
            kotlin.math.abs(speed.top - distance.top) < 4f * composeRule.density.density,
        )
    }

    @Test
    fun monochromeDataBarStillExposesEveryControl() {
        val density = composeRule.density
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
            ) {
                MaterialTheme {
                    Box(Modifier.width(320.dp)) {
                        NavigationDataBar(
                            snapshot = snapshot(),
                            onEnd = {},
                            mediaPanelOpen = true,
                            onMediaToggle = {},
                            monochrome = true,
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithContentDescription("Media controls").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("End navigation").assertIsDisplayed()
        composeRule.onNodeWithText("END").assertIsDisplayed()
    }
}

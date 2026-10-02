package com.organicmoto.maps.ui

import android.Manifest
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.DarkGuidanceStyleReadyKey
import com.organicmoto.maps.DarkRideMapEnabledKey
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.RideSurfaceColor
import com.organicmoto.maps.RouteUiStateKey
import com.organicmoto.maps.map.OfflineBasemapRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Fixture-first native screen coverage for the rider-lock control's selected
 * and unselected palettes. The color samples come from the actual 48 dp map
 * button rather than a duplicate preview of its glyph.
 */
@RunWith(AndroidJUnit4::class)
class CameraFollowControlVisualOnDeviceTest {

    private val offlineBasemap = OfflineBasemapRule()
    private val permission = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(offlineBasemap)
        .around(permission)
        .around(compose)

    @org.junit.Before
    fun dismissStartupSetup() = compose.dismissMediaStartupPrompt()

    @Test(timeout = 300_000)
    fun lockButtonInvertsTheMapPillPaletteInBothModes() {
        awaitMapPrerequisites()
        compose.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        compose.onNodeWithContentDescription("To").performTextInput("-27.4570,153.0350")
        compose.onNodeWithText("START").performClick()
        compose.waitUntil(240_000) {
            compose.onAllNodes(
                SemanticsMatcher.expectValue(RouteUiStateKey, "success"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("RIDE").performClick()
        compose.onNodeWithText("END").assertExists()
        val startedWithBlackAndWhite = hasValue(DarkRideMapEnabledKey, true)
        if (startedWithBlackAndWhite) {
            compose.onNodeWithContentDescription("Dark ride map, on").performClick()
            compose.waitUntil(10_000) { hasValue(DarkRideMapEnabledKey, false) }
        }

        assertLockButton(
            description = "Rider lock on; tap to release",
            expectedBackground = RideSurfaceColor,
            expectedGlyph = Color.White,
        )
        toggleLockSemantically("Rider lock on; tap to release")
        settlePaletteAnimation()
        assertLockButton(
            description = "Rider lock off; tap to follow",
            expectedBackground = Color.White,
            expectedGlyph = RideSurfaceColor,
        )
        toggleLockSemantically("Rider lock off; tap to follow")
        settlePaletteAnimation()

        compose.onNodeWithContentDescription("Dark ride map, off").performClick()
        compose.waitUntil(90_000) {
            compose.onAllNodes(
                SemanticsMatcher.expectValue(DarkGuidanceStyleReadyKey, true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        assertLockButton(
            description = "Rider lock on; tap to release",
            expectedBackground = RideSurfaceColor,
            expectedGlyph = Color.White,
        )
        toggleLockSemantically("Rider lock on; tap to release")
        settlePaletteAnimation()
        assertLockButton(
            description = "Rider lock off; tap to follow",
            expectedBackground = Color.White,
            expectedGlyph = RideSurfaceColor,
        )
        toggleLockSemantically("Rider lock off; tap to follow")
        settlePaletteAnimation()
        if (!startedWithBlackAndWhite) {
            compose.onNodeWithContentDescription("Dark ride map, on").performClick()
            compose.waitUntil(10_000) { hasValue(DarkRideMapEnabledKey, false) }
        }
        compose.onNodeWithText("END").performClick()
    }

    private fun assertLockButton(
        description: String,
        expectedBackground: Color,
        expectedGlyph: Color,
    ) {
        val pixels = compose.onNodeWithContentDescription(description)
            .captureToImage()
            .toPixelMap()
        val sideInsetPx = (4f * compose.density.density).toInt().coerceAtLeast(1)
        assertEquals(
            "$description background",
            expectedBackground,
            pixels[sideInsetPx, pixels.height / 2],
        )
        assertEquals(
            "$description crosshair center",
            expectedGlyph,
            pixels[pixels.width / 2, pixels.height / 2],
        )
    }

    private fun toggleLockSemantically(description: String) {
        compose.onNodeWithContentDescription(description)
            .performSemanticsAction(SemanticsActions.OnClick) { onClick -> onClick() }
    }

    private fun settlePaletteAnimation() {
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
    }

    private fun awaitMapPrerequisites() = CameraMapPrerequisites.awaitReady(compose)

    private fun hasValue(
        key: androidx.compose.ui.semantics.SemanticsPropertyKey<Boolean>,
        value: Boolean,
    ): Boolean = compose.onAllNodes(SemanticsMatcher.expectValue(key, value), useUnmergedTree = true)
        .fetchSemanticsNodes().isNotEmpty()
}

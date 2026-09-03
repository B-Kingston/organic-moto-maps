package com.organicmoto.maps.ui

import android.Manifest
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.RouteScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FontScaleTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun fontScaleTwoKeepsStartAndAttributionReachable() {
        setRouteScreen(fontScale = 2f)
        assertStableSurfaceAndStart()
        composeRule.onNodeWithContentDescription("From").performTextInput("invalid input")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(hasText("No match", substring = true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val error = composeRule.onNode(hasText("No match", substring = true)).fetchSemanticsNode().boundsInRoot
        assertTrue(error.left >= root.left)
        assertTrue(error.right <= root.right)
        assertTrue(error.top >= root.top)
        assertTrue(error.bottom <= root.bottom)
    }

    @Test
    fun fontScaleOnePointThreeKeepsStartReachable() {
        setRouteScreen(fontScale = 1.3f)
        assertStableSurfaceAndStart()
    }

    private fun setRouteScreen(fontScale: Float) {
        val density = composeRule.density
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale),
            ) {
                MaterialTheme { RouteScreen() }
            }
        }
    }

    private fun assertStableSurfaceAndStart() {
        val start = composeRule.onNodeWithText("START")
        start.assertExists()
        start.assertHasClickAction()
        composeRule.onNode(hasText("© OpenMapTiles.org © OpenStreetMap contributors", substring = true)).assertExists()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val bounds = start.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= root.left)
        assertTrue(bounds.right <= root.right)
        assertTrue(bounds.bottom <= root.bottom)
    }
}

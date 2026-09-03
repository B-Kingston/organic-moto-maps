package com.organicmoto.maps.map

import android.Manifest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.MapReadyKey
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineMapSmokeTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test(timeout = 240_000)
    fun localMapLoadsAttributionAndSurvivesControls() {
        composeRule.waitUntil(180_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(MapReadyKey, true))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNode(SemanticsMatcher.expectValue(MapReadyKey, true)).assertExists()
        composeRule.onNode(hasText("© OpenMapTiles.org © OpenStreetMap contributors", substring = true)).assertExists()
        // Zoom actions use semantics and do not depend on API-private touch injection.
        composeRule.onNodeWithContentDescription("Zoom in").performClick()
        composeRule.onNodeWithContentDescription("Zoom out").performClick()
        composeRule.onNodeWithContentDescription("Zoom in").assertExists()
        composeRule.onNodeWithContentDescription("Zoom out").assertExists()
        composeRule.onNode(hasText("© OpenMapTiles.org © OpenStreetMap contributors", substring = true)).assertExists()
    }
}

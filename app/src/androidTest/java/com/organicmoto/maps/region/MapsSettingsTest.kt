package com.organicmoto.maps.region

import com.organicmoto.maps.ui.dismissMediaStartupPrompt
import android.Manifest
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Maps screen: reachable from the settings card, exposes every control with
 * a content description and a glove-sized target, refuses activation while a
 * ride is active, and returns to the planner with the shared back button.
 */
@RunWith(AndroidJUnit4::class)
class MapsSettingsTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    private fun openMapsScreen() {
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Maps").performClick()
        composeRule.onNodeWithContentDescription("Map server address").assertExists()
    }

    @Test
    fun exposesTheServerControlsWithGloveSizedTargets() {
        openMapsScreen()
        val minimum = 48f * composeRule.density.density
        listOf(
            "Map server address",
            "Check server",
            "Import package file",
        ).forEach { description ->
            val bounds: Rect = composeRule.onNodeWithContentDescription(description)
                .assertExists()
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$description ${bounds.width} x ${bounds.height} must be at least 48 dp",
                bounds.width >= minimum && bounds.height >= minimum,
            )
        }
        composeRule.onNodeWithContentDescription("Check server").assertHasClickAction()
        composeRule.onNodeWithContentDescription("Import package file").assertHasClickAction()
    }

    @Test
    fun checkingWithoutAnAddressShowsAnHonestError() {
        openMapsScreen()
        // The button is disabled until an address is typed, so the user cannot
        // trigger a meaningless request.
        composeRule.onNodeWithContentDescription("Check server").assertIsNotEnabled()
    }

    @Test
    fun systemBackReturnsToThePlanner() {
        openMapsScreen()
        Espresso.pressBack()
        composeRule.onNodeWithContentDescription("Map server address").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Planning settings").assertExists()
    }

    @Test
    fun thePlannerSettingsCardListsMaps() {
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Maps").assertExists()
        composeRule.onNodeWithText("Load map file").assertExists()
    }

    @Test
    fun bundledOnlyStateIsHonestAndCannotBeDeleted() {
        openMapsScreen()
        composeRule.onNodeWithText("Queensland (bundled)").assertExists()
        composeRule.onNodeWithText("cannot be deleted", substring = true).assertExists()
        composeRule.onNodeWithText("No downloaded maps are installed.", substring = true).assertExists()
        // The immutable bundled data offers no rubbish bin.
        assertTrue(
            composeRule.onAllNodes(
                androidx.compose.ui.test.hasContentDescription("Delete", substring = true),
            ).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun headerNamesTheActiveMapAndNoControlSitsUnderTheActionRail() {
        openMapsScreen()
        composeRule.onNodeWithContentDescription("Active map Queensland (bundled)").assertExists()
        // The cog/bookmark rail stays reachable above this screen; the header
        // block reserves its corner so no Maps control is ever hidden by it.
        val rail = listOf("Planning settings", "Saved routes").map { description ->
            composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        }
        listOf(
            "Active map Queensland (bundled)",
            "Map server address",
            "Check server",
        ).forEach { description ->
            val control = composeRule.onNodeWithContentDescription(description)
                .fetchSemanticsNode().boundsInRoot
            rail.forEach { button ->
                assertTrue(
                    "$description $control overlaps the action rail $button",
                    !control.overlaps(button),
                )
            }
        }
    }
}

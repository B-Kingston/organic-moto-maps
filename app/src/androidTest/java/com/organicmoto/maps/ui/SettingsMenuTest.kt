package com.organicmoto.maps.ui

import android.Manifest
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings cog expands into a card whose right edge stays flush with the
 * pill, so the pill reads as the card's tab. This suite pins that contract —
 * both rail targets keep their size and position, every row sits in the body
 * left of the tab — and the back-button exit shared by the screens the card
 * opens.
 */
@RunWith(AndroidJUnit4::class)
class SettingsMenuTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    @Test
    fun expandedCardKeepsThePillAsItsTab() {
        val cogClosed = bounds("Planning settings")
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        val cog = bounds("Planning settings")
        val bookmark = bounds("Saved routes")
        val minimum = 48f * composeRule.density.density

        // Opening the card must not move or shrink the tab.
        assertEquals(cogClosed.left, cog.left, 1f)
        assertEquals(cogClosed.top, cog.top, 1f)
        assertEquals(cogClosed.right, cog.right, 1f)
        assertEquals(cogClosed.bottom, cog.bottom, 1f)
        listOf("Planning settings" to cog, "Saved routes" to bookmark).forEach { (label, target) ->
            assertTrue(
                "$label ${target.width} x ${target.height} must stay at least 48 dp",
                target.width >= minimum && target.height >= minimum,
            )
        }
        assertEquals(cog.right, bookmark.right, 1f)

        // Rows fill the body, ending where the tab column starts, in order.
        val rows = listOf("Route settings", "Load map file", "Maps", "Import GPX route", "Sound settings")
            .map { rowBounds(it) }
        rows.forEach { row ->
            assertTrue("row right ${row.right} vs tab left ${cog.left}", row.right <= cog.left + 1f)
            assertTrue("row height ${row.height} must be at least 48 dp", row.height >= minimum)
        }
        rows.zipWithNext().forEach { (above, below) ->
            assertTrue("rows must stay in order: ${above.top} then ${below.top}", above.top < below.top)
        }
        assertTrue(
            "first row ${rows.first().top} must start at the tab's bottom ${cog.bottom}",
            rows.first().top >= cog.bottom - 1f,
        )

        // The card is anchored to the top-right: nothing leaves the screen.
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        rows.forEach { row ->
            assertTrue(row.left >= root.left)
            assertTrue(row.right <= root.right)
            assertTrue(row.bottom <= root.bottom)
        }
    }

    @Test
    fun settingsScreensExposeBackButtonsThatReturnToThePlanner() {
        openSettingsScreen("Route settings")
        composeRule.onNodeWithText("Target maximum shared roads").assertExists()
        exitSettingsScreen()
        composeRule.onNodeWithText("Target maximum shared roads").assertDoesNotExist()

        openSettingsScreen("Sound settings")
        composeRule.onNodeWithText("Spoken turn guidance").assertExists()
        exitSettingsScreen()
        composeRule.onNodeWithText("Spoken turn guidance").assertDoesNotExist()

        // Maps opens the regional package screen; Back returns to the planner.
        openSettingsScreen("Maps")
        composeRule.onNodeWithContentDescription("Map server address").assertExists()
        composeRule.onNodeWithContentDescription("Import package file").assertExists()
        exitSettingsScreen()
        composeRule.onNodeWithContentDescription("Map server address").assertDoesNotExist()
    }

    @Test
    fun cogTogglesTheCardAndSystemBackClosesIt() {
        openSettingsMenu()
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Sound settings").assertDoesNotExist()

        openSettingsMenu()
        Espresso.pressBack()
        composeRule.onNodeWithText("Sound settings").assertDoesNotExist()
    }

    @Test
    fun tappingThePlannerPanelClosesTheCard() {
        openSettingsMenu()
        // The planner panel is outside the map region. The dismissal scrim
        // must cover it too, so this tap closes the card instead of leaking
        // into the field underneath.
        composeRule.onNodeWithContentDescription("From").performClick()
        composeRule.onNodeWithText("Sound settings").assertDoesNotExist()
    }

    private fun openSettingsMenu() {
        if (composeRule.onAllNodes(hasText("Sound settings")).fetchSemanticsNodes().isEmpty()) {
            composeRule.onNodeWithContentDescription("Planning settings").performClick()
        }
        composeRule.onNodeWithText("Sound settings").assertExists()
    }

    private fun openSettingsScreen(label: String) {
        openSettingsMenu()
        composeRule.onNodeWithText(label).performClick()
        composeRule.onNodeWithContentDescription("Back").assertExists().assertHasClickAction()
    }

    private fun exitSettingsScreen() {
        composeRule.onNodeWithContentDescription("Back").performClick()
    }

    private fun bounds(description: String): Rect =
        composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot

    private fun rowBounds(label: String): Rect =
        composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
}

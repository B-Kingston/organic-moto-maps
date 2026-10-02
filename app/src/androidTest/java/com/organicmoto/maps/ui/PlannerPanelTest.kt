package com.organicmoto.maps.ui

import android.Manifest
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.CAROUSEL_SLOT_HEIGHT
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.MapRouteCountKey
import com.organicmoto.maps.RouteUiStateKey
import com.organicmoto.maps.ROUTE_ENDPOINT_GROUP_TEST_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlannerPanelTest {

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
    fun startIsReachableAndEnabledWhileIdle() {
        val start = composeRule.onNodeWithText("START")
        start.assertExists()
        start.assertIsEnabled()
        start.assertHasClickAction()
        val bounds = start.fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.left >= root.left)
        assertTrue(bounds.right <= root.right)
        assertTrue(bounds.top >= root.top)
        assertTrue(bounds.bottom <= root.bottom)
    }

    @Test
    fun currentLocationShortcutIsReachableBesideFrom() {
        val shortcut = composeRule.onNodeWithContentDescription("Use current location")
        shortcut.assertHasClickAction()
        val bounds = shortcut.fetchSemanticsNode().boundsInRoot
        val minimum = 48f * composeRule.density.density
        assertTrue("current-location target width ${bounds.width} < $minimum", bounds.width >= minimum)
        assertTrue("current-location target height ${bounds.height} < $minimum", bounds.height >= minimum)
        assertTrue(
            "current-location shortcut must sit beside the From field",
            bounds.left >= composeRule.onNodeWithContentDescription("From").fetchSemanticsNode().boundsInRoot.left,
        )
    }

    @Test
    fun firstSearchPopupStaysAboveBothPlannerRows() {
        composeRule.onNodeWithContentDescription("From").performTextInput("Brisbane")
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(hasContentDescription("Search results"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        val popup = composeRule.onNodeWithContentDescription("Search results")
            .fetchSemanticsNode().boundsOnScreen()
        // Popups have their own Compose root. Select the route-screen node
        // explicitly instead of `onRoot()`, which is ambiguous while the
        // popup is present.
        val root = composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteUiStateKey))
            .fetchSemanticsNode().boundsOnScreen()
        val from = composeRule.onNodeWithContentDescription("From")
            .fetchSemanticsNode().boundsOnScreen()
        val to = composeRule.onNodeWithContentDescription("To")
            .fetchSemanticsNode().boundsOnScreen()
        val endpointGroup = composeRule.onNodeWithTag(ROUTE_ENDPOINT_GROUP_TEST_TAG)
            .fetchSemanticsNode().boundsOnScreen()
        assertTrue("search popup must remain on-screen", popup.top >= root.top && popup.bottom <= root.bottom)
        assertTrue("search popup must stay above From", popup.bottom <= from.top)
        assertTrue("search popup must stay above To", popup.bottom <= to.top)
        val expectedPopupWidth = endpointGroup.width - 64f * composeRule.density.density
        assertEquals(expectedPopupWidth, popup.width, 1f)
        assertEquals(endpointGroup.center.x, popup.center.x, 1f)
        assertEquals(endpointGroup.top, popup.bottom, 1f)
    }

    @Test
    fun planningSettingsAndSavedRoutesFormAReachableVerticalPill() {
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val settings = composeRule.onNodeWithContentDescription("Planning settings")
            .fetchSemanticsNode().boundsInRoot
        val saved = composeRule.onNodeWithContentDescription("Saved routes")
            .fetchSemanticsNode().boundsInRoot
        val zoomIn = composeRule.onNodeWithContentDescription("Zoom in")
            .fetchSemanticsNode().boundsInRoot

        assertTrue(settings.top < saved.top)
        assertTrue(settings.left >= root.center.x)
        assertEquals(settings.width, zoomIn.width, 1f)
        assertEquals(settings.right, zoomIn.right, 1f)
        assertTrue(saved.right <= root.right)
        assertTrue(saved.bottom <= root.bottom)
        composeRule.onNodeWithText("Load map file").assertDoesNotExist()
        composeRule.onNodeWithText("Import GPX route").assertDoesNotExist()
    }

    @Test
    fun settingsCogOpensAccessibleMenuAndRouteActionDismissesIt() {
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        listOf("Route settings", "Load map file", "Import GPX route", "Sound settings")
            .forEach { label ->
                val item = composeRule.onNodeWithText(label)
                item.assertExists()
                item.assertHasClickAction()
                val bounds = item.fetchSemanticsNode().boundsInRoot
                val minimum = 48f * composeRule.density.density
                assertTrue("$label width ${bounds.width} < $minimum", bounds.width >= minimum)
                assertTrue("$label height ${bounds.height} < $minimum", bounds.height >= minimum)
            }
        composeRule.onNodeWithText("Route settings").performClick()
        composeRule.onNodeWithText("Target maximum shared roads").assertExists()
        composeRule.onNodeWithText("Load map file").assertDoesNotExist()
    }

    @Test
    fun settingsMenuDismissesOnBackAndOutsideTap() {
        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Sound settings").assertExists()
        Espresso.pressBack()
        composeRule.onNodeWithText("Sound settings").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Sound settings").assertExists()
        Espresso.onView(isRoot()).perform(ViewActions.click())
        composeRule.onNodeWithText("Sound settings").assertDoesNotExist()
    }

    private fun SemanticsNode.boundsOnScreen(): Rect {
        val position = positionOnScreen
        check(position.isSpecified) { "${config} has no screen position" }
        return Rect(
            left = position.x,
            top = position.y,
            right = position.x + size.width.toFloat(),
            bottom = position.y + size.height.toFloat(),
        )
    }

    @Test
    fun invalidInputShowsErrorAndReenablesStart() {
        composeRule.onNodeWithContentDescription("From").performTextInput("not a real place")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "error"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("START").assertIsEnabled()
        composeRule.onNodeWithText("No match", substring = true).assertExists()
    }
    @Test
    fun rapidEmptySubmissionsNeverLeaveStartLoading() {
        repeat(5) { composeRule.onNodeWithText("START").performClick() }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, "error"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("START").assertIsEnabled()
        composeRule.onNode(SemanticsMatcher.expectValue(RouteUiStateKey, "loading")).assertDoesNotExist()
    }

    @Test(timeout = 300_000)
    fun validCoordinatesKeepPanelFrameFixedAcrossRouting() {
        // Routeless frame contract: the panel is bottom-anchored, so START
        // and the From/To rows sit at the same spot in every state that has
        // no route candidates (idle, typing, loading, error).
        val idleBounds = composeRule.onNodeWithText("START").fetchSemanticsNode().boundsInRoot
        val idleFromTop = composeRule.onNodeWithContentDescription("From")
            .fetchSemanticsNode().boundsInRoot.top
        val idleMapBounds = composeRule.onNode(SemanticsMatcher.keyIsDefined(MapRouteCountKey))
            .fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        var observedSuccess = false
        composeRule.waitUntil(300_000) {
            val states = composeRule.onAllNodes(
                SemanticsMatcher.keyIsDefined(RouteUiStateKey),
            ).fetchSemanticsNodes()
            val success = states.any {
                it.config[RouteUiStateKey] == "success"
            }
            observedSuccess = observedSuccess || success
            observedSuccess
        }
        // The waitUntil above can only exit when a success frame is observed,
        // so asserting anything weaker here would be unreachable-false.
        assertTrue(observedSuccess)
        // Success swaps the frame: the carousel bar (one card per candidate)
        // appears above the fields. The panel grows upward only: START and
        // the From/To rows never move, and the panel's top edge — the map
        // viewport's bottom — rises by exactly the carousel slot height.
        val carouselPx = with(composeRule.density) { CAROUSEL_SLOT_HEIGHT.toPx() }
        val successBounds = composeRule.onNodeWithText("RIDE").fetchSemanticsNode().boundsInRoot
        val fromBounds = composeRule.onNodeWithContentDescription("From")
            .fetchSemanticsNode().boundsInRoot
        val successMapBounds = composeRule.onNode(SemanticsMatcher.keyIsDefined(MapRouteCountKey))
            .fetchSemanticsNode().boundsInRoot
        assertEquals(idleBounds, successBounds)
        assertEquals(idleFromTop, fromBounds.top, 1f)
        assertEquals(carouselPx, idleMapBounds.bottom - successMapBounds.bottom, 1f)
    }
}

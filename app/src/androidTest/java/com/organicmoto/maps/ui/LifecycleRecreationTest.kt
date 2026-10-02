package com.organicmoto.maps.ui

import android.Manifest
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.RouteUiStateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LifecycleRecreationTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    @Test(timeout = 300_000)
    fun editableFieldsSurviveSubmissionRecreation() {
        enterCoordinates()
        composeRule.onNodeWithText("START").performClick()
        // Honest pre-recreate guard: the submission must have been observed
        // leaving idle (loading or success) so this really exercises
        // recreation during/independent of async routing — not a silent
        // recreation-before-submit.
        composeRule.waitUntil(30_000) {
            currentState() != "idle"
        }
        val observedState = currentState()
        assertTrue(
            "expected loading or success before recreation, saw $observedState",
            observedState == "loading" || observedState == "success",
        )
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        assertEquals("-27.4698,153.0251", fieldText("From"))
        assertEquals("-27.3353,152.7720", fieldText("To"))
        UiTestWaits.waitForState(composeRule, "idle")
        composeRule.onNodeWithText("START").assertIsEnabled()
    }

    @Test(timeout = 300_000)
    fun fieldsAndComplexitySurviveSuccessRecreation() {
        enterCoordinates()
        composeRule.onNodeWithText("START").performClick()
        UiTestWaits.waitForState(composeRule, "success")
        KnobRobot.performDetentClicks(composeRule, 1)
        KnobRobot.waitForLevel(composeRule, 1)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        assertEquals("-27.4698,153.0251", fieldText("From"))
        assertEquals("-27.3353,152.7720", fieldText("To"))
        UiTestWaits.waitForState(composeRule, "idle")
        composeRule.onNodeWithContentDescription("Ride complexity level 1").assertExists()
    }

    @Test(timeout = 300_000)
    fun recreationsDuringStyleLoadNeverShowAMapFileError() {
        // The offline style is loaded from a LaunchedEffect keyed on the map URL
        // and guidance style. Cancelling that effect (recomposition or an
        // activity recreation while the asset read is in flight) is not a load
        // failure: the effect rethrows CancellationException. Treating that
        // cancellation as an error used to leave a stale modal
        // "Map file not loaded" dialog covering the live map.
        repeat(3) {
            composeRule.activityRule.scenario.recreate()
            composeRule.waitForIdle()
        }
        // Let any cancelled-then-restarted load finish before looking for a
        // stale error surface.
        Thread.sleep(3_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Map file not loaded").assertDoesNotExist()
        composeRule.onNodeWithText("The offline map style could not be loaded").assertDoesNotExist()
    }

    @Test
    fun openSheetAndDialogDoNotSurviveRecreation() {
        composeRule.onNodeWithContentDescription("Saved routes").performClick()
        composeRule.onNodeWithText("Saved routes").assertExists()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Saved routes").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Planning settings").performClick()
        composeRule.onNodeWithText("Route settings").performClick()
        composeRule.onNodeWithText("Route settings").assertExists()
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Route settings").assertDoesNotExist()
    }

    private fun currentState(): String =
        composeRule.onNode(SemanticsMatcher.keyIsDefined(RouteUiStateKey))
            .fetchSemanticsNode()
            .config[RouteUiStateKey]

    private fun enterCoordinates() {
        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
    }

    private fun fieldText(label: String): String =
        composeRule.onNodeWithContentDescription(label)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

}

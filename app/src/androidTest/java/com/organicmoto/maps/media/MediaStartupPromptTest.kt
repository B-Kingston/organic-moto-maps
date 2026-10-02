package com.organicmoto.maps.media

import android.Manifest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.MainActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue

class MediaStartupPromptTest {
    @get:Rule val location = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    @get:Rule val rule = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalAccess = false
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun rememberAccess() { originalAccess = NotificationListenerAccess.isGranted(context) }
    @After fun restoreAccess() {
        scenario?.close()
        NotificationListenerAccess.setGranted(context, originalAccess)
    }

    @Test fun deniedAccessPromptsOnlyAtStartupAndDismissalSurvivesRecreation() {
        assumeTrue(NotificationListenerAccess.setGranted(context, false))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithText("Enable media player control?").assertIsDisplayed()
        rule.onNodeWithText("Enable notification access").assertIsDisplayed()
        rule.onNodeWithText("Not now").performClick()
        rule.onNodeWithText("START").assertIsDisplayed()
        scenario!!.recreate()
        rule.onNodeWithText("Enable media player control?").assertDoesNotExist()
        rule.onNodeWithText("START").assertIsDisplayed()
    }

    @Test fun grantedAccessDoesNotPromptOnAppOpen() {
        assumeTrue(NotificationListenerAccess.setGranted(context, true))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithText("Enable media player control?").assertDoesNotExist()
        rule.onNodeWithText("START").assertIsDisplayed()
    }
}

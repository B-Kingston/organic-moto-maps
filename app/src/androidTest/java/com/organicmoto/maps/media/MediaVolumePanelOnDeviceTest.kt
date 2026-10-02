package com.organicmoto.maps.media

import com.organicmoto.maps.ui.dismissMediaStartupPrompt
import android.Manifest
import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.MainActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end volume readback while the panel is open: with no notification
 * access the panel targets the real phone music stream, and a change made
 * outside the app (a hardware volume press changes the stream with no app
 * event) must appear in the displayed level within the polling interval.
 *
 * The test restores both the stream level and this app's notification-listener
 * state, and never rewrites another app's listeners.
 */
@RunWith(AndroidJUnit4::class)
class MediaVolumePanelOnDeviceTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private var originalVolume = 0
    private var originalAccess = false

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        originalAccess = NotificationListenerAccess.isGranted(context)
    }

    @After
    fun tearDown() {
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
        NotificationListenerAccess.setGranted(context, originalAccess)
    }

    @Test
    fun openPhoneVolumePanelFollowsAnExternalStreamChange() {
        assumeTrue(
            "notification-listener access could not be revoked in this environment",
            NotificationListenerAccess.setGranted(context, false),
        )
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        assumeTrue("the music stream has no adjustable range on this device", max > min)
        val midpoint = (min + max) / 2
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, midpoint, 0)

        composeRule.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        composeRule.onNodeWithContentDescription("To").performTextInput("-27.3353,152.7720")
        composeRule.onNodeWithText("START").performClick()
        composeRule.waitUntil(120_000) {
            composeRule.onAllNodes(
                androidx.compose.ui.test.SemanticsMatcher.expectValue(
                    com.organicmoto.maps.RouteUiStateKey,
                    "success",
                ),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("RIDE").performClick()
        composeRule.onNodeWithText("END").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Media controls").performClick()
        composeRule.onNodeWithContentDescription("Media control center").assertExists()

        // The panel shows the real phone stream and its real limits.
        composeRule.onNodeWithText("$midpoint / $max").assertExists()
        composeRule.onNodeWithContentDescription("Volume up, Phone").assertExists()

        // A change outside the app (hardware volume) must be observed by the
        // panel's polling, not cached.
        val raised = (midpoint + 1).coerceAtMost(max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, raised, 0)
        org.junit.Assert.assertEquals(
            "the platform did not accept the external volume change",
            raised,
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
        )
        val observedTexts = mutableListOf<String>()
        val observed = waitUntil(10_000) {
            observedTexts.clear()
            observedTexts += composeRule.onAllNodes(
                androidx.compose.ui.test.hasText(" / ", substring = true),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().mapNotNull { node ->
                node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                    ?.joinToString(" ")
            }
            observedTexts.any { it == "$raised / $max" }
        }
        assertTrue(
            "panel never observed the external volume change to $raised; saw $observedTexts",
            observed,
        )
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(200)
        }
        return condition()
    }
}

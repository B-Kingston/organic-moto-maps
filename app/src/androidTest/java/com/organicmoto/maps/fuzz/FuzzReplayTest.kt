package com.organicmoto.maps.fuzz

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.organicmoto.maps.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FuzzReplayTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test(timeout = 300_000)
    fun replayCheckedInSeedWithoutCrash() {
        val seedFile = androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("fuzzSeedFile") ?: "initial.json"
        val executor = FuzzExecutor(
            rule = composeRule,
            onBack = {
                composeRule.activityRule.scenario.onActivity { activity ->
                    activity.onBackPressedDispatcher.onBackPressed()
                }
            },
            onBackgroundForeground = {
                composeRule.activityRule.scenario.recreate()
                composeRule.waitForIdle()
            },
        )
        FuzzSeed.readActions(seedFile).forEach { action ->
            executor.execute(action)
            composeRule.waitForIdle()
        }
    }
}

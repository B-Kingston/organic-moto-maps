package com.organicmoto.maps.fuzz

import com.organicmoto.maps.ui.dismissMediaStartupPrompt
import android.Manifest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import com.organicmoto.maps.MainActivity
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class FuzzMinimizerTest {

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
    fun greedySingleEliminationWritesAReproducibleMinimum() {
        val original = FuzzSeed.readActions("initial.json")
        val minimized = minimize(original, maxTrials = 20) { candidate ->
            reproducesKnobSignal(candidate)
        }

        assertTrue("minimizer must remove at least one action", minimized.size < original.size)
        assertTrue(
            "the minimized sequence must keep the signal",
            minimized.any { it is RotateKnob && it.detents > 0 },
        )
        assertTrue(reproducesKnobSignal(minimized))

        val directory = File(
            InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
            "fuzz_seeds",
        )
        require(directory.mkdirs() || directory.isDirectory) {
            "Could not create minimizer output directory $directory"
        }
        val originalFile = File(directory, "initial.json")
        val minimizedFile = File(directory, "initial.minimized.json")
        originalFile.writeText(FuzzActionCodec.encodeAll(original))
        minimizedFile.writeText(FuzzActionCodec.encodeAll(minimized))
        assertTrue(originalFile.isFile)
        assertTrue(minimizedFile.isFile)
        assertTrue(minimizedFile.readText().contains("\"actions\""))
    }

    private fun reproducesKnobSignal(actions: List<FuzzAction>): Boolean {
        composeRule.activityRule.scenario.recreate()
        waitForScreen()
        val executor = FuzzExecutor(
            rule = composeRule,
            onBack = {},
            onBackgroundForeground = {
                composeRule.activityRule.scenario.recreate()
                waitForScreen()
            },
        )
        // ActivityScenario.recreate preserves rememberSaveable values. Reset
        // the editable inputs and the unbounded dial before each trial.
        runCatching {
            composeRule.onNodeWithContentDescription("From").performTextClearance()
            composeRule.onNodeWithContentDescription("To").performTextClearance()
        }
        executor.execute(RotateKnob(-64))
        composeRule.waitForIdle()
        actions.forEach {
            executor.execute(it)
            composeRule.waitForIdle()
        }
        return composeRule.onAllNodes(
            hasContentDescription("Ride complexity level 1"),
        ).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForScreen() = FuzzWait.waitForScreen(composeRule)

    private fun minimize(
        original: List<FuzzAction>,
        maxTrials: Int,
        reproduces: (List<FuzzAction>) -> Boolean,
    ): List<FuzzAction> {
        var current = original
        var trials = 0
        var index = 0
        while (index < current.size && trials < maxTrials) {
            val candidate = current.toMutableList().apply { removeAt(index) }
            trials++
            if (reproduces(candidate)) {
                current = candidate
            } else {
                index++
            }
        }
        return current
    }
}

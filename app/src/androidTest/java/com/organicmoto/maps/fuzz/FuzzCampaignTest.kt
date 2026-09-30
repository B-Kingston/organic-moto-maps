package com.organicmoto.maps.fuzz

import android.Manifest
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import com.organicmoto.maps.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FuzzCampaignTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test(timeout = 900_000)
    fun runSeededCampaign() {
        val arguments = InstrumentationRegistry.getArguments()
        val seeds = (arguments.getString("fuzzSeeds") ?: "42")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map(String::toLong)
        val steps = (arguments.getString("fuzzSteps") ?: "60").toInt().coerceAtLeast(1)
        val failFast = (arguments.getString("fuzzFailFast") ?: "false").toBoolean()
        // Soft warnings are advisory until they persist across a large share of
        // a campaign, which usually means the oracle caught a real degradation.
        // A single-step hit stays non-fatal (races legitimately occur); when
        // one distinct warning exceeds this share of steps it escalates to a
        // hard failure. Set fuzzWarnFraction=0 to disable escalation.
        val warnFraction = (arguments.getString("fuzzWarnFraction") ?: "0.25").toDouble()
        val warnBudget = if (warnFraction <= 0.0) Int.MAX_VALUE else maxOf(1, kotlin.math.ceil(steps * warnFraction).toInt())
        val reports = mutableListOf<String>()
        val hardFailures = mutableListOf<String>()
        val executor = executor()
        val weights = actionWeights()
        waitForScreen()

        seeds.forEachIndexed { seedIndex, seed ->
            if (seedIndex > 0) {
                composeRule.activityRule.scenario.recreate()
                waitForScreen()
            }
            val random = FuzzRandom(seed)
            val extractor = StateExtractor(composeRule)
            val oracles = FuzzOracles(composeRule)
            val coverage = CoverageTracker()
            val actions = mutableListOf<FuzzAction>()
            val states = mutableListOf<StateFingerprint>()
            var loadingSteps = 0
            var loadingGeneration: Int? = null
            var runError: String? = null
            val softWarnings = mutableListOf<String>()
            val warningCounts = HashMap<String, Int>()

            repeat(steps) { step ->
                val state = try {
                    extractor.extract()
                } catch (failure: Throwable) {
                    runError = "state extraction failed at step $step: ${failure.message}"
                    hardFailures += "seed=$seed $runError"
                    return@repeat
                }
                states += state
                if (state.stateName == "loading") {
                    if (loadingGeneration == state.generation) {
                        loadingSteps++
                    } else {
                        loadingGeneration = state.generation
                        loadingSteps = 1
                    }
                } else {
                    loadingGeneration = null
                    loadingSteps = 0
                }
                val candidates = List(4) { random.nextAction(weights) }
                val action = if (random.nextInt(10) < 3) {
                    random.nextAction(weights)
                } else {
                    candidates.maxBy { coverage.score(state, it) }
                }
                actions += action
                try {
                    executor.execute(action)
                    waitForScreen()
                } catch (failure: Throwable) {
                    runError = "action $action failed at step $step: ${failure.message}"
                    hardFailures += "seed=$seed $runError"
                    if (failFast) return@repeat
                }
                val after = runCatching { extractor.extract() }.getOrNull() ?: state
                hardFailures += oracles.hardFailures(after, loadingSteps, loadingGeneration)
                    .map { "seed=$seed step=$step $it" }
                oracles.softWarnings(after).forEach { warning ->
                    softWarnings += "seed=$seed step=$step $warning"
                    warningCounts[warning] = (warningCounts[warning] ?: 0) + 1
                }
                coverage.record(after, action)
                if (failFast && hardFailures.any { it.startsWith("seed=$seed") }) return@repeat
            }
            warningCounts.filterValues { it > warnBudget }.forEach { (warning, count) ->
                hardFailures +=
                    "seed=$seed PersistentSoftWarning: \"$warning\" fired on $count of " +
                        "$steps steps (budget $warnBudget); persistent soft signals are treated as failures"
            }
            val report = FailureRecorder.record(
                context = InstrumentationRegistry.getInstrumentation().targetContext,
                seed = seed,
                steps = actions.size,
                actions = actions,
                states = states,
                error = runError ?: hardFailures.firstOrNull { it.startsWith("seed=$seed") },
                warnings = softWarnings,
                extractor = extractor,
            )
            println(
                "FUZZ_REPORT seed=$seed path=${report.absolutePath} " +
                    "states=${coverage.discoveredStateCount()} warnings=${softWarnings.size}",
            )
            android.util.Log.i(
                "OrganicMoto.Fuzz",
                "FUZZ_REPORT seed=$seed states=${coverage.discoveredStateCount()} warnings=${softWarnings.size}",
            )
            reports += report.absolutePath
        }

        if (hardFailures.isNotEmpty()) {
            fail("Fuzz hard failures:\n${hardFailures.joinToString("\n")}\nReports:\n${reports.joinToString("\n")}")
        }
    }

    private fun executor(): FuzzExecutor = FuzzExecutor(
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
    private fun waitForScreen() {
        // Gentle polling instead of rule.waitUntil: compose-idle sync can
        // force measure/layout inside a live map draw pass and crash with
        // framework-level IAE (seen in fuzz campaigns on Pixel_10_Pro AVD).
        // A timeout IS a failure: an app that never reaches the route screen
        // (hang, nav dead-end, crash-recovery loop) must surface as a hard
        // failure, not silently let the campaign run against stale state.
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val present = runCatching {
                composeRule.onAllNodes(
                    androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(com.organicmoto.maps.RouteUiStateKey),
                ).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
            if (present) return
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        error("RouteUiStateKey node did not appear within 30s")
    }

    private fun actionWeights(): Map<Class<out FuzzAction>, Int> = mapOf(
        FuzzAction.Click::class.java to 20,
        FuzzAction.LongClick::class.java to 8,
        FuzzAction.TypeText::class.java to 18,
        FuzzAction.SwipeCarousel::class.java to 8,
        FuzzAction.RotateKnob::class.java to 10,
        FuzzAction.TapStart::class.java to 12,
        FuzzAction.Back::class.java to 5,
        FuzzAction.OpenSavedRoutes::class.java to 5,
        FuzzAction.ToggleSettings::class.java to 5,
        FuzzAction.OpenVoiceSettings::class.java to 5,
        FuzzAction.ToggleDarkRideMap::class.java to 5,
        FuzzAction.PanMap::class.java to 6,
        FuzzAction.BackgroundForeground::class.java to 3,
    )
}

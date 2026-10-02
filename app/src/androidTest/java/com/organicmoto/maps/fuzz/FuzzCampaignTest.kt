package com.organicmoto.maps.fuzz

import com.organicmoto.maps.ui.dismissMediaStartupPrompt
import android.Manifest
import android.content.pm.ActivityInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import com.organicmoto.maps.GuidanceActiveKey
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

    @org.junit.Before
    fun dismissStartupSetup() = composeRule.dismissMediaStartupPrompt()

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
        // Coverage gates. Without them a campaign whose actions mostly no-op
        // still reports green, which is the failure mode these exist to catch.
        // 0 disables an individual gate; `fuzzRequireTargets` is a comma list
        // of UiTarget names that must each be genuinely exercised.
        val minEffective = (arguments.getString("fuzzMinEffectiveSteps") ?: "10").toInt()
        val minStates = (arguments.getString("fuzzMinStates") ?: "5").toInt()
        val minMediaPanelStates = (arguments.getString("fuzzMinMediaPanelStates") ?: "0").toInt()
        val requiredTargets = (arguments.getString("fuzzRequireTargets") ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { name ->
                runCatching { UiTarget.valueOf(name) }
                    .getOrElse { fail("fuzzRequireTargets has no UiTarget named $name"); return@getOrElse null }
            }
            .filterNotNull()
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
            enterRideMode(executor)
            val random = FuzzRandom(seed)
            val extractor = StateExtractor(composeRule)
            val oracles = FuzzOracles(composeRule)
            val coverage = CoverageTracker()
            val actions = mutableListOf<FuzzAction>()
            val states = mutableListOf<StateFingerprint>()
            var loadingSteps = 0
            var loadingGeneration: Int? = null
            var runError: String? = null
            var noOps = 0
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
                val outcome = try {
                    val result = executor.execute(action)
                    waitForScreen()
                    result
                } catch (failure: Throwable) {
                    runError = "action $action failed at step $step: ${failure.message}"
                    hardFailures += "seed=$seed $runError"
                    if (failFast) return@repeat
                    FuzzOutcome.UNAVAILABLE
                }
                if (outcome != FuzzOutcome.PERFORMED) noOps++
                val after = runCatching { extractor.extract() }.getOrNull() ?: state
                hardFailures += oracles.hardFailures(after, loadingSteps, loadingGeneration)
                    .map { "seed=$seed step=$step $it" }
                oracles.softWarnings(after).forEach { warning ->
                    softWarnings += "seed=$seed step=$step $warning"
                    warningCounts[warning] = (warningCounts[warning] ?: 0) + 1
                }
                coverage.record(after, action, effective = outcome == FuzzOutcome.PERFORMED)
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
            val summary = "seed=$seed states=${coverage.discoveredStateCount()} " +
                "mediaPanelStates=${coverage.mediaPanelStateCount()} " +
                "effective=${coverage.effectiveStepCount()}/$noOps no-op " +
                "warnings=${softWarnings.size}"
            println("FUZZ_REPORT $summary path=${report.absolutePath}")
            android.util.Log.i("OrganicMoto.Fuzz", "FUZZ_REPORT $summary")
            reports += report.absolutePath

            // A run whose actions mostly no-op still reports green unless the
            // effective work is checked, so coverage collapse fails loudly.
            val effective = coverage.effectiveStepCount()
            if (effective < minEffective) {
                hardFailures += "InsufficientEffectiveSteps: seed=$seed only $effective of " +
                    "${actions.size} steps reached the UI (minimum $minEffective)"
            }
            if (coverage.discoveredStateCount() < minStates) {
                hardFailures += "InsufficientStateCoverage: seed=$seed discovered " +
                    "${coverage.discoveredStateCount()} states (minimum $minStates)"
            }
            if (minMediaPanelStates > 0 && coverage.mediaPanelStateCount() < minMediaPanelStates) {
                hardFailures += "InsufficientMediaPanelCoverage: seed=$seed discovered " +
                    "${coverage.mediaPanelStateCount()} media-panel states " +
                    "(minimum $minMediaPanelStates)"
            }
            requiredTargets.forEach { target ->
                if ((coverage.targetHits()[target] ?: 0) == 0) {
                    hardFailures += "UncoveredTarget: seed=$seed never exercised $target"
                }
            }
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
        onRotateDevice = { orientation ->
            composeRule.activityRule.scenario.onActivity { activity ->
                activity.requestedOrientation = when (orientation) {
                    DeviceOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    DeviceOrientation.LANDSCAPE_LEFT -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    DeviceOrientation.LANDSCAPE_RIGHT -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                }
            }
            composeRule.waitForIdle()
            waitForScreen()
        },
    )

    /**
     * Deterministically reaches the guidance surface before the random steps:
     * the ride-only controls (media panel, END, dark ride map) are otherwise
     * rarely reachable, because random typing keeps invalidating the plan.
     * Uses the same gentle polling as [FuzzWait] instead of compose-idle
     * sync, which can force measure/layout inside a live map draw pass.
     */
    private fun enterRideMode(executor: FuzzExecutor) {
        executor.execute(StartRide)
        FuzzWait.waitForCondition(45_000) { FuzzWait.present(composeRule, hasText("RIDE")) }
        executor.execute(StartRide)
        FuzzWait.waitForCondition(20_000) {
            FuzzWait.present(composeRule, SemanticsMatcher.expectValue(GuidanceActiveKey, true))
        }
    }

    private fun waitForScreen() = FuzzWait.waitForScreen(composeRule)

    private fun actionWeights(): Map<Class<out FuzzAction>, Int> = FuzzWeights.all()
}

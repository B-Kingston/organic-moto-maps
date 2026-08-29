package com.organicmoto.maps.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared rotary-knob driver for every suite that has to cross ride-complexity
 * detents. One source of truth for the synthesized arc gesture: 8 movement
 * steps of PI/32 radians per detent click, starting at the top of the ring,
 * 20 ms between injected events. If the knob's gesture thresholds change,
 * retune here — not per test file.
 *
 * Synthesized drags are occasionally dropped by the input pipeline for no
 * app-code reason, so [performDetentClicks] VERIFIES that each click changed
 * the reported level (retrying up to [MAX_ATTEMPTS_PER_CLICK]) instead of
 * blindly firing N gestures and hoping. A systematically broken rotation
 * still fails all retries and every positive-control assertion stays honest.
 */
object KnobRobot {

    const val LEVEL_DESCRIPTION_PREFIX = "Ride complexity level"

    /** Level-verification window per click; generous for emulator hiccups. */
    private const val CLICK_SETTLE_MS = 8_000L

    private const val MAX_ATTEMPTS_PER_CLICK = 3

    const val STEPS_PER_CLICK = 8
    private const val STEP_ANGLE = Math.PI / 32.0
    private const val EVENT_TIME_MS = 20L

    fun matcher(): SemanticsMatcher =
        hasContentDescription(LEVEL_DESCRIPTION_PREFIX, substring = true)

    /**
     * Rotates [clicks] detents, verifying each individual click registers.
     *
     * [clampAtZero] declares the dial has a hard Fastest stop: targets below
     * zero are considered satisfied once the knob reports exactly 0 (the
     * gesture itself is still injected every iteration, hammering the stop).
     */
    fun performDetentClicks(rule: ComposeTestRule, clicks: Int, clampAtZero: Boolean = false) {
        if (clicks == 0) return
        ensureIdleControlsVisible(rule)
        val direction = if (clicks > 0) 1 else -1
        var achieved = currentLevel(rule) ?: 0
        repeat(abs(clicks)) {
            val wanted = achieved + direction
            var attempts = 0
            while (true) {
                attempts++
                rotateWithGesture(knobInteraction(rule), direction, STEPS_PER_CLICK)
                rule.waitForIdle()
                val reachedWanted = expectLevelWithin(rule, wanted, CLICK_SETTLE_MS)
                val clampedAtFloor = clampAtZero && wanted < 0 && currentLevel(rule) == 0
                if (reachedWanted || clampedAtFloor) break
                check(attempts < MAX_ATTEMPTS_PER_CLICK) {
                    "detent click $attempts toward $wanted failed; knob reports ${currentLevel(rule)}"
                }
            }
            // After a floor-clamped click the knob sits at exactly 0 no matter
            // how negative the running target gets.
            achieved = if (clampAtZero && wanted < 0) 0 else wanted
        }
    }

    /**
     * Crosses [detents] clicks inside one continuous drag (fuzz-executor
     * style). Best-effort by contract: the fuzz campaign treats unexpected
     * intermediate states as signal, so no per-click verification here.
     */
    fun performContinuousDrag(rule: ComposeTestRule, detents: Int) {
        if (detents == 0 || !present(rule)) return
        ensureIdleControlsVisible(rule)
        rotateWithGesture(
            knobInteraction(rule),
            if (detents > 0) 1 else -1,
            abs(detents) * STEPS_PER_CLICK,
        )
        rule.waitForIdle()
    }

    /**
     * Fetches the dial interaction, tolerating the brief windows where the
     * release-spring settle or a row recomposition makes a one-shot strict
     * lookup miss. Verified semantics come afterwards via the level checks.
     */
    private fun knobInteraction(rule: ComposeTestRule): androidx.compose.ui.test.SemanticsNodeInteraction {
        val deadline = android.os.SystemClock.elapsedRealtime() + CLICK_SETTLE_MS
        var lastDump = "<none>"
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val found = rule.onAllNodes(matcher(), useUnmergedTree = true).fetchSemanticsNodes()
            if (found.isNotEmpty()) return rule.onNode(matcher())
            lastDump = describeRoot(rule)
            try {
                Thread.sleep(120)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        error("knobInteraction: dial never appeared within ${CLICK_SETTLE_MS}ms; tree had:\n$lastDump")
    }

    /** Compact structural snapshot for diagnosing where the dial went. */
    private fun describeRoot(rule: ComposeTestRule): String {
        val root = runCatching {
            rule.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        }.getOrElse { return "<no root: ${it.message}>" }
        val descriptions = mutableListOf<String>()
        fun visit(node: androidx.compose.ui.semantics.SemanticsNode, depth: Int) {
            val desc = node.config.getOrElse(
                androidx.compose.ui.semantics.SemanticsProperties.ContentDescription,
            ) { emptyList() }.joinToString("|")
            val text = node.config.getOrElse(
                androidx.compose.ui.semantics.SemanticsProperties.Text,
            ) { emptyList() }.joinToString("|") { it.text }
            if (desc.isNotEmpty() || text.isNotEmpty()) {
                descriptions += " ".repeat(depth) + "[$desc][$text]"
            }
            if (descriptions.size < 80) node.children.forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        return descriptions.joinToString("\n")
    }

    /**
     * Waits until the knob reports exactly [level]. On timeout, fails with
     * every knob description currently in the tree so callers see the actual
     * level reached instead of an opaque timeout.
     */
    fun waitForLevel(rule: ComposeTestRule, level: Int, timeoutMs: Long = CLICK_SETTLE_MS) {
        check(expectLevelWithin(rule, level, timeoutMs)) {
            "waitForLevel($level) timed out; knob reported " +
                rule.onAllNodes(matcher(), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .mapNotNull(::describedLevel)
        }
    }

    private fun expectLevelWithin(rule: ComposeTestRule, level: Int, timeoutMs: Long): Boolean {
        // Manual interval probing instead of rule.waitUntil: the compose-idle
        // synchronization behind waitUntil can force measure/layout while a
        // map draw pass is in flight (framework-level IAE); a relaxed poll
        // never interleaves with traversal.
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        var sawLevel = false
        while (android.os.SystemClock.elapsedRealtime() <= deadline) {
            if (!sawLevel && rule.onAllNodes(hasContentDescription("$LEVEL_DESCRIPTION_PREFIX $level"))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                sawLevel = true
            }
            if (sawLevel) return true
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return sawLevel
    }

    /** Parses the numeric part of the first matching knob description, or null. */
    fun currentLevel(rule: ComposeTestRule): Int? {
        val node = rule.onAllNodes(matcher(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull() ?: return null
        return describedLevel(node)
    }

    fun describedLevel(node: SemanticsNode): Int? {
        val description = node.config.getOrElse(
            androidx.compose.ui.semantics.SemanticsProperties.ContentDescription,
        ) { emptyList() }.firstOrNull() ?: return null
        return description.substringAfterLast(' ').toIntOrNull()
    }

    /**
     * Guarantees the ride-controls box actually contains the dial. While a
     * planner field is focused, its search suggestions REPLACE the knob in
     * that box (RouteScreen.kt swaps content on `searchState.active`), so
     * tests that type coordinates and only then reach for the dial otherwise
     * race the debounce and see a tree without any knob. Dismissing the IME
     * clears focus, which flips the field back to Idle (SearchField.kt
     * onFocusChanged) and restores the dial synchronously.
     */
    private fun ensureIdleControlsVisible(rule: ComposeTestRule) {
        if (!present(rule)) {
            runCatching {
                rule.onNodeWithContentDescription("To").performImeAction()
            }.recoverCatching {
                rule.onNodeWithContentDescription("From").performImeAction()
            }
        }
        rule.waitForIdle()
        // Relaxed presence probe (no rule.waitUntil — see expectLevelWithin).
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        while (!present(rule)) {
            check(android.os.SystemClock.elapsedRealtime() < deadline) {
                "knob never became visible; screen state=${describeRoot(rule)}"
            }
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                error("interrupted while waiting for the dial")
            }
        }
    }

    // No runCatching-swallow here: a throwing query must surface as itself,
    // not silently read as 'knob absent' forever.
    private fun present(rule: ComposeTestRule): Boolean =
        rule.onAllNodes(matcher(), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun rotateWithGesture(
        knob: androidx.compose.ui.test.SemanticsNodeInteraction,
        direction: Int,
        steps: Int,
    ) {
        val bounds = knob.fetchSemanticsNode().boundsInRoot
        knob.performTouchInput {
            val center = Offset(bounds.width / 2f, bounds.height / 2f)
            val radius = bounds.width.coerceAtMost(bounds.height) * 0.45f
            val startAngle = Math.PI / 2.0
            down(center + polar(radius, startAngle))
            advanceEventTime(EVENT_TIME_MS)
            repeat(steps) { step ->
                val angle = startAngle + direction * (step + 1) * STEP_ANGLE
                moveTo(center + polar(radius, angle))
                advanceEventTime(EVENT_TIME_MS)
            }
            up()
        }
    }

    private fun polar(radius: Float, angle: Double): Offset =
        Offset((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())
}

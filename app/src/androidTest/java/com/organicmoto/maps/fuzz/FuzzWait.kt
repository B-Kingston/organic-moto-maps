package com.organicmoto.maps.fuzz

import android.os.SystemClock
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.organicmoto.maps.RouteUiStateKey

/**
 * Gentle waiting for fuzz suites.
 *
 * `rule.waitUntil` forces compose-idle sync, which can trigger measure/layout
 * inside a live map draw pass and crash with a framework-level IAE on the
 * Pixel_10_Pro AVD. Polling for the semantics node instead avoids that, and a
 * timeout is still a hard failure: a screen that never appears (hang, nav
 * dead-end, crash-recovery loop) must surface rather than leave the campaign
 * running against stale state.
 */
object FuzzWait {

    fun waitForScreen(rule: ComposeTestRule, timeoutMs: Long = 30_000L) {
        if (!waitForCondition(timeoutMs) { present(rule, routeRoot()) }) {
            error("RouteUiStateKey node did not appear within ${timeoutMs}ms")
        }
    }

    fun waitForCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            if (!sleep()) return condition()
        }
        return condition()
    }

    fun present(rule: ComposeTestRule, matcher: SemanticsMatcher): Boolean =
        runCatching {
            rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)

    private fun routeRoot(): SemanticsMatcher = SemanticsMatcher.keyIsDefined(RouteUiStateKey)

    private fun sleep(): Boolean = try {
        Thread.sleep(150)
        true
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}

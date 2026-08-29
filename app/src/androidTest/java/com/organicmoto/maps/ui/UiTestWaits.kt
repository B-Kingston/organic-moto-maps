package com.organicmoto.maps.ui

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.organicmoto.maps.RouteUiStateKey

/** Shared UI-test helpers: one home for waits every suite repeats. */
object UiTestWaits {
    /** Waits until the route screen reports [state] via [RouteUiStateKey]. */
    fun waitForState(rule: ComposeTestRule, state: String) {
        rule.waitUntil(300_000) {
            rule.onAllNodes(SemanticsMatcher.expectValue(RouteUiStateKey, state))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }
}

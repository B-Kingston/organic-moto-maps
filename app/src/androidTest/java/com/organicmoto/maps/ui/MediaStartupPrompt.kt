package com.organicmoto.maps.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

/** Ordinary planner suites skip optional setup; dedicated prompt tests exercise it. */
fun ComposeTestRule.dismissMediaStartupPrompt() {
    if (onAllNodes(androidx.compose.ui.test.hasText("Not now")).fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithText("Not now").performClick()
    }
}

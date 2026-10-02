package com.organicmoto.maps.map

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.organicmoto.maps.MapInstalledKey
import com.organicmoto.maps.MapReadyKey
import org.junit.Assert.assertTrue

/** Call after startup prompts are dismissed, before route/camera assertions. */
fun ComposeContentTestRule.requireOfflineMapReady(timeoutMillis: Long = 15_000) {
    fun installed() = onAllNodes(SemanticsMatcher.expectValue(MapInstalledKey, true))
        .fetchSemanticsNodes().isNotEmpty()
    fun ready() = onAllNodes(SemanticsMatcher.expectValue(MapReadyKey, true))
        .fetchSemanticsNodes().isNotEmpty()
    try {
        waitUntil(timeoutMillis) { installed() }
    } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("Map prerequisite failed: installed-map semantics never became true", error)
    }
    try {
        waitUntil(timeoutMillis) { ready() }
    } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("Map prerequisite failed: archive installed=${installed()}, style ready=${ready()}", error)
    }
    assertTrue("Map prerequisite failed: installed archive disappeared while style loaded", installed())
}

package com.organicmoto.maps.ui

import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeTestRule
import com.organicmoto.maps.ActiveRegionKey
import com.organicmoto.maps.MapInstalledKey
import com.organicmoto.maps.MapAppliedStyleMatchesRequestKey
import com.organicmoto.maps.MapAppliedStyleReadyKey
import com.organicmoto.maps.MapLoadFailureKey
import com.organicmoto.maps.MapNativeStyleReadyKey
import com.organicmoto.maps.MapReadyKey
import com.organicmoto.maps.MapScreenStartedKey
import com.organicmoto.maps.MapStyleJsonReadyKey
import com.organicmoto.maps.MapStyleLoadedKey
import com.organicmoto.maps.MapStyleLoadErrorKey
import com.organicmoto.maps.MapStyleLoadGenerationKey
import com.organicmoto.maps.MapStyleLoadStartedKey
import com.organicmoto.maps.MapStyleMatchesRequestKey
import com.organicmoto.maps.tiles.OfflineTileStore
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry

/** Map camera instrumentation fails at the fixture/style stage with live evidence. */
internal object CameraMapPrerequisites {

    fun awaitReady(rule: ComposeTestRule, cameraSnapshot: () -> String? = { null }) {
        try {
            rule.waitUntil(60_000) { boolState(rule, MapInstalledKey) == "true" }
        } catch (failure: Throwable) {
            fail("offline basemap was not installed in the launched app", rule, cameraSnapshot, failure)
        }
        rule.onNode(SemanticsMatcher.expectValue(MapInstalledKey, true), useUnmergedTree = true).assertExists()

        try {
            rule.waitUntil(60_000) { boolState(rule, MapStyleJsonReadyKey) == "true" }
        } catch (failure: Throwable) {
            fail("offline map style JSON was not prepared", rule, cameraSnapshot, failure)
        }
        // The style JSON is loaded from assets on Dispatchers.IO and then
        // published into Compose state. Force one real Compose frame before
        // waiting for the LaunchedEffect that calls MapLibre setStyle; a
        // semantics poll can observe the new state before that effect runs.
        rule.mainClock.advanceTimeByFrame()

        try {
            rule.waitUntil(10_000) {
                boolState(rule, MapStyleLoadStartedKey) == "true" ||
                    boolState(rule, MapReadyKey) == "true"
            }
        } catch (failure: Throwable) {
            fail("style JSON was ready but the native style request did not start", rule, cameraSnapshot, failure)
        }

        try {
            rule.waitUntil(60_000) { boolState(rule, MapReadyKey) == "true" }
        } catch (failure: Throwable) {
            fail("native map/style did not become ready", rule, cameraSnapshot, failure)
        }
        rule.onNode(SemanticsMatcher.expectValue(MapReadyKey, true), useUnmergedTree = true).assertExists()
    }

    private fun fail(
        stage: String,
        rule: ComposeTestRule,
        cameraSnapshot: () -> String?,
        cause: Throwable,
    ): Nothing {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fallbackTiles = File(context.filesDir, "tiles/basemap.pmtiles")
        val fallbackDetails = runCatching {
            val bytes = fallbackTiles.length()
            val validHeader = fallbackTiles.isFile && OfflineTileStore.hasValidHeader(fallbackTiles.inputStream())
            "path=${fallbackTiles.absolutePath}, exists=${fallbackTiles.isFile}, bytes=$bytes, validHeader=$validHeader"
        }.getOrElse { "path=${fallbackTiles.absolutePath}, probeError=${it.message}" }
        val diagnostic = buildString {
            append("$stage; ")
            append("activeRegion=${stringState(rule, ActiveRegionKey)}, ")
            append("fallbackTiles={$fallbackDetails}, ")
            append("mapInstalled=${boolState(rule, MapInstalledKey)}, ")
            append("mapReady=${boolState(rule, MapReadyKey)}, ")
            append("styleJsonReady=${boolState(rule, MapStyleJsonReadyKey)}, ")
            append("nativeMapStyleReady=${boolState(rule, MapNativeStyleReadyKey)}, ")
            append("styleLoaded=${boolState(rule, MapStyleLoadedKey)}, ")
            append("styleMatchesRequest=${boolState(rule, MapStyleMatchesRequestKey)}, ")
            append("screenStarted=${boolState(rule, MapScreenStartedKey)}, ")
            append("styleLoadStarted=${boolState(rule, MapStyleLoadStartedKey)}, ")
            append("styleLoadGeneration=${intState(rule, MapStyleLoadGenerationKey)}, ")
            append("appliedStyleReady=${boolState(rule, MapAppliedStyleReadyKey)}, ")
            append("appliedStyleMatchesRequest=${boolState(rule, MapAppliedStyleMatchesRequestKey)}, ")
            append("styleLoadError=${stringState(rule, MapStyleLoadErrorKey)}, ")
            append("loadFailure=${stringState(rule, MapLoadFailureKey)}, ")
            append("camera=${cameraSnapshot()}")
        }
        throw AssertionError(diagnostic, cause)
    }

    private fun boolState(rule: ComposeTestRule, key: SemanticsPropertyKey<Boolean>): String =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(key), useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull()?.config?.getOrElse(key) { false }?.toString() ?: "<absent>"

    private fun stringState(rule: ComposeTestRule, key: SemanticsPropertyKey<String>): String =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(key), useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull()?.config?.getOrElse(key) { "" } ?: "<absent>"

    private fun intState(rule: ComposeTestRule, key: SemanticsPropertyKey<Int>): String =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(key), useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull()?.config?.getOrElse(key) { 0 }?.toString() ?: "<absent>"
}

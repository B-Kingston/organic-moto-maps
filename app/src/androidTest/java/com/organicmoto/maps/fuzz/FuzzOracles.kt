package com.organicmoto.maps.fuzz

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import com.organicmoto.maps.MapReadyKey
import com.organicmoto.maps.RouteUiStateKey

/** Oracle checks for the route screen fuzz campaign. */
class FuzzOracles(private val rule: ComposeTestRule) {

    fun hardFailures(state: StateFingerprint, loadingSteps: Int, loadingGeneration: Int?): List<String> {
        if (!hasComposeHierarchy()) return listOf("NoComposeHierarchy: route screen disappeared")
        val failures = mutableListOf<String>()
        if (state.stateName == "loading" && loadingSteps > 20 && loadingGeneration == state.generation) {
            failures += "NoPermanentLoading: loading persisted for $loadingSteps steps at generation ${state.generation}"
        }
        if (state.routeCount > 0) {
            if (state.selectedIndex != state.focusedRouteIndex) {
                failures += "CarouselMatchesMap: selected=${state.selectedIndex} focused=${state.focusedRouteIndex}"
            }
            if (state.routeCount != state.mapRouteCount) {
                failures += "CarouselMatchesMap: cards=${state.routeCount} map=${state.mapRouteCount}"
            }
        }
        val blockingDialogs = listOf(
            hasText("Delete saved route?"),
            hasText("Route settings"),
        ).sumOf { matcher ->
            rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size
        }
        if (blockingDialogs > 1) failures += "NoDuplicateBlockingDialogs: $blockingDialogs dialogs"

        val startOrEnd = if (state.guidanceActive) {
            rule.onAllNodes(hasContentDescription("End navigation").and(hasClickAction()))
        } else {
            rule.onAllNodes(hasText("START").and(hasClickAction()))
        }
        if (startOrEnd.fetchSemanticsNodes().isEmpty()) {
            failures += if (state.guidanceActive) {
                "EndNavigationReachable: END has no click action"
            } else {
                "StartReachable: START has no click action"
            }
        }
        val darkMapControls = rule.onAllNodes(
            hasContentDescription("Dark ride map", substring = true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        if (state.guidanceActive) {
            if (darkMapControls.size != 1) {
                failures += "DarkRideMapAccessible: expected one guidance toggle, found ${darkMapControls.size}"
            } else {
                val darkMapNode = darkMapControls.single()
                val clickableToggle = rule.onAllNodes(
                    hasContentDescription("Dark ride map", substring = true).and(hasClickAction()),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                if (clickableToggle.isEmpty()) {
                    failures += "DarkRideMapAccessible: toggle has no click action"
                }
                val minSizePx = 48f * rule.density.density
                if (darkMapNode.boundsInRoot.width < minSizePx ||
                    darkMapNode.boundsInRoot.height < minSizePx
                ) {
                    failures += "DarkRideMapAccessible: target smaller than 48 dp"
                }
                val expectedDescription = if (state.darkRideMapEnabled) {
                    "Dark ride map, on"
                } else {
                    "Dark ride map, off"
                }
                val descriptions = darkMapNode.config.getOrElse(
                    SemanticsProperties.ContentDescription,
                ) { emptyList() }
                if (expectedDescription !in descriptions) {
                    failures += "DarkRideMapStateVisible: expected $expectedDescription, found $descriptions"
                }
            }
        } else if (darkMapControls.isNotEmpty()) {
            failures += "DarkRideMapGuidanceOnly: toggle visible outside guidance"
        }
        if (rule.onAllNodes(hasText("OpenMapTiles.org", substring = true)).fetchSemanticsNodes().isEmpty()) {
            failures += "AttributionVisible: attribution is missing"
        }
        if (state.voiceSettingsOpen) {
            listOf("Spoken turn guidance", "Announcement interval", "Preview next").forEach { description ->
                if (rule.onAllNodes(
                        androidx.compose.ui.test.hasContentDescription(description, substring = true),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes().isEmpty()
                ) {
                    failures += "VoiceSettingsAccessible: missing $description control"
                }
            }
        }
        return failures
    }

    private fun hasComposeHierarchy(): Boolean =
        runCatching {
            rule.onAllNodes(
                SemanticsMatcher.keyIsDefined(RouteUiStateKey),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)

    fun softWarnings(state: StateFingerprint): List<String> = buildList {
        if (state.stateName == "success" && state.mapInstalled && !state.mapReady) {
            add("MapReadyBeforeRouteRender: route success before map readiness")
        }
        if (state.guidanceActive && state.darkRideMapEnabled && !state.darkGuidanceStyleReady) {
            add("DarkGuidanceStyleLoading: selected dark style has not loaded yet")
        }
    }
}

package com.organicmoto.maps.fuzz

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
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

        if (rule.onAllNodes(hasText("START").and(hasClickAction())).fetchSemanticsNodes().isEmpty()) {
            failures += "StartReachable: START has no click action"
        }
        if (rule.onAllNodes(hasText("OpenMapTiles.org", substring = true)).fetchSemanticsNodes().isEmpty()) {
            failures += "AttributionVisible: attribution is missing"
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
        if (state.stateName == "success" && !state.mapReady) {
            add("MapReadyBeforeRouteRender: route success before map readiness")
        }
    }
}

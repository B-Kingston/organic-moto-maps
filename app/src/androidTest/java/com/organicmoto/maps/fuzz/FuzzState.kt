package com.organicmoto.maps.fuzz

import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasText
import com.organicmoto.maps.FocusedRouteIndexKey
import com.organicmoto.maps.MapReadyKey
import com.organicmoto.maps.MapInstalledKey
import com.organicmoto.maps.GuidanceActiveKey
import com.organicmoto.maps.DarkRideMapEnabledKey
import com.organicmoto.maps.DarkGuidanceStyleReadyKey
import com.organicmoto.maps.MapRouteCountKey
import com.organicmoto.maps.MediaCenterStatusKey
import com.organicmoto.maps.MediaPanelOpenKey
import com.organicmoto.maps.RouteCountKey
import com.organicmoto.maps.RouteGenerationKey
import com.organicmoto.maps.RouteUiStateKey
import com.organicmoto.maps.SelectedRouteKey

/** Observable route-screen state used for fuzz coverage and failure reports. */
data class StateFingerprint(
    val stateName: String,
    val generation: Int,
    val selectedIndex: Int,
    val routeCount: Int,
    val complexityLevel: Int,
    val fromText: String,
    val toText: String,
    val mapReady: Boolean,
    val mapInstalled: Boolean,
    val focusedRouteIndex: Int,
    val mapRouteCount: Int,
    val dialog: String?,
    val sheetOpen: Boolean,
    val settingsMenuOpen: Boolean,
    val voiceSettingsOpen: Boolean,
    val guidanceActive: Boolean,
    val darkRideMapEnabled: Boolean,
    val darkGuidanceStyleReady: Boolean,
    val mediaPanelOpen: Boolean,
    val mediaCenterStatus: String,
    val mapsSettingsOpen: Boolean,
)

class StateExtractor(private val rule: ComposeTestRule) {

    fun extract(): StateFingerprint {
        val root = nodeWith(RouteUiStateKey) ?: error("route root semantics are missing")
        val map = nodeWith(MapReadyKey)
        return StateFingerprint(
            stateName = root.config.valueOrDefault(RouteUiStateKey, "unknown"),
            generation = root.config.valueOrDefault(RouteGenerationKey, 0),
            selectedIndex = root.config.valueOrDefault(SelectedRouteKey, 0),
            routeCount = root.config.valueOrDefault(RouteCountKey, 0),
            complexityLevel = complexityLevel(),
            fromText = editableText("From"),
            toText = editableText("To"),
            mapReady = map?.let { it.config.valueOrDefault(MapReadyKey, false) } ?: false,
            mapInstalled = root.config.valueOrDefault(MapInstalledKey, false),
            focusedRouteIndex = map?.let { it.config.valueOrDefault(FocusedRouteIndexKey, 0) } ?: 0,
            mapRouteCount = map?.let { it.config.valueOrDefault(MapRouteCountKey, 0) } ?: 0,
            dialog = dialogText(),
            sheetOpen = rule.onAllNodes(hasText("Saved routes")).fetchSemanticsNodes().isNotEmpty(),
            settingsMenuOpen = rule.onAllNodes(hasText("Sound settings")).fetchSemanticsNodes().isNotEmpty(),
            voiceSettingsOpen = rule.onAllNodes(
                hasText("Spoken turn guidance"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty(),
            guidanceActive = root.config.valueOrDefault(GuidanceActiveKey, false),
            darkRideMapEnabled = root.config.valueOrDefault(DarkRideMapEnabledKey, false),
            darkGuidanceStyleReady = root.config.valueOrDefault(DarkGuidanceStyleReadyKey, false),
            mediaPanelOpen = root.config.valueOrDefault(MediaPanelOpenKey, false),
            mediaCenterStatus = root.config.valueOrDefault(MediaCenterStatusKey, "unknown"),
            mapsSettingsOpen = rule.onAllNodes(
                hasContentDescription("Map server address"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    fun semanticsDump(): String =
        runCatching { rule.onRoot(useUnmergedTree = true).fetchSemanticsNode().toString() }
            .getOrElse { "<compose hierarchy unavailable: ${it.message}>" }

    private fun nodeWith(key: androidx.compose.ui.semantics.SemanticsPropertyKey<*>): SemanticsNode? =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(key), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .firstOrNull()

    private fun editableText(label: String): String =
        rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().firstOrNull { node ->
            node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                .any { it == label }
        }?.config?.getOrElse(SemanticsProperties.EditableText) { androidx.compose.ui.text.AnnotatedString("") }
            ?.text
            .orEmpty()

    private fun complexityLevel(): Int {
        val descriptions = rule.onAllNodes(
            hasContentDescription("Ride complexity level", substring = true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        return descriptions.firstNotNullOfOrNull { node ->
            node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                .firstOrNull()
                ?.substringAfterLast(' ')
                ?.toIntOrNull()
        } ?: 0
    }

    private fun dialogText(): String? {
        val dialogs = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        return dialogs.asSequence()
            .flatMap { node -> node.allText().asSequence() }
            .firstOrNull()
    }

    private fun SemanticsNode.allText(): List<String> = buildList {
        addAll(config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text })
        children.forEach { addAll(it.allText()) }
    }

    private fun <T> SemanticsConfiguration.valueOrDefault(
        key: androidx.compose.ui.semantics.SemanticsPropertyKey<T>,
        default: T,
    ): T = getOrElse(key) { default }
}

/** Weighted state/action novelty tracker used by seeded campaigns. */
class CoverageTracker {
    private val visits = mutableMapOf<Pair<StateFingerprint, UiTarget>, Int>()
    private val states = mutableSetOf<StateFingerprint>()
    private val transitions = mutableSetOf<Pair<StateFingerprint, StateFingerprint>>()
    private var previousState: StateFingerprint? = null
    private var previousAction: FuzzAction? = null
    private val recentActions = ArrayDeque<FuzzAction>()
    private val hits = mutableMapOf<UiTarget, Int>()
    private var effectiveSteps = 0

    fun score(state: StateFingerprint, action: FuzzAction): Int {
        val target = action.target ?: UiTarget.MAP
        var score = 0
        if (state !in states) score += 10
        previousState?.let { before ->
            if (before != state && (before to state) !in transitions) score += 8
        }
        if ((state to target) !in visits) score += 5
        if (action is RotateKnob && action.detents !in recentActions.mapNotNull { (it as? RotateKnob)?.detents }) {
            score += 5
        }
        val visitsForAction = visits[state to target] ?: 0
        score += if (visitsForAction == 0) 5 else -10 * visitsForAction
        if (previousAction == action) score -= 5
        return score
    }

    fun record(state: StateFingerprint, action: FuzzAction, effective: Boolean) {
        if (effective) {
            effectiveSteps++
            action.target?.let { hits[it] = (hits[it] ?: 0) + 1 }
        }
        val target = action.target ?: UiTarget.MAP
        visits[state to target] = (visits[state to target] ?: 0) + 1
        states += state
        previousState?.let { before -> if (before != state) transitions += before to state }
        previousState = state
        previousAction = action
        recentActions.addLast(action)
        while (recentActions.size > 8) recentActions.removeFirst()
    }

    fun discoveredStateCount(): Int = states.size

    /** How many distinct states were observed with the media panel open. */
    fun mediaPanelStateCount(): Int = states.count { it.mediaPanelOpen }

    /** Steps that actually reached the UI, excluding no-ops. */
    fun effectiveStepCount(): Int = effectiveSteps

    /** How many times each target was genuinely exercised. */
    fun targetHits(): Map<UiTarget, Int> = hits.toMap()
}

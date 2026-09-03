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
import com.organicmoto.maps.MapRouteCountKey
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

    fun score(state: StateFingerprint, action: FuzzAction): Int {
        val target = action.targetOrNull() ?: UiTarget.MAP
        var score = 0
        if (state !in states) score += 10
        previousState?.let { before ->
            if (before != state && (before to state) !in transitions) score += 8
        }
        if ((state to target) !in visits) score += 5
        if (action is FuzzAction.RotateKnob && action.detents !in recentActions.mapNotNull { (it as? FuzzAction.RotateKnob)?.detents }) {
            score += 5
        }
        val visitsForAction = visits[state to target] ?: 0
        score += if (visitsForAction == 0) 5 else -10 * visitsForAction
        if (previousAction == action) score -= 5
        return score
    }

    fun record(state: StateFingerprint, action: FuzzAction) {
        val target = action.targetOrNull() ?: UiTarget.MAP
        visits[state to target] = (visits[state to target] ?: 0) + 1
        states += state
        previousState?.let { before -> if (before != state) transitions += before to state }
        previousState = state
        previousAction = action
        recentActions.addLast(action)
        while (recentActions.size > 8) recentActions.removeFirst()
    }

    fun discoveredStateCount(): Int = states.size

    private fun FuzzAction.targetOrNull(): UiTarget? = when (this) {
        is FuzzAction.Click -> target
        is FuzzAction.LongClick -> target
        is FuzzAction.TypeText -> target
        is FuzzAction.SwipeCarousel -> UiTarget.CAROUSEL
        is FuzzAction.RotateKnob -> UiTarget.KNOB
        FuzzAction.TapStart -> UiTarget.START
        FuzzAction.Back -> null
        FuzzAction.OpenSavedRoutes -> UiTarget.SAVED_ROUTES
        FuzzAction.ToggleSettings -> UiTarget.SETTINGS_COG
        is FuzzAction.PanMap -> UiTarget.MAP
        FuzzAction.BackgroundForeground -> null
    }
}

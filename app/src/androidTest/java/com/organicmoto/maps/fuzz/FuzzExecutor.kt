package com.organicmoto.maps.fuzz

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.organicmoto.maps.MapReadyKey
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Executes fuzz actions through semantics instead of screen coordinates. */
class FuzzExecutor(
    private val rule: ComposeTestRule,
    private val onBack: () -> Unit,
    private val onBackgroundForeground: () -> Unit,
) {

    fun execute(action: FuzzAction) {
        when (action) {
            is FuzzAction.Click -> click(action.target)
            is FuzzAction.LongClick -> longClick(action.target)
            is FuzzAction.TypeText -> typeText(action.target, action.value)
            is FuzzAction.SwipeCarousel -> swipeCarousel(action.direction)
            is FuzzAction.RotateKnob -> rotateKnob(action.detents)
            FuzzAction.Back -> back()
            FuzzAction.TapStart -> click(UiTarget.START)
            FuzzAction.OpenSavedRoutes -> click(UiTarget.SAVED_ROUTES)
            FuzzAction.ToggleSettings -> click(UiTarget.SETTINGS_COG)
            FuzzAction.OpenVoiceSettings -> click(UiTarget.VOICE_SETTINGS)
            FuzzAction.ToggleDarkRideMap -> click(UiTarget.DARK_RIDE_MAP)
            is FuzzAction.PanMap -> panMap(action.direction)
            FuzzAction.BackgroundForeground -> onBackgroundForeground()
        }
    }

    private fun click(target: UiTarget) {
        val matcher = targetMatcher(target) ?: return
        performIfPresent(matcher) { it.performClick() }
    }

    private fun longClick(target: UiTarget) {
        val matcher = targetMatcher(target) ?: return
        performIfPresent(matcher) { it.performTouchInput { longClick() } }
    }

    private fun typeText(target: UiTarget, value: String) {
        val matcher = when (target) {
            UiTarget.FROM_FIELD -> hasContentDescription("From")
            UiTarget.TO_FIELD -> hasContentDescription("To")
            UiTarget.COMMENT_FIELD -> hasText("Add a comment")
            else -> return
        }
        performIfPresent(matcher) { it.performTextInput(value) }
    }

    private fun swipeCarousel(direction: Direction) {
        val matcher = hasContentDescription("Route 1:", substring = true)
        performIfPresent(matcher) { interaction ->
            interaction.performTouchInput {
                if (direction == Direction.LEFT) swipeLeft() else swipeRight()
            }
        }
    }

    private fun panMap(direction: Direction) {
        val matcher = SemanticsMatcher.keyIsDefined(MapReadyKey)
        performIfPresent(matcher) { interaction ->
            interaction.performTouchInput {
                when (direction) {
                    Direction.LEFT -> swipeLeft()
                    Direction.RIGHT -> swipeRight()
                    Direction.UP -> swipeUp()
                    Direction.DOWN -> swipeDown()
                }
            }
        }
    }

    private fun back() {
        if (present(hasText("Saved routes"))) {
            if (present(hasContentDescription("Close saved routes"))) {
                rule.onNode(hasContentDescription("Close saved routes")).performClick()
            } else {
                onBack()
            }
            return
        }
        if (present(hasText("Delete saved route?"))) {
            performIfPresent(hasText("Cancel")) { it.performClick() }
            return
        }
        if (present(hasText("Route settings"))) {
            performIfPresent(hasText("CANCEL")) { it.performClick() }
            return
        }
        if (present(hasText("Voice guidance"))) {
            performIfPresent(hasText("CANCEL")) { it.performClick() }
            return
        }
        val hasDialog = present(
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.IsDialog),
        )
        if (hasDialog) onBack()
    }

    private fun rotateKnob(detents: Int) {
        // Delegated: one continuous drag crossing N clicks, identical physics
        // with the planner-panel suites via KnobRobot.
        com.organicmoto.maps.ui.KnobRobot.performContinuousDrag(rule, detents)
    }

    private fun targetMatcher(target: UiTarget): SemanticsMatcher? = when (target) {
        UiTarget.FROM_FIELD -> hasContentDescription("From")
        UiTarget.TO_FIELD -> hasContentDescription("To")
        UiTarget.START -> hasText("START")
        UiTarget.KNOB -> hasContentDescription("Ride complexity level", substring = true)
        UiTarget.CAROUSEL, UiTarget.CARD_0 -> hasContentDescription("Route 1:", substring = true)
        UiTarget.CARD_1 -> hasContentDescription("Route 2:", substring = true)
        UiTarget.CARD_2 -> hasContentDescription("Route 3:", substring = true)
        UiTarget.MAP -> SemanticsMatcher.keyIsDefined(MapReadyKey)
        UiTarget.SAVED_ROUTES -> hasContentDescription("Saved routes")
        UiTarget.SETTINGS_COG -> hasContentDescription("Route settings")
        UiTarget.VOICE_SETTINGS -> hasContentDescription("Voice guidance settings", substring = true)
        UiTarget.DARK_RIDE_MAP -> hasContentDescription("Dark ride map", substring = true)
        UiTarget.RIDE -> hasText("RIDE")
        UiTarget.END_NAVIGATION -> hasContentDescription("End navigation")
        UiTarget.SAVE_BUTTON -> hasContentDescription("Save route")
        UiTarget.COMMENT_FIELD -> hasText("Add a comment")
        UiTarget.ADD_COMMENT -> hasContentDescription("Add comment")
        UiTarget.DELETE_CONFIRM -> hasText("Delete")
    }

    private fun present(matcher: SemanticsMatcher): Boolean =
        runCatching { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)

    private fun performIfPresent(
        matcher: SemanticsMatcher,
        action: (androidx.compose.ui.test.SemanticsNodeInteraction) -> Unit,
    ) {
        if (present(matcher)) action(rule.onNode(matcher))
    }

    private fun polar(radius: Float, angle: Double): Offset =
        Offset((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())
}

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
        val hasDialog = present(
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.IsDialog),
        )
        if (hasDialog) onBack()
    }

    private fun rotateKnob(detents: Int) {
        val matcher = hasContentDescription("Ride complexity level", substring = true)
        if (!present(matcher) || detents == 0) return
        val interaction = rule.onNode(matcher)
        val bounds = interaction.fetchSemanticsNode().boundsInRoot
        interaction.performTouchInput {
            val center = Offset(bounds.width / 2f, bounds.height / 2f)
            val radius = bounds.width.coerceAtMost(bounds.height) * 0.45f
            val direction = if (detents >= 0) 1 else -1
            val startAngle = Math.PI / 2.0
            down(center + polar(radius, startAngle))
            advanceEventTime(20)
            repeat(abs(detents) * 8) { step ->
                val angle = startAngle + direction * (step + 1) * Math.PI / 32.0
                moveTo(center + polar(radius, angle))
                advanceEventTime(20)
            }
            up()
        }
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

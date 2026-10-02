package com.organicmoto.maps.fuzz

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.organicmoto.maps.MapReadyKey
import androidx.test.espresso.Espresso

/** Whether a step actually reached the UI, so no-ops can be scored honestly. */
enum class FuzzOutcome {
    /** The action was applied to a real node. */
    PERFORMED,

    /** The node exists but is not currently interactive (e.g. loading). */
    UNAVAILABLE,

    /** The node is not on screen at all in this state. */
    NO_SUCH_TARGET,
}

/** Executes fuzz actions through semantics instead of screen coordinates. */
class FuzzExecutor(
    private val rule: ComposeTestRule,
    private val onBack: () -> Unit,
    private val onBackgroundForeground: () -> Unit,
    private val onRotateDevice: (DeviceOrientation) -> Unit = {},
) {

    fun execute(action: FuzzAction): FuzzOutcome = when (action) {
        is Click -> click(action.target)
        is LongClick -> longClick(action.target)
        is TypeText -> typeText(action.target, action.value)
        is SwipeCarousel -> swipeCarousel(action.direction)
        is PanMap -> panMap(action.direction)
        is TiltMap -> tiltMap(action.increase)
        is RotateKnob -> rotateKnob(action.detents)
        is AdjustRoadShare -> adjustRoadShare(action.percent)
        is PressMedia -> pressMedia(action.command)
        is AdjustVolume -> adjustVolume(action.increase)
        is SelectPlayer -> selectPlayer(action.index)
        is DeleteSavedRoute -> deleteSavedRoute(action.cardIndex)
        is RotateDevice -> rotateDevice(action.orientation)
        Back -> back()
        TapStart -> click(UiTarget.START)
        UseCurrentLocation -> click(UiTarget.CURRENT_LOCATION)
        StartRide -> startRide()
        OpenSavedRoutes -> click(UiTarget.SAVED_ROUTES)
        ToggleSettings -> toggleSettingsMenu()
        OpenRouteSettings -> openRouteSettings()
        ApplyRouteSettings -> click(UiTarget.APPLY_ROUTE_SETTINGS)
        ToggleBlockUnpaved -> click(UiTarget.BLOCK_UNPAVED)
        OpenVoiceSettings -> openVoiceSettings()
        ToggleDarkRideMap -> click(UiTarget.DARK_RIDE_MAP)
        ToggleMediaPanel -> toggleMediaPanel()
        OpenMapsSettings -> openMapsSettings()
        AddComment -> addComment()
        BackgroundForeground -> {
            onBackgroundForeground()
            FuzzOutcome.PERFORMED
        }
    }

    private fun click(target: UiTarget): FuzzOutcome {
        val matcher = targetMatcher(target) ?: return FuzzOutcome.NO_SUCH_TARGET
        return performIfPresent(matcher) { it.performClick() }
    }

    private fun longClick(target: UiTarget): FuzzOutcome {
        val matcher = targetMatcher(target) ?: return FuzzOutcome.NO_SUCH_TARGET
        return performIfPresent(matcher) { it.performTouchInput { longClick() } }
    }

    private fun typeText(target: UiTarget, value: String): FuzzOutcome {
        val matcher = when (target) {
            UiTarget.FROM_FIELD -> hasContentDescription("From")
            UiTarget.TO_FIELD -> hasContentDescription("To")
            UiTarget.COMMENT_FIELD -> hasText("Add a comment")
            else -> return FuzzOutcome.NO_SUCH_TARGET
        }
        return performIfPresent(matcher) { it.performTextInput(value) }
    }

    /**
     * Types a comment into the open saved-route sheet. The comment field only
     * exists once a route is expanded, so this opens the sheet first.
     */
    private fun addComment(): FuzzOutcome {
        click(UiTarget.SAVED_ROUTES)
        val field = hasText("Add a comment")
        if (!present(field)) return FuzzOutcome.NO_SUCH_TARGET
        performIfPresent(field) { it.performTextInput("fuzz comment") }
        return click(UiTarget.ADD_COMMENT)
    }

    /**
     * Deletes a saved route end to end: open the sheet, press that card's own
     * delete target, then confirm. The index is bounds-checked against the
     * sheet and an out-of-range request cancels the dialog rather than
     * deleting an arbitrary route.
     */
    private fun deleteSavedRoute(cardIndex: Int): FuzzOutcome {
        if (click(UiTarget.SAVED_ROUTES) != FuzzOutcome.PERFORMED) {
            return FuzzOutcome.NO_SUCH_TARGET
        }
        // Several cards share the "Delete saved route" description, so the
        // index selects among them rather than the label.
        val targets = rule.onAllNodes(
            hasContentDescription("Delete saved route"),
            useUnmergedTree = true,
        )
        if (cardIndex >= targets.fetchSemanticsNodes().size) return FuzzOutcome.NO_SUCH_TARGET
        targets[cardIndex].performClick()
        return performIfPresent(hasText("Delete")) { it.performClick() }
    }

    private fun swipeCarousel(direction: Direction): FuzzOutcome {
        val matcher = hasContentDescription("Route 1:", substring = true)
        return performIfPresent(matcher) { interaction ->
            interaction.performTouchInput {
                if (direction == Direction.LEFT) swipeLeft() else swipeRight()
            }
        }
    }

    private fun panMap(direction: Direction): FuzzOutcome {
        val matcher = SemanticsMatcher.keyIsDefined(MapReadyKey)
        return performIfPresent(matcher) { interaction ->
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

    /**
     * Sends the real two-pointer shove through the accessible native map node.
     * MapReady is a Boolean readiness seam: a present-but-false key means the
     * map exists but is not yet interactive, so that step is unavailable and
     * must not be credited as a target hit. Gesture failures after readiness
     * are deliberately allowed to propagate into the campaign report.
    */
    private fun tiltMap(increase: Boolean): FuzzOutcome {
        val mapNode = SemanticsMatcher.keyIsDefined(MapReadyKey)
        val mapNodes = rule.onAllNodes(mapNode, useUnmergedTree = true).fetchSemanticsNodes()
        if (mapNodes.isEmpty()) return FuzzOutcome.NO_SUCH_TARGET
        val readyMapNodes = rule.onAllNodes(
            SemanticsMatcher.expectValue(MapReadyKey, true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        if (readyMapNodes.isEmpty()) {
            return FuzzOutcome.UNAVAILABLE
        }
        com.organicmoto.maps.ui.MapGestureRobot.performTwoFingerTilt(rule, increase)
        return FuzzOutcome.PERFORMED
    }

    /**
     * Drags the route-settings road-share dial to an absolute percentage. The
     * dial is a continuous sweep, so the drag is expressed as a target angle
     * rather than a detent count, matching the 10-90% / 5% contract.
     */
    private fun adjustRoadShare(percent: Int): FuzzOutcome {
        val matcher = hasContentDescription("Maximum shared roads", substring = true)
        if (!present(matcher)) return FuzzOutcome.NO_SUCH_TARGET
        val fraction = ((percent.coerceIn(10, 90) - 10) / 80f).toDouble()
        val degrees = 135.0 + fraction * 270.0
        return performIfPresent(matcher) { interaction ->
            interaction.performTouchInput {
                val radius = minOf(visibleSize.width, visibleSize.height) * 0.46f
                val radians = Math.toRadians(degrees)
                val target = center + Offset(
                    (kotlin.math.cos(radians) * radius).toFloat(),
                    (kotlin.math.sin(radians) * radius).toFloat(),
                )
                swipe(center, target)
            }
        }
    }

    private fun pressMedia(command: MediaCommand): FuzzOutcome {
        toggleMediaPanel()
        return performIfPresent(hasContentDescription(command.label).and(hasClickAction())) {
            it.performClick()
        }
    }

    private fun adjustVolume(increase: Boolean): FuzzOutcome {
        toggleMediaPanel()
        val label = if (increase) "Volume up" else "Volume down"
        return performIfPresent(
            hasContentDescription(label, substring = true).and(hasClickAction()),
        ) { it.performClick() }
    }

    private fun selectPlayer(index: Int): FuzzOutcome {
        toggleMediaPanel()
        val chips = rule.onAllNodes(
            hasContentDescription("Select player", substring = true),
            useUnmergedTree = true,
        )
        if (index >= chips.fetchSemanticsNodes().size) return FuzzOutcome.NO_SUCH_TARGET
        chips[index].performClick()
        return FuzzOutcome.PERFORMED
    }

    /**
     * Drives the planner into ride mode through its real controls: the RIDE
     * action when a route is ready, otherwise the known-good corridor is
     * re-typed (clearing whatever random text the campaign left behind) and
     * START is pressed. This gives the campaign reliable access to
     * guidance-only surfaces (media panel, END, dark ride map) without
     * hard-coding coordinates anywhere else.
     */
    private fun startRide(): FuzzOutcome {
        if (!present(hasText("RIDE"))) {
            performIfPresent(hasContentDescription("From")) { interaction ->
                interaction.performTextClearance()
                interaction.performTextInput(RIDE_CORRIDOR_FROM)
            }
            performIfPresent(hasContentDescription("To")) { interaction ->
                interaction.performTextClearance()
                interaction.performTextInput(RIDE_CORRIDOR_TO)
            }
            return click(UiTarget.START)
        }
        return performIfPresent(hasText("RIDE")) { it.performClick() }
    }

    private fun back(): FuzzOutcome {
        if (present(hasContentDescription("Media control center"))) {
            // System Back closes the media panel before anything else.
            Espresso.pressBack()
            return FuzzOutcome.PERFORMED
        }
        if (present(hasText("Saved routes"))) {
            if (present(hasContentDescription("Close saved routes"))) {
                rule.onNode(hasContentDescription("Close saved routes")).performClick()
            } else {
                onBack()
            }
            return FuzzOutcome.PERFORMED
        }
        if (present(hasText("Delete saved route?"))) {
            return performIfPresent(hasText("Cancel")) { it.performClick() }
        }
        if (present(hasText("Sound settings"))) {
            Espresso.pressBack()
            return FuzzOutcome.PERFORMED
        }
        if (present(hasText("Route settings"))) {
            return backOutOfSettingsScreen()
        }
        if (present(hasText("Voice guidance"))) {
            return backOutOfSettingsScreen()
        }
        val hasDialog = present(
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.IsDialog),
        )
        if (!hasDialog) return FuzzOutcome.NO_SUCH_TARGET
        onBack()
        return FuzzOutcome.PERFORMED
    }

    /**
     * Opens the media panel through its accessible opener and closes it again
     * through the visible close target, so the campaign exercises both the
     * glove-sized opener and the panel's own controls.
     */
    private fun toggleMediaPanel(): FuzzOutcome {
        if (present(hasContentDescription("Media control center"))) {
            return performIfPresent(hasContentDescription("Close media controls")) { it.performClick() }
        }
        return click(UiTarget.MEDIA_OPENER)
    }

    private fun toggleSettingsMenu(): FuzzOutcome {
        if (present(hasText("Sound settings"))) {
            Espresso.pressBack()
            return FuzzOutcome.PERFORMED
        }
        return click(UiTarget.SETTINGS_COG)
    }

    private fun openRouteSettings(): FuzzOutcome {
        if (present(hasText("Target maximum shared roads"))) return FuzzOutcome.PERFORMED
        // Close the settings card first: its row and the dialog title share
        // the "Route settings" label.
        if (present(hasText("Sound settings"))) Espresso.pressBack()
        if (!present(hasText("Route settings"))) {
            val opened = click(UiTarget.SETTINGS_COG)
            if (opened != FuzzOutcome.PERFORMED) return opened
        }
        return click(UiTarget.ROUTE_SETTINGS)
    }

    /**
     * Opens the Maps screen through the settings card. If it is already open
     * (a previous step) this is a no-op rather than a failure.
     */
    private fun openMapsSettings(): FuzzOutcome {
        if (present(hasContentDescription("Map server address"))) {
            return FuzzOutcome.PERFORMED
        }
        if (present(hasText("Sound settings"))) Espresso.pressBack()
        if (!present(hasText("Maps"))) {
            val opened = click(UiTarget.SETTINGS_COG)
            if (opened != FuzzOutcome.PERFORMED) return opened
        }
        return click(UiTarget.MAPS_SETTINGS)
    }

    private fun rotateDevice(orientation: DeviceOrientation): FuzzOutcome {
        onRotateDevice(orientation)
        return FuzzOutcome.PERFORMED
    }

    /**
     * Screens opened from the settings card are exited with their shared back
     * button; fall back to the system back when the screen is mid-transition.
     */
    private fun backOutOfSettingsScreen(): FuzzOutcome {
        if (present(hasContentDescription("Back"))) {
            return performIfPresent(hasContentDescription("Back")) { it.performClick() }
        }
        onBack()
        return FuzzOutcome.PERFORMED
    }

    private fun openVoiceSettings(): FuzzOutcome {
        // During a ride the speaker shortcut lives in the Ride settings pill;
        // while planning, Sound settings lives in the settings-cog popup.
        if (present(hasText("Target maximum shared roads"))) Espresso.pressBack()
        if (!present(hasText("Sound settings")) &&
            !present(hasContentDescription("Voice guidance settings", substring = true))
        ) {
            val opened = click(UiTarget.SETTINGS_COG)
            if (opened != FuzzOutcome.PERFORMED) return opened
        }
        return click(UiTarget.VOICE_SETTINGS)
    }

    private fun rotateKnob(detents: Int): FuzzOutcome {
        if (!present(hasContentDescription("Ride complexity level", substring = true))) {
            return FuzzOutcome.NO_SUCH_TARGET
        }
        // Delegated: one continuous drag crossing N clicks, identical physics
        // with the planner-panel suites via KnobRobot.
        com.organicmoto.maps.ui.KnobRobot.performContinuousDrag(rule, detents)
        return FuzzOutcome.PERFORMED
    }

    private fun targetMatcher(target: UiTarget): SemanticsMatcher? = when (target) {
        UiTarget.FROM_FIELD -> hasContentDescription("From")
        UiTarget.CURRENT_LOCATION -> hasContentDescription("Use current location")
        UiTarget.TO_FIELD -> hasContentDescription("To")
        UiTarget.START -> hasText("START")
        UiTarget.KNOB -> hasContentDescription("Ride complexity level", substring = true)
        UiTarget.CAROUSEL, UiTarget.CARD_0 -> hasContentDescription("Route 1:", substring = true)
        UiTarget.CARD_1 -> hasContentDescription("Route 2:", substring = true)
        UiTarget.CARD_2 -> hasContentDescription("Route 3:", substring = true)
        UiTarget.MAP -> SemanticsMatcher.keyIsDefined(MapReadyKey)
        UiTarget.SAVED_ROUTES -> hasContentDescription("Saved routes")
        UiTarget.SETTINGS_COG -> hasContentDescription("Planning settings")
        UiTarget.ROUTE_SETTINGS -> hasText("Route settings").and(hasClickAction())
        UiTarget.APPLY_ROUTE_SETTINGS -> hasText("APPLY")
        UiTarget.ROAD_SHARE_KNOB -> hasContentDescription("Maximum shared roads", substring = true)
        UiTarget.BLOCK_UNPAVED -> hasContentDescription("Block unpaved roads")
        UiTarget.VOICE_SETTINGS -> hasText("Sound settings").or(            hasContentDescription("Voice guidance settings", substring = true),
        )
        UiTarget.DARK_RIDE_MAP -> hasContentDescription("Dark ride map", substring = true)
        UiTarget.MEDIA_OPENER -> hasContentDescription("Media controls")
        UiTarget.MEDIA_PLAY -> hasContentDescription("Play")
        UiTarget.MEDIA_PAUSE -> hasContentDescription("Pause")
        UiTarget.MEDIA_NEXT -> hasContentDescription("Next track")
        UiTarget.MEDIA_PREVIOUS -> hasContentDescription("Previous track")
        UiTarget.MEDIA_VOLUME_UP -> hasContentDescription("Volume up", substring = true)
        UiTarget.MEDIA_VOLUME_DOWN -> hasContentDescription("Volume down", substring = true)
        UiTarget.MEDIA_PLAYER -> hasContentDescription("Select player", substring = true)
        UiTarget.MAPS_SETTINGS -> hasText("Maps").and(hasClickAction())
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
    ): FuzzOutcome = if (present(matcher)) {
        action(rule.onNode(matcher))
        FuzzOutcome.PERFORMED
    } else {
        FuzzOutcome.NO_SUCH_TARGET
    }

    private companion object {
        // The same corridor the Compose UI suites route; only used to recover
        // ride-mode access after random typing, never as a fixed UI coordinate.
        const val RIDE_CORRIDOR_FROM = "-27.4698,153.0251"
        const val RIDE_CORRIDOR_TO = "-27.3353,152.7720"
    }
}

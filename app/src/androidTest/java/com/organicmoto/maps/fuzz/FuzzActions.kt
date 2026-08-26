package com.organicmoto.maps.fuzz

import kotlin.random.Random

sealed interface FuzzAction {
    data class Click(val target: UiTarget) : FuzzAction
    data class LongClick(val target: UiTarget) : FuzzAction
    data class TypeText(val target: UiTarget, val value: String) : FuzzAction
    data class SwipeCarousel(val direction: Direction) : FuzzAction
    data class RotateKnob(val detents: Int) : FuzzAction
    data object TapStart : FuzzAction
    data object Back : FuzzAction
    data object OpenSavedRoutes : FuzzAction
    data object ToggleSettings : FuzzAction
    data class PanMap(val direction: Direction) : FuzzAction
    data object BackgroundForeground : FuzzAction
}

enum class Direction { LEFT, RIGHT, UP, DOWN }

enum class UiTarget {
    FROM_FIELD,
    TO_FIELD,
    START,
    KNOB,
    CAROUSEL,
    MAP,
    SAVED_ROUTES,
    SETTINGS_COG,
    SAVE_BUTTON,
    CARD_0,
    CARD_1,
    CARD_2,
    COMMENT_FIELD,
    ADD_COMMENT,
    DELETE_CONFIRM,
}

/** Seeded action generator. The seed fully determines every choice. */
class FuzzRandom(seed: Long) {
    private val random = Random(seed)

    fun nextInt(bound: Int): Int = random.nextInt(bound)

    fun nextText(): String = TEXT_CORPUS[random.nextInt(TEXT_CORPUS.size)]

    fun nextAction(weights: Map<Class<out FuzzAction>, Int>): FuzzAction {
        val choices = weights.entries.filter { it.value > 0 }
        require(choices.isNotEmpty()) { "fuzz action weights must contain a positive entry" }
        val total = choices.sumOf { it.value }
        var selected = random.nextInt(total)
        val type = choices.first { entry ->
            selected -= entry.value
            selected < 0
        }.key
        return when (type) {
            FuzzAction.Click::class.java -> FuzzAction.Click(nextTarget())
            FuzzAction.LongClick::class.java -> FuzzAction.LongClick(nextTarget())
            FuzzAction.TypeText::class.java -> FuzzAction.TypeText(nextFieldTarget(), nextText())
            FuzzAction.SwipeCarousel::class.java -> FuzzAction.SwipeCarousel(nextDirection())
            FuzzAction.RotateKnob::class.java -> FuzzAction.RotateKnob(random.nextInt(-8, 25))
            FuzzAction.TapStart::class.java -> FuzzAction.TapStart
            FuzzAction.Back::class.java -> FuzzAction.Back
            FuzzAction.OpenSavedRoutes::class.java -> FuzzAction.OpenSavedRoutes
            FuzzAction.ToggleSettings::class.java -> FuzzAction.ToggleSettings
            FuzzAction.PanMap::class.java -> FuzzAction.PanMap(nextDirection())
            FuzzAction.BackgroundForeground::class.java -> FuzzAction.BackgroundForeground
            else -> error("unsupported fuzz action ${type.name}")
        }
    }

    private fun nextTarget(): UiTarget = UiTarget.entries[random.nextInt(UiTarget.entries.size)]

    private fun nextFieldTarget(): UiTarget =
        if (random.nextBoolean()) UiTarget.FROM_FIELD else UiTarget.TO_FIELD

    private fun nextDirection(): Direction = Direction.entries[random.nextInt(Direction.entries.size)]

    private companion object {
        val TEXT_CORPUS = listOf(
            "-27.4698,153.0251",
            "-27.3353 152.7720",
            "Brisbane",
            "Mount Glorious",
            "Cairns",
            "🏍️",
            "東京駅",
            "مقهى",
            "a\u200Bb",
            "line\nbreak",
            "!@#$%^&*()_+-=[]{};:'\",.<>/?",
            "x".repeat(10_000),
            "",
        )
    }
}

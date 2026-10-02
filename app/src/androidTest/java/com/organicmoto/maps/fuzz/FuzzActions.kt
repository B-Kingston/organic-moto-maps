package com.organicmoto.maps.fuzz

import kotlin.random.Random

/**
 * A single fuzz step.
 *
 * Every action carries its own [wireName], [target], and [args], so seed JSON,
 * failure reports, and coverage scoring all read from one source of truth
 * instead of parallel `when` blocks. [FuzzActionCodec] is the only place that
 * turns those three pieces into JSON and back, and `FuzzActionCodecTest`
 * proves the round trip for every action.
 */
sealed interface FuzzAction {
    /** Stable name used in seed JSON and failure reports. */
    val wireName: String

    /** The UI target this action drives, or null for whole-screen actions. */
    val target: UiTarget?

    /** Wire fields beyond the type name, rendered by [FuzzActionCodec]. */
    fun args(): Map<String, Any?> = emptyMap()
}

data class Click(override val target: UiTarget) : FuzzAction {
    override val wireName = "Click"
    override fun args() = mapOf("target" to target.name)
}

data class LongClick(override val target: UiTarget) : FuzzAction {
    override val wireName = "LongClick"
    override fun args() = mapOf("target" to target.name)
}

data class TypeText(override val target: UiTarget, val value: String) : FuzzAction {
    override val wireName = "TypeText"
    override fun args() = mapOf("target" to target.name, "value" to value)
}

data class SwipeCarousel(val direction: Direction) : FuzzAction {
    override val wireName = "SwipeCarousel"
    override val target = UiTarget.CAROUSEL
    override fun args() = mapOf("direction" to direction.name)
}

data class PanMap(val direction: Direction) : FuzzAction {
    override val wireName = "PanMap"
    override val target = UiTarget.MAP
    override fun args() = mapOf("direction" to direction.name)
}

data class TiltMap(val increase: Boolean) : FuzzAction {
    override val wireName = "TiltMap"
    override val target = UiTarget.MAP
    override fun args() = mapOf("increase" to increase)
}

data class RotateKnob(val detents: Int) : FuzzAction {
    override val wireName = "RotateKnob"
    override val target = UiTarget.KNOB
    override fun args() = mapOf("detents" to detents)
}

data class AdjustRoadShare(val percent: Int) : FuzzAction {
    override val wireName = "AdjustRoadShare"
    override val target = UiTarget.ROAD_SHARE_KNOB
    override fun args() = mapOf("percent" to percent)
}

data class PressMedia(val command: MediaCommand) : FuzzAction {
    override val wireName = "PressMedia"
    override val target = command.target
    override fun args() = mapOf("command" to command.name)
}

data class AdjustVolume(val increase: Boolean) : FuzzAction {
    override val wireName = "AdjustVolume"
    override val target = if (increase) UiTarget.MEDIA_VOLUME_UP else UiTarget.MEDIA_VOLUME_DOWN
    override fun args() = mapOf("increase" to increase)
}

data class SelectPlayer(val index: Int) : FuzzAction {
    override val wireName = "SelectPlayer"
    override val target = UiTarget.MEDIA_PLAYER
    override fun args() = mapOf("index" to index)
}

data class DeleteSavedRoute(val cardIndex: Int) : FuzzAction {
    override val wireName = "DeleteSavedRoute"
    override val target = UiTarget.DELETE_CONFIRM
    override fun args() = mapOf("cardIndex" to cardIndex)
}

data class RotateDevice(val orientation: DeviceOrientation) : FuzzAction {
    override val wireName = "RotateDevice"
    override val target: UiTarget? = null
    override fun args() = mapOf("orientation" to orientation.name)
}

data object TapStart : FuzzAction {
    override val wireName = "TapStart"
    override val target = UiTarget.START
}
data object UseCurrentLocation : FuzzAction {
    override val wireName = "UseCurrentLocation"
    override val target = UiTarget.CURRENT_LOCATION
}

data object StartRide : FuzzAction {
    override val wireName = "StartRide"
    override val target = UiTarget.RIDE
}

data object Back : FuzzAction {
    override val wireName = "Back"
    override val target: UiTarget? = null
}

data object OpenSavedRoutes : FuzzAction {
    override val wireName = "OpenSavedRoutes"
    override val target = UiTarget.SAVED_ROUTES
}

data object ToggleSettings : FuzzAction {
    override val wireName = "ToggleSettings"
    override val target = UiTarget.SETTINGS_COG
}

data object OpenRouteSettings : FuzzAction {
    override val wireName = "OpenRouteSettings"
    override val target = UiTarget.ROUTE_SETTINGS
}

data object ApplyRouteSettings : FuzzAction {
    override val wireName = "ApplyRouteSettings"
    override val target = UiTarget.APPLY_ROUTE_SETTINGS
}

data object ToggleBlockUnpaved : FuzzAction {
    override val wireName = "ToggleBlockUnpaved"
    override val target = UiTarget.BLOCK_UNPAVED
}

data object OpenVoiceSettings : FuzzAction {
    override val wireName = "OpenVoiceSettings"
    override val target = UiTarget.VOICE_SETTINGS
}

data object ToggleDarkRideMap : FuzzAction {
    override val wireName = "ToggleDarkRideMap"
    override val target = UiTarget.DARK_RIDE_MAP
}

data object ToggleMediaPanel : FuzzAction {
    override val wireName = "ToggleMediaPanel"
    override val target = UiTarget.MEDIA_OPENER
}

data object OpenMapsSettings : FuzzAction {
    override val wireName = "OpenMapsSettings"
    override val target = UiTarget.MAPS_SETTINGS
}

data object AddComment : FuzzAction {
    override val wireName = "AddComment"
    override val target = UiTarget.ADD_COMMENT
}

data object BackgroundForeground : FuzzAction {
    override val wireName = "BackgroundForeground"
    override val target: UiTarget? = null
}

enum class Direction { LEFT, RIGHT, UP, DOWN }

/** The transport controls the media panel exposes, each a 64 dp glove target. */
enum class MediaCommand(val label: String, val target: UiTarget) {
    PLAY("Play", UiTarget.MEDIA_PLAY),
    PAUSE("Pause", UiTarget.MEDIA_PAUSE),
    NEXT("Next track", UiTarget.MEDIA_NEXT),
    PREVIOUS("Previous track", UiTarget.MEDIA_PREVIOUS),
}

enum class DeviceOrientation { PORTRAIT, LANDSCAPE_LEFT, LANDSCAPE_RIGHT }

/**
 * Campaign action weights, shared by the campaign driver and the codec test so
 * both agree on what the generator can emit.
 *
 * Every action here must stay inside the app. Actions that open system UI are
 * deliberately absent: the notification-access button leaves for the platform's
 * listener settings and finishes the activity, which a semantic campaign
 * cannot revive. That path is covered by MediaAccessRecoveryOnDeviceTest,
 * which grants and restores access directly.
 */
object FuzzWeights {
    fun all(): Map<Class<out FuzzAction>, Int> = mapOf(
        Click::class.java to 20,
        LongClick::class.java to 8,
        TypeText::class.java to 18,
        SwipeCarousel::class.java to 8,
        PanMap::class.java to 6,
        TiltMap::class.java to 4,
        RotateKnob::class.java to 10,
        TapStart::class.java to 12,
        StartRide::class.java to 10,
        Back::class.java to 5,
        UseCurrentLocation::class.java to 3,
        OpenSavedRoutes::class.java to 5,
        ToggleSettings::class.java to 5,
        OpenVoiceSettings::class.java to 5,
        ToggleDarkRideMap::class.java to 5,
        ToggleMediaPanel::class.java to 6,
        OpenMapsSettings::class.java to 4,
        BackgroundForeground::class.java to 3,
        AdjustRoadShare::class.java to 6,
        PressMedia::class.java to 8,
        AdjustVolume::class.java to 6,
        SelectPlayer::class.java to 4,
        DeleteSavedRoute::class.java to 4,
        OpenRouteSettings::class.java to 5,
        ApplyRouteSettings::class.java to 5,
        ToggleBlockUnpaved::class.java to 5,
        AddComment::class.java to 4,
        RotateDevice::class.java to 3,
    )
}

enum class UiTarget {
    FROM_FIELD,
    TO_FIELD,
    CURRENT_LOCATION,
    START,
    KNOB,
    CAROUSEL,
    MAP,
    SAVED_ROUTES,
    SETTINGS_COG,
    ROUTE_SETTINGS,
    APPLY_ROUTE_SETTINGS,
    ROAD_SHARE_KNOB,
    BLOCK_UNPAVED,
    VOICE_SETTINGS,
    DARK_RIDE_MAP,
    MEDIA_OPENER,
    MAPS_SETTINGS,
    MEDIA_PLAY,
    MEDIA_PAUSE,
    MEDIA_NEXT,
    MEDIA_PREVIOUS,
    MEDIA_VOLUME_UP,
    MEDIA_VOLUME_DOWN,
    MEDIA_PLAYER,
    RIDE,
    END_NAVIGATION,
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
        var selected = random.nextInt(choices.sumOf { it.value })
        val type = choices.first {
            selected -= it.value
            selected < 0
        }.key
        return when (type) {
            Click::class.java -> Click(nextTarget())
            LongClick::class.java -> LongClick(nextTarget())
            TypeText::class.java -> TypeText(nextFieldTarget(), nextText())
            SwipeCarousel::class.java -> SwipeCarousel(nextDirection())
            PanMap::class.java -> PanMap(nextDirection())
            TiltMap::class.java -> TiltMap(random.nextBoolean())
            RotateKnob::class.java -> RotateKnob(random.nextInt(-8, 25))
            AdjustRoadShare::class.java ->
                AdjustRoadShare(10 + 5 * random.nextInt(17))
            PressMedia::class.java ->
                PressMedia(MediaCommand.entries[random.nextInt(MediaCommand.entries.size)])
            AdjustVolume::class.java -> AdjustVolume(random.nextBoolean())
            SelectPlayer::class.java -> SelectPlayer(random.nextInt(3))
            DeleteSavedRoute::class.java -> DeleteSavedRoute(random.nextInt(3))
            RotateDevice::class.java -> RotateDevice(
                DeviceOrientation.entries[random.nextInt(DeviceOrientation.entries.size)],
            )
            TapStart::class.java -> TapStart
            UseCurrentLocation::class.java -> UseCurrentLocation
            StartRide::class.java -> StartRide
            Back::class.java -> Back
            OpenSavedRoutes::class.java -> OpenSavedRoutes
            ToggleSettings::class.java -> ToggleSettings
            OpenRouteSettings::class.java -> OpenRouteSettings
            ApplyRouteSettings::class.java -> ApplyRouteSettings
            ToggleBlockUnpaved::class.java -> ToggleBlockUnpaved
            OpenVoiceSettings::class.java -> OpenVoiceSettings
            ToggleDarkRideMap::class.java -> ToggleDarkRideMap
            ToggleMediaPanel::class.java -> ToggleMediaPanel
            OpenMapsSettings::class.java -> OpenMapsSettings
            AddComment::class.java -> AddComment
            BackgroundForeground::class.java -> BackgroundForeground
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

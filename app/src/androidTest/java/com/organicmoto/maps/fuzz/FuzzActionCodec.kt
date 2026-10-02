package com.organicmoto.maps.fuzz

/**
 * The only JSON codec for fuzz actions. Seeding, replay, minimisation, and
 * failure reports all go through here, so an action cannot be half-wired: a
 * new [FuzzAction] declares its name, target, and args once, and this codec
 * encodes and decodes it. `FuzzActionCodecTest` proves the round trip.
 */
object FuzzActionCodec {

    /** One action as a single-line JSON object. */
    fun encode(action: FuzzAction): String = buildString {
        append("{\"type\":").append(quote(action.wireName))
        action.args().forEach { (key, value) ->
            append(',').append(quote(key)).append(':').append(literal(value))
        }
        append('}')
    }

    /** A whole seed document, one action per line. */
    fun encodeAll(actions: List<FuzzAction>): String = buildString {
        append("{\n  \"actions\": [\n")
        actions.forEachIndexed { index, action ->
            append("    ").append(encode(action))
            if (index < actions.lastIndex) append(',')
            append('\n')
        }
        append("  ]\n}\n")
    }

    fun decode(name: String, args: Map<String, String>): FuzzAction = when (name) {
        "Click" -> Click(args.enum("target"))
        "LongClick" -> LongClick(args.enum("target"))
        "TypeText" -> TypeText(args.enum("target"), args.string("value"))
        "SwipeCarousel" -> SwipeCarousel(args.enum("direction"))
        "PanMap" -> PanMap(args.enum("direction"))
        "TiltMap" -> TiltMap(args.boolean("increase"))
        "RotateKnob" -> RotateKnob(args.int("detents"))
        "AdjustRoadShare" -> AdjustRoadShare(args.int("percent"))
        "PressMedia" -> PressMedia(args.enum("command"))
        "AdjustVolume" -> AdjustVolume(args.boolean("increase"))
        "SelectPlayer" -> SelectPlayer(args.int("index"))
        "DeleteSavedRoute" -> DeleteSavedRoute(args.int("cardIndex"))
        "RotateDevice" -> RotateDevice(args.enum("orientation"))
        "TapStart" -> TapStart
        "UseCurrentLocation" -> UseCurrentLocation
        "StartRide" -> StartRide
        "Back" -> Back
        "OpenSavedRoutes" -> OpenSavedRoutes
        "ToggleSettings" -> ToggleSettings
        "OpenRouteSettings" -> OpenRouteSettings
        "ApplyRouteSettings" -> ApplyRouteSettings
        "ToggleBlockUnpaved" -> ToggleBlockUnpaved
        "OpenVoiceSettings" -> OpenVoiceSettings
        "ToggleDarkRideMap" -> ToggleDarkRideMap
        "ToggleMediaPanel" -> ToggleMediaPanel
        "OpenMapsSettings" -> OpenMapsSettings
        "AddComment" -> AddComment
        "BackgroundForeground" -> BackgroundForeground
        else -> error("unknown fuzz action type $name")
    }

    private fun literal(value: Any?): String = when (value) {
        null -> "null"
        is Boolean, is Int, is Long -> value.toString()
        else -> quote(value.toString())
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private inline fun <reified T : Enum<T>> Map<String, String>.enum(key: String): T =
        enumValueOf(field(key))

    private fun Map<String, String>.string(key: String): String = field(key)

    private fun Map<String, String>.int(key: String): Int = field(key).toInt()

    private fun Map<String, String>.boolean(key: String): Boolean = field(key).toBoolean()

    private fun Map<String, String>.field(key: String): String =
        requireNotNull(this[key]) { "fuzz action field '$key' is missing" }
}

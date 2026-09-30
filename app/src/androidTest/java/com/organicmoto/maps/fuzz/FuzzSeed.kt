package com.organicmoto.maps.fuzz

import android.util.JsonReader
import androidx.test.platform.app.InstrumentationRegistry
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/** Reads replayable action sequences from instrumentation-test assets. */
object FuzzSeed {

    fun readActions(assetName: String): List<FuzzAction> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assets.open("fuzz_seeds/$assetName").use { input ->
            JsonReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                var actions: List<FuzzAction> = emptyList()
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "actions" -> actions = readActionsArray(reader)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                return actions
            }
        }
    }

    private fun readActionsArray(reader: JsonReader): List<FuzzAction> {
        val actions = mutableListOf<FuzzAction>()
        reader.beginArray()
        while (reader.hasNext()) {
            var type = ""
            var target: UiTarget? = null
            var direction: Direction? = null
            var value = ""
            var detents = 0
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "type" -> type = reader.nextString()
                    "target" -> target = UiTarget.valueOf(reader.nextString())
                    "direction" -> direction = Direction.valueOf(reader.nextString())
                    "value" -> value = reader.nextString()
                    "detents" -> detents = reader.nextInt()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            actions += when (type) {
                "Click" -> FuzzAction.Click(requireNotNull(target))
                "LongClick" -> FuzzAction.LongClick(requireNotNull(target))
                "TypeText" -> FuzzAction.TypeText(requireNotNull(target), value)
                "SwipeCarousel" -> FuzzAction.SwipeCarousel(requireNotNull(direction))
                "RotateKnob" -> FuzzAction.RotateKnob(detents)
                "TapStart" -> FuzzAction.TapStart
                "Back" -> FuzzAction.Back
                "OpenSavedRoutes" -> FuzzAction.OpenSavedRoutes
                "ToggleSettings" -> FuzzAction.ToggleSettings
                "OpenVoiceSettings" -> FuzzAction.OpenVoiceSettings
                "ToggleDarkRideMap" -> FuzzAction.ToggleDarkRideMap
                "PanMap" -> FuzzAction.PanMap(requireNotNull(direction))
                "BackgroundForeground" -> FuzzAction.BackgroundForeground
                else -> error("unknown fuzz action type $type")
            }
        }
        reader.endArray()
        return actions
    }
}

package com.organicmoto.maps.fuzz

import android.util.JsonReader
import android.util.JsonToken
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
            var name = ""
            val args = mutableMapOf<String, String>()
            reader.beginObject()
            while (reader.hasNext()) {
                when (val key = reader.nextName()) {
                    "type" -> name = reader.nextString()
                    else -> args[key] = nextScalar(reader)
                }
            }
            reader.endObject()
            actions += FuzzActionCodec.decode(name, args)
        }
        reader.endArray()
        return actions
    }

    /**
     * Scalars are kept as text; [FuzzActionCodec] narrows them per action.
     * Android's JsonReader only coerces numbers in `nextString`, so booleans
     * are read explicitly and stringified.
     */
    private fun nextScalar(reader: JsonReader): String = when (reader.peek()) {
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        JsonToken.NUMBER, JsonToken.STRING -> reader.nextString()
        else -> error("fuzz seed field must be a scalar, was ${reader.peek()}")
    }
}

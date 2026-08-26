package com.organicmoto.maps.fuzz

import android.content.Context
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/** Persists every fuzz run with enough data for deterministic replay. */
object FailureRecorder {

    fun record(
        context: Context,
        seed: Long,
        steps: Int,
        actions: List<FuzzAction>,
        states: List<StateFingerprint>,
        error: String?,
        warnings: List<String> = emptyList(),
        extractor: StateExtractor,
    ): File {
        val directory = File(context.filesDir, "fuzz/run-$seed-${System.currentTimeMillis()}")
        require(directory.mkdirs() || directory.isDirectory) {
            "Could not create fuzz report directory $directory"
        }
        val record = buildString {
            append("{\n")
            append("  \"seed\": ").append(seed).append(",\n")
            append("  \"steps\": ").append(steps).append(",\n")
            append("  \"actionSequence\": ").append(actions.map { it.toString() }.joinToJson()).append(",\n")
            append("  \"stateSequence\": ").append(states.map { it.toString() }.joinToJson()).append(",\n")
            append("  \"androidApi\": ").append(android.os.Build.VERSION.SDK_INT).append(",\n")
            append("  \"generation\": ").append(states.lastOrNull()?.generation ?: 0).append(",\n")
            append("  \"selectedIndex\": ").append(states.lastOrNull()?.selectedIndex ?: 0).append(",\n")
            append("  \"softWarnings\": ").append(warnings.joinToJson()).append(",\n")
            append("  \"error\": ").append(error?.let(::quote) ?: "null").append('\n')
            append("}\n")
        }
        File(directory, "failure.json").writeText(record)
        File(directory, "semantics.txt").writeText(extractor.semanticsDump())
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        FileOutputStream(File(directory, "screenshot.png")).use { output ->
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not encode fuzz screenshot"
            }
        }
        return directory
    }

    private fun List<String>.joinToJson(): String = joinToString(", ", prefix = "[", postfix = "]", transform = ::quote)

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
}

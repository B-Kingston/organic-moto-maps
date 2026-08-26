package com.organicmoto.maps.assets

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StyleAndSpritesIntegrityTest {

    @Test
    fun styleHasOnlyLocalSourcesAndCompleteAssetReferences() {
        val root = repoRoot()
        val style = JSONObject(root.resolve("app/src/main/assets/style.json").readText())
        val strings = mutableListOf<String>()
        collectStrings(style, null, strings)
        assertTrue(strings.none { it.matches(Regex("^https?://.*")) })

        val source = style.getJSONObject("sources").getJSONObject("omt")
        assertEquals("{tiles_path}", source.getString("url"))
        val attribution = source.getString("attribution")
        assertTrue(attribution.contains("OpenMapTiles.org"))
        assertTrue(attribution.contains("OpenStreetMap"))

        val glyphUrls = strings.filter { it.startsWith("asset://glyphs/") }.distinct()
        assertEquals(listOf("asset://glyphs/{fontstack}/{range}.pbf"), glyphUrls)
        val fontStacks = collectValuesForKey(style, "text-font")
            .flatMap { value ->
                when {
                    value is JSONArray -> (0 until value.length()).map { value.getString(it) }
                    value is String -> listOf(value)
                    else -> emptyList()
                }
            }
            .toSet()
        assertTrue(fontStacks.isNotEmpty())
        val glyphRoot = root.resolve("app/src/main/assets/glyphs")
        fontStacks.forEach { stack ->
            val directory = glyphRoot.resolve(stack)
            assertTrue(
                "missing glyph stack $stack; run tools/style/fetch-style-assets.sh",
                directory.isDirectory,
            )
            assertTrue(
                "missing 0-255 glyph range for $stack; run tools/style/fetch-style-assets.sh",
                directory.resolve("0-255.pbf").isFile,
            )
            assertTrue(
                "missing 256-511 glyph range for $stack; run tools/style/fetch-style-assets.sh",
                directory.resolve("256-511.pbf").isFile,
            )
        }

        assertTrue(strings.contains("asset://sprites/sprite"))
        val spriteRoot = root.resolve("app/src/main/assets/sprites")
        listOf("sprite.json", "sprite.png", "sprite@2x.json", "sprite@2x.png").forEach { file ->
            assertTrue(
                "missing sprite asset $file; run tools/style/fetch-style-assets.sh",
                spriteRoot.resolve(file).isFile,
            )
        }
    }

    private fun collectStrings(value: Any?, key: String?, output: MutableList<String>) {
        when (value) {
            is JSONObject -> value.keys().forEach { childKey ->
                collectStrings(value.get(childKey), childKey, output)
            }
            is JSONArray -> for (index in 0 until value.length()) {
                collectStrings(value.get(index), key, output)
            }
            is String -> output += value
        }
    }

    private fun collectValuesForKey(value: Any?, wanted: String): List<Any> {
        val found = mutableListOf<Any>()
        fun visit(current: Any?) {
            when (current) {
                is JSONObject -> current.keys().forEach { key ->
                    val child = current.get(key)
                    if (key == wanted && child !is JSONObject && child !is JSONArray) found += child
                    if (key == wanted && child is JSONArray) found += child
                    visit(child)
                }
                is JSONArray -> for (index in 0 until current.length()) visit(current.get(index))
            }
        }
        visit(value)
        return found
    }

    private fun repoRoot(): File {
        var directory: File? = File(".").absoluteFile
        while (directory != null) {
            if (directory.resolve("settings.gradle.kts").isFile) return directory
            directory = directory.parentFile
        }
        error("Could not locate repository root")
    }
}

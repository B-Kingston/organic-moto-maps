package com.organicmoto.maps.assets

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StyleAndSpritesIntegrityTest {

    @Test
    fun hasExpressionsUseLiteralPropertyNames() {
        val style = JSONObject(repoRoot().resolve("app/src/main/assets/style.json").readText())
        val invalid = mutableListOf<String>()

        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { visit(value.get(it)) }
                is JSONArray -> {
                    if (value.length() >= 2 && value.optString(0) == "has" && value.get(1) !is String) {
                        invalid += value.toString()
                    }
                    for (index in 0 until value.length()) visit(value.get(index))
                }
            }
        }
        visit(style)

        assertTrue(
            "MapLibre has expressions require a literal property name, not a nested get: $invalid",
            invalid.isEmpty(),
        )
    }

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

    /**
     * The install step in tools/style/fetch-style-assets.sh already fails when
     * a listed icon is missing from sprite.json, but nothing stopped someone
     * from editing sprite.json afterwards and regressing an icon silently
     * (a green light tier with blank POI icons at runtime). Reproduce the
     * pipeline's invariant here: every icon name in data/style/icon-names.txt
     * must exist in the shipped sprite sheet, and every literal icon-image
     * string inside style.json must be one of those names.
     */
    @Test
    fun referencedIconNamesAllExistInTheSpriteSheet() {
        val root = repoRoot()
        val requiredIcons = root.resolve("data/style/icon-names.txt")
            .readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue("icon-names.txt must list at least one icon", requiredIcons.isNotEmpty())
        assertEquals(
            "icon-names.txt must not contain duplicates",
            requiredIcons.size,
            requiredIcons.toSet().size,
        )

        val spriteKeys = JSONObject(
            root.resolve("app/src/main/assets/sprites/sprite.json").readText(),
        ).let { json ->
            json.keys().asSequence().toSet()
        }
        val missing = requiredIcons.filterNot { it in spriteKeys }
        assertTrue(
            "icons missing from sprite.json: $missing; run tools/style/fetch-style-assets.sh",
            missing.isEmpty(),
        )

        // Literal icon-image references in style.json (not "{icon}" token
        // forms driven by feature properties) must also resolve.
        val style = JSONObject(root.resolve("app/src/main/assets/style.json").readText())
        val literals = mutableListOf<String>()
        collectValuesForKey(style, "icon-image").forEach { value ->
            if (value is String && !value.contains('{')) literals += value
        }
        val unresolvedLiterals = literals.filterNot { it in spriteKeys }
        assertTrue(
            "style.json references icons absent from sprite.json: $unresolvedLiterals",
            unresolvedLiterals.isEmpty(),
        )
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

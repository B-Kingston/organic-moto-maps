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

    @Test
    fun darkRideStyleIsOfflineBlackAndKeepsOnlyDimRoadsAndLimitedRoadNames() {
        val style = JSONObject(
            repoRoot().resolve("app/src/main/assets/ride-dark-style.json").readText(),
        )
        val source = style.getJSONObject("sources").getJSONObject("omt")
        assertEquals("{tiles_path}", source.getString("url"))
        assertTrue(source.getString("attribution").contains("OpenStreetMap"))

        val layers = style.getJSONArray("layers")
        val ids = (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }
        assertEquals(listOf("background", "ride-roads", "ride-road-labels"), ids)
        assertEquals(
            "#000000",
            layers.getJSONObject(0).getJSONObject("paint").getString("background-color"),
        )
        val roads = layers.getJSONObject(1)
        assertEquals("transportation", roads.getString("source-layer"))
        assertEquals("#777777", roads.getJSONObject("paint").getString("line-color"))
        val labels = layers.getJSONObject(2)
        assertEquals("transportation_name", labels.getString("source-layer"))
        assertTrue(labels.getInt("minzoom") >= 10)
        assertEquals("asset://glyphs/{fontstack}/{range}.pbf", style.getString("glyphs"))

        val strings = mutableListOf<String>()
        collectStrings(style, null, strings)
        assertTrue("dark guidance style must not request network resources", strings.none {
            it.matches(Regex("^https?://.*"))
        })
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
    @Test
    fun poiVisibilityMatchesOrganicMapsZoomBands() {
        val style = JSONObject(repoRoot().resolve("app/src/main/assets/style.json").readText())
        val layers = style.getJSONArray("layers")
        val icons = layerById(layers, "poi-icons")
        val labels = layerById(layers, "poi-labels")
        assertEquals(12, icons.getInt("minzoom"))
        assertEquals(12, labels.getInt("minzoom"))

        val iconFilter = icons.getJSONArray("filter")
        assertPoiVisible(iconFilter, "railway", "station", 11.9, false)
        assertPoiVisible(iconFilter, "railway", "station", 12.0, true)
        assertPoiVisible(iconFilter, "bus", "bus_stop", 15.9, false)
        assertPoiVisible(iconFilter, "bus", "bus_stop", 16.0, true)
        assertPoiVisible(iconFilter, "museum", null, 12.9, false)
        assertPoiVisible(iconFilter, "museum", null, 13.0, true)
        assertPoiVisible(iconFilter, "fuel", "fuel", 13.9, false)
        assertPoiVisible(iconFilter, "fuel", "fuel", 14.0, true)
        assertPoiVisible(iconFilter, "cafe", "cafe", 14.9, false)
        assertPoiVisible(iconFilter, "cafe", "cafe", 15.0, true)
        assertPoiVisible(iconFilter, "shop", "beauty", 15.9, false)
        assertPoiVisible(iconFilter, "shop", "beauty", 16.0, true)
        assertPoiVisible(iconFilter, "shop", "unknown", 17.9, false)
        assertPoiVisible(iconFilter, "shop", "unknown", 18.0, true)
        assertPoiVisible(iconFilter, "atm", "atm", 17.9, false)
        assertPoiVisible(iconFilter, "atm", "atm", 18.0, true)
        assertPoiVisible(iconFilter, "cemetery", "cemetery", 14.9, false)
        assertPoiVisible(iconFilter, "cemetery", "cemetery", 15.0, true)
        assertPoiVisible(iconFilter, "park", "bbq", 17.9, false)
        assertPoiVisible(iconFilter, "park", "bbq", 18.0, true)

        val labelFilter = labels.getJSONArray("filter")
        assertPoiVisible(labelFilter, "railway", "station", 11.9, false, named = true)
        assertPoiVisible(labelFilter, "railway", "station", 12.0, true, named = true)
        assertPoiVisible(labelFilter, "fuel", "fuel", 13.9, false, named = true)
        assertPoiVisible(labelFilter, "fuel", "fuel", 14.0, true, named = true)
        assertPoiVisible(labelFilter, "cafe", "cafe", 14.9, false, named = true)
        assertPoiVisible(labelFilter, "cafe", "cafe", 15.0, true, named = true)
        assertPoiVisible(labelFilter, "shop", "beauty", 15.9, false, named = true)
        assertPoiVisible(labelFilter, "shop", "beauty", 16.0, true, named = true)
        assertPoiVisible(labelFilter, "parking", "parking", 17.9, false, named = true)
        assertPoiVisible(labelFilter, "parking", "parking", 18.0, true, named = true)
        assertPoiVisible(labelFilter, "waste_basket", "waste_basket", 18.9, false, named = true)
        assertPoiVisible(labelFilter, "waste_basket", "waste_basket", 19.0, true, named = true)
        assertPoiVisible(labelFilter, "cemetery", "cemetery", 14.9, false, named = true)
        assertPoiVisible(labelFilter, "cemetery", "cemetery", 15.0, true, named = true)

        assertEquals(14, layerById(layers, "park-labels").getInt("minzoom"))
        assertEquals(13, layerById(layers, "peak-labels").getInt("minzoom"))
        assertEquals(15, layerById(layers, "peak-icons-unnamed").getInt("minzoom"))
        assertEquals(7, layerById(layers, "aerodrome-icons").getInt("minzoom"))
        assertEquals(10, layerById(layers, "aerodrome-labels").getInt("minzoom"))
    }

    private fun layerById(layers: JSONArray, wanted: String): JSONObject {
        for (index in 0 until layers.length()) {
            val layer = layers.getJSONObject(index)
            if (layer.getString("id") == wanted) return layer
        }
        error("Missing style layer $wanted")
    }

    private fun assertPoiVisible(
        filter: JSONArray,
        className: String,
        subclass: String?,
        zoom: Double,
        expected: Boolean,
        named: Boolean = false,
    ) {
        val properties = mutableMapOf<String, Any>("class" to className)
        subclass?.let { properties["subclass"] = it }
        if (named) properties["name"] = "Example"
        assertEquals(
            "$className/$subclass at z$zoom",
            expected,
            evaluateFilter(filter, properties, zoom) as Boolean,
        )
    }

    private fun evaluateFilter(
        expression: Any?,
        properties: Map<String, Any>,
        zoom: Double,
    ): Any? {
        if (expression !is JSONArray) return expression
        return when (expression.getString(0)) {
            "get" -> properties[expression.getString(1)]
            "has" -> properties.containsKey(expression.getString(1))
            "zoom" -> zoom
            "literal" -> expression.get(1)
            "all" -> (1 until expression.length()).all {
                evaluateFilter(expression.get(it), properties, zoom) == true
            }
            "any" -> (1 until expression.length()).any {
                evaluateFilter(expression.get(it), properties, zoom) == true
            }
            "!" -> !(evaluateFilter(expression.get(1), properties, zoom) as Boolean)
            "==" -> evaluateFilter(expression.get(1), properties, zoom) ==
                evaluateFilter(expression.get(2), properties, zoom)
            "!=" -> evaluateFilter(expression.get(1), properties, zoom) !=
                evaluateFilter(expression.get(2), properties, zoom)
            ">=" -> (evaluateFilter(expression.get(1), properties, zoom) as Number).toDouble() >=
                (evaluateFilter(expression.get(2), properties, zoom) as Number).toDouble()
            "in" -> {
                val needle = evaluateFilter(expression.get(1), properties, zoom)
                val haystack = evaluateFilter(expression.get(2), properties, zoom)
                require(haystack is JSONArray) { "Expected literal array in $expression" }
                (0 until haystack.length()).any { haystack.get(it) == needle }
            }
            "match" -> {
                val input = evaluateFilter(expression.get(1), properties, zoom)
                var index = 2
                var result: Any? = null
                while (index < expression.length() - 1) {
                    val label = evaluateFilter(expression.get(index), properties, zoom)
                    if (label == input) {
                        result = evaluateFilter(expression.get(index + 1), properties, zoom)
                        break
                    }
                    index += 2
                }
                result ?: evaluateFilter(expression.get(expression.length() - 1), properties, zoom)
            }
            else -> error("Unsupported style expression ${expression.getString(0)}")
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

package com.organicmoto.maps.assets

import com.organicmoto.maps.map.DARK_RIDE_BASEMAP_PROBE_LAYERS
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.pow

/**
 * Key used by [evaluateFilter] for the `["geometry-type"]` expression. Style
 * filters read it from the synthetic properties map the same way `class` and
 * `name` are supplied.
 */
private const val GEOMETRY_TYPE_KEY = "\$geometry-type"

class StyleAndSpritesIntegrityTest {

    private val darkRideStyle: JSONObject by lazy {
        JSONObject(repoRoot().resolve("app/src/main/assets/ride-dark-style.json").readText())
    }

    /** The drivable `transportation` classes the ride map must render. */
    private val drivableRoadClasses = listOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service", "track",
    )

    /** Solid road layers and the exact drivable class whitelist each one owns. */
    private val solidRoadLayers = linkedMapOf(
        "ride-service-roads" to setOf("service", "track"),
        "ride-local-roads" to setOf("tertiary", "minor"),
        "ride-major-roads" to setOf("motorway", "trunk", "primary", "secondary"),
    )

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
     * The black-and-white ride map is the only map the rider sees during
     * guidance. It must stay offline, pure black, and carry every drivable
     * class as a solid grayscale line instead of the old thin, half-opaque
     * wash that made streets look like empty outlines.
     */
    @Test
    fun unselectedRideRoadsAreFivePercentDimmerThanThePreviousPalette() {
        mapOf("ride-service-roads" to 72, "ride-local-roads" to 104, "ride-major-roads" to 146).forEach { (id, previous) ->
            val current = darkRideLineColor(id).substring(1, 3).toInt(16)
            assertTrue("$id must be subtly dimmed", current.toDouble() / previous in 0.94..0.96)
        }
    }

    @Test
    fun darkRideStyleIsOfflineBlackAndGrayscaleWithOneSolidLayerPerDrivableClass() {
        val style = darkRideStyle
        val source = style.getJSONObject("sources").getJSONObject("omt")
        assertEquals("vector", source.getString("type"))
        assertEquals("{tiles_path}", source.getString("url"))
        assertTrue(source.getString("attribution").contains("OpenMapTiles.org"))
        assertTrue(source.getString("attribution").contains("OpenStreetMap"))
        assertEquals("asset://glyphs/{fontstack}/{range}.pbf", style.getString("glyphs"))

        val layers = style.getJSONArray("layers")
        val ids = (0 until layers.length()).map { layers.getJSONObject(it).getString("id") }
        assertEquals(
            listOf(
                "background",
                "ride-water",
                "ride-service-roads",
                "ride-local-roads",
                "ride-major-roads",
                "ride-road-labels",
            ),
            ids,
        )
        assertEquals(
            "#000000",
            darkRideLayer("background").getJSONObject("paint").getString("background-color"),
        )

        // Every drivable class is owned by exactly one solid road layer.
        val claimed = mutableSetOf<String>()
        solidRoadLayers.forEach { (layerId, expected) ->
            val layer = darkRideLayer(layerId)
            assertEquals("$layerId must be a line layer", "line", layer.getString("type"))
            assertEquals("transportation", layer.getString("source-layer"))
            assertEquals("$layerId class whitelist", expected, classesDrawnBy(layer, drivableRoadClasses))
            assertTrue(
                "$layerId duplicates ${expected.intersect(claimed)}",
                expected.intersect(claimed).isEmpty(),
            )
            claimed += expected
        }
        assertEquals(
            "every drivable class needs one solid road layer",
            drivableRoadClasses.toSet(),
            claimed,
        )

        val strings = mutableListOf<String>()
        collectStrings(style, null, strings)
        assertTrue("the B&W ride style must not request network resources", strings.none {
            it.matches(Regex("^https?://.*"))
        })

        val colours = strings.filter { it.matches(Regex("^#[0-9A-Fa-f]{6}$")) }
        assertTrue("the ride palette must keep its greys", colours.size >= 5)
        colours.forEach { hex ->
            val red = hex.substring(1, 3).toInt(16)
            val green = hex.substring(3, 5).toInt(16)
            val blue = hex.substring(5, 7).toInt(16)
            assertEquals("B&W ride mode must not show colour $hex", red, green)
            assertEquals("B&W ride mode must not show colour $hex", green, blue)
        }
        assertTrue(
            "service roads stay dimmer than local roads, which stay dimmer than major roads",
            luminance(darkRideLineColor("ride-service-roads")) <
                luminance(darkRideLineColor("ride-local-roads")) &&
                luminance(darkRideLineColor("ride-local-roads")) <
                luminance(darkRideLineColor("ride-major-roads")),
        )
    }

    /**
     * A line layer fed a `transportation` polygon draws the polygon's border:
     * that is the black-map "wireframe road area" artefact. Every road layer
     * must reject polygons (and points) explicitly at the style level, not by
     * accident.
     */
    @Test
    fun darkRideRoadLayersRejectPolygonAndPointTransportationFeatures() {
        solidRoadLayers.keys.forEach { layerId ->
            val layer = darkRideLayer(layerId)
            val filter = layer.getJSONArray("filter").toString()
            assertTrue(
                "$layerId must whitelist the LineString geometry type: $filter",
                filter.contains("geometry-type") && filter.contains("LineString"),
            )
            assertTrue(
                "$layerId must still draw its classes as real lines",
                classesDrawnBy(layer, drivableRoadClasses).isNotEmpty(),
            )
            listOf("Polygon", "Point", "GeometryCollection").forEach { geometry ->
                val drawn = classesDrawnBy(layer, drivableRoadClasses, geometryType = geometry)
                assertTrue(
                    "$layerId must ignore $geometry transportation features but drew $drawn",
                    drawn.isEmpty(),
                )
            }
        }
        // Pedestrian `path` features are deliberately not drawn: the archive
        // ships them as dense parallel lines around every street, which turned
        // the B&W frame into an architectural drawing instead of solid roads.
        solidRoadLayers.keys.forEach { layerId ->
            assertTrue(
                "$layerId must not draw pedestrian path features",
                classesDrawnBy(darkRideLayer(layerId), listOf("path")).isEmpty(),
            )
        }
    }

    /**
     * The PMTiles archive stops at z14 while guidance runs at z14-z18. A
     * street-close rider therefore needs widths that keep growing through the
     * overzoomed range and stay thick at the slowest (z18) and mid (z16)
     * bands, not a 2.7 dp hairline clamped at the tile's last zoom.
     */
    @Test
    fun darkRideRoadWidthsGrowThroughRidingZoomsAndStayThick() {
        val minimumWidthAtZ16 = mapOf(
            "ride-service-roads" to 5.0,
            "ride-local-roads" to 9.0,
            "ride-major-roads" to 13.0,
        )
        val minimumWidthAtZ18 = mapOf(
            "ride-service-roads" to 8.0,
            "ride-local-roads" to 16.0,
            "ride-major-roads" to 24.0,
        )
        solidRoadLayers.keys.forEach { layerId ->
            val layer = darkRideLayer(layerId)
            fun widthAt(zoom: Double) = layerWidthAt(layer, zoom)
            assertTrue("$layerId must be visible at z14 (${widthAt(14.0)})", widthAt(14.0) >= 2.0)
            for (zoom in 14..17) {
                assertTrue(
                    "$layerId width must grow from z$zoom to z${zoom + 1} " +
                        "(${widthAt(zoom.toDouble())} -> ${widthAt(zoom + 1.0)})",
                    widthAt(zoom + 1.0) > widthAt(zoom.toDouble()),
                )
            }
            assertTrue(
                "$layerId must stay thick at the z16 speed band (${widthAt(16.0)})",
                widthAt(16.0) >= minimumWidthAtZ16.getValue(layerId),
            )
            assertTrue(
                "$layerId must be a bold street at the z18 band (${widthAt(18.0)})",
                widthAt(18.0) >= minimumWidthAtZ18.getValue(layerId),
            )
            assertTrue(
                "$layerId width stops must reach past the z14 tile limit",
                maxStopsZoom(layer, "line-width") >= 18.0,
            )
        }
    }

    /**
     * MapLibre reads line-cap/line-join from `layout`; the same keys parked in
     * `paint` are silently ignored, which is how the old B&W roads lost their
     * round ends. Solid roads also mean no line-gap-width (the hollow
     * "casing" look), no dashing (the dashed-sidewalk look), and no sub-1
     * paint opacity.
     */
    @Test
    fun darkRideLineLayersKeepRoundLayoutCapsAndJoinsWithoutHollowOrFadedRoads() {
        val lineLayerIds = (0 until darkRideStyle.getJSONArray("layers").length())
            .map { darkRideStyle.getJSONArray("layers").getJSONObject(it).getString("id") }
            .filter { darkRideLayer(it).getString("type") == "line" }
        assertEquals(
            listOf("ride-service-roads", "ride-local-roads", "ride-major-roads"),
            lineLayerIds,
        )
        assertEquals("debug probe road layers", lineLayerIds, DARK_RIDE_BASEMAP_PROBE_LAYERS)
        lineLayerIds.forEach { layerId ->
            val layer = darkRideLayer(layerId)
            val layout = layer.getJSONObject("layout")
            val paint = layer.getJSONObject("paint")
            assertEquals("$layerId line-cap", "round", layout.getString("line-cap"))
            assertEquals("$layerId line-join", "round", layout.getString("line-join"))
            assertFalse("$layerId must not park line-cap in paint", paint.has("line-cap"))
            assertFalse("$layerId must not park line-join in paint", paint.has("line-join"))
        }
        listOf(
            "line-gap-width",
            "line-pattern",
            "line-dasharray",
            "line-opacity",
            "text-opacity",
            "fill-opacity",
            "icon-opacity",
        ).forEach { key ->
            assertEquals(
                "the B&W ride map must draw solid, fully opaque roads (found $key)",
                emptyList<Any>(),
                collectValuesForKey(darkRideStyle, key),
            )
        }
    }

    /**
     * At the 58 degree guidance tilt labels lying in the road plane flatten
     * into unreadable slivers. Portrait-framed street names at street sizes
     * are part of the fix, not decoration.
     */
    @Test
    fun darkRideStreetLabelsStayReadableAndViewportAligned() {
        val labels = darkRideLayer("ride-road-labels")
        assertEquals("symbol", labels.getString("type"))
        assertEquals("transportation_name", labels.getString("source-layer"))
        assertTrue("street names must appear from z10", labels.getInt("minzoom") <= 10)

        val layout = labels.getJSONObject("layout")
        assertEquals("line", layout.getString("symbol-placement"))
        assertEquals("map", layout.getString("text-rotation-alignment"))
        assertEquals("viewport", layout.getString("text-pitch-alignment"))
        assertEquals("Noto Sans Regular", layout.getJSONArray("text-font").getString(0))
        assertTrue(
            "street names stay readable at the z16 speed band (${labelTextSizeAt(labels, 16.0)})",
            labelTextSizeAt(labels, 16.0) >= 14.0,
        )
        assertTrue(
            "street names grow at the z18 street band (${labelTextSizeAt(labels, 18.0)})",
            labelTextSizeAt(labels, 18.0) >= 15.0,
        )
        assertTrue(
            "names need a readable dark halo (${labels.getJSONObject("paint").getDouble("text-halo-width")})",
            labels.getJSONObject("paint").getDouble("text-halo-width") >= 1.2,
        )
        assertEquals(
            "only named drivable classes are labelled; paths and rail stay bare",
            setOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor"),
            classesDrawnBy(labels, drivableRoadClasses + listOf("path", "rail")),
        )
        assertTrue(
            "street names must stay bright against the black map",
            luminance(labels.getJSONObject("paint").getString("text-color")) > 0.6,
        )
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

    private fun darkRideLayer(id: String): JSONObject =
        layerById(darkRideStyle.getJSONArray("layers"), id)

    private fun darkRideLineColor(id: String): String =
        darkRideLayer(id).getJSONObject("paint").getString("line-color")

    private fun luminance(hex: String): Double {
        val red = hex.substring(1, 3).toInt(16)
        val green = hex.substring(3, 5).toInt(16)
        val blue = hex.substring(5, 7).toInt(16)
        return (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0
    }

    /** Classes from [candidates] whose features [layer]'s filter accepts. */
    private fun classesDrawnBy(
        layer: JSONObject,
        candidates: List<String>,
        geometryType: String = "LineString",
        zoom: Double = 16.0,
    ): Set<String> = candidates.filterTo(mutableSetOf()) { className ->
        evaluateFilter(
            layer.getJSONArray("filter"),
            mapOf("class" to className, GEOMETRY_TYPE_KEY to geometryType),
            zoom,
        ) == true
    }

    private fun layerWidthAt(layer: JSONObject, zoom: Double): Double =
        numericLayerValueAt(layer, "line-width", zoom)

    private fun labelTextSizeAt(layer: JSONObject, zoom: Double): Double =
        numericLayerValueAt(layer, "text-size", zoom)

    /**
     * Evaluates a numeric zoom function from either `layout` or `paint`.
     * Accepts the constant form, the legacy `{"base": x, "stops": [...]}`
     * form (clamped outside its stops), and the modern `["interpolate", ...]`
     * form, so the shipped syntax can migrate without dropping the invariant.
     */
    private fun numericLayerValueAt(layer: JSONObject, key: String, zoom: Double): Double {
        val value = layer.optJSONObject("layout")?.opt(key)
            ?: layer.optJSONObject("paint")?.opt(key)
            ?: error("Layer ${layer.getString("id")} has no $key")
        return when (value) {
            is Number -> value.toDouble()
            is JSONObject -> {
                val stops = value.getJSONArray("stops")
                interpolate(
                    points = (0 until stops.length()).map {
                        val stop = stops.getJSONArray(it)
                        stop.getDouble(0) to stop.getDouble(1)
                    },
                    base = value.optDouble("base", 1.0),
                    zoom = zoom,
                )
            }
            is JSONArray -> {
                assertEquals("interpolate", value.getString(0))
                assertEquals("zoom", value.getJSONArray(2).getString(0))
                val interpolation = value.get(1) as? JSONArray
                val base = if (interpolation?.getString(0) == "exponential") {
                    interpolation.getDouble(1)
                } else {
                    1.0
                }
                interpolate(
                    points = (3 until value.length() step 2).map {
                        value.getDouble(it) to value.getDouble(it + 1)
                    },
                    base = base,
                    zoom = zoom,
                )
            }
            else -> error("Unsupported $key value in layer ${layer.getString("id")}")
        }
    }

    /** The highest zoom a legacy or interpolate stop list defines. */
    private fun maxStopsZoom(layer: JSONObject, key: String): Double {
        val value = layer.getJSONObject("paint").get(key)
        return when (value) {
            is JSONObject -> value.getJSONArray("stops").let { stops ->
                (0 until stops.length()).maxOf { stops.getJSONArray(it).getDouble(0) }
            }
            is JSONArray -> value.getDouble(value.length() - 2)
            else -> error("Layer ${layer.getString("id")} has a constant $key")
        }
    }

    private fun interpolate(
        points: List<Pair<Double, Double>>,
        base: Double,
        zoom: Double,
    ): Double {
        require(points.size >= 2) { "A zoom function needs at least two stops: $points" }
        if (zoom <= points.first().first) return points.first().second
        if (zoom >= points.last().first) return points.last().second
        for (index in 0 until points.size - 1) {
            val (lowZoom, lowValue) = points[index]
            val (highZoom, highValue) = points[index + 1]
            if (zoom in lowZoom..highZoom) {
                val progress = if (base == 1.0) {
                    (zoom - lowZoom) / (highZoom - lowZoom)
                } else {
                    (base.pow(zoom - lowZoom) - 1.0) / (base.pow(highZoom - lowZoom) - 1.0)
                }
                return lowValue + (highValue - lowValue) * progress
            }
        }
        return points.last().second
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
            "geometry-type" -> properties[GEOMETRY_TYPE_KEY]
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

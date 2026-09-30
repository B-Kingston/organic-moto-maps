package com.organicmoto.maps.assets

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Evaluate the actual shipped expressions, rather than a separate policy copy. */
class BuildingStyleTest {
    private val style = JSONObject(File("src/main/assets/style.json").readText())
    private val layers = style.getJSONArray("layers")
    private fun layer(id: String) = (0 until layers.length()).map(layers::getJSONObject)
        .single { it.getString("id") == id }
    private val extrusion = layer("building-3d")
    private val paint = extrusion.getJSONObject("paint")

    @Test fun localBuildingsKeepFlatOverviewAndOverzoomedHouses() {
        assertEquals("fill-extrusion", extrusion.getString("type"))
        assertEquals("omt", extrusion.getString("source"))
        assertEquals("building", extrusion.getString("source-layer"))
        assertEquals(14, extrusion.getInt("minzoom"))
        assertFalse("archive maxzoom must not cap vector overzoom", extrusion.has("maxzoom"))
        assertEquals("fill", layer("building").getString("type"))
        assertEquals(13, layer("building").getInt("minzoom"))
        assertFalse(layer("building").has("maxzoom"))
        assertEquals("{tiles_path}", style.getJSONObject("sources").getJSONObject("omt").getString("url"))
        assertTrue(paint.getDouble("fill-extrusion-opacity") in 0.2..0.35)
        assertTrue(paint.getBoolean("fill-extrusion-vertical-gradient"))
        assertTrue(paint.getJSONArray("fill-extrusion-height").toString().contains("render_height"))
        assertTrue(paint.getJSONArray("fill-extrusion-base").toString().contains("render_min_height"))
    }

    @Test fun onlyExplicitHide3dTrueSuppressesExtrusion() {
        for ((properties, visible) in listOf(
            emptyMap<String, Any>() to true,
            mapOf("hide_3d" to false) to true,
            mapOf("hide_3d" to true) to false,
        )) assertEquals(visible, evaluate(extrusion.getJSONArray("filter"), properties))
    }

    @Test fun fallbackHouseAndTowerDimensionsAreDefensive() {
        assertDimensions(emptyMap(), 5.0, 0.0)
        assertDimensions(mapOf("render_height" to 5, "render_min_height" to 0), 5.0, 0.0)
        assertDimensions(mapOf("render_height" to 260, "render_min_height" to 12), 260.0, 12.0)
        assertDimensions(mapOf("render_height" to "bad", "render_min_height" to "bad"), 5.0, 0.0)
        assertDimensions(mapOf("render_height" to -20, "render_min_height" to -5), 0.0, 0.0)
        assertDimensions(mapOf("render_height" to 9000, "render_min_height" to 8000), 400.0, 400.0)
        assertDimensions(mapOf("render_height" to 5, "render_min_height" to 10), 5.0, 5.0)
        val random = Random(42)
        repeat(1000) {
            val properties = mapOf("render_height" to random.nextDouble(-1000.0, 1000.0),
                "render_min_height" to random.nextDouble(-1000.0, 1000.0))
            val height = dimension("height", properties)
            val base = dimension("base", properties)
            assertTrue(height in 0.0..400.0)
            assertTrue(base in 0.0..height)
        }
    }

    private fun assertDimensions(properties: Map<String, Any>, height: Double, base: Double) {
        assertEquals(height, dimension("height", properties), 0.0)
        assertEquals(base, dimension("base", properties), 0.0)
    }
    private fun dimension(name: String, properties: Map<String, Any>) =
        (evaluate(paint.getJSONArray("fill-extrusion-$name"), properties) as Number).toDouble()

    private fun evaluate(value: Any?, properties: Map<String, Any>): Any? {
        if (value !is JSONArray) return value
        fun arg(index: Int) = evaluate(value.get(index), properties)
        return when (value.getString(0)) {
            "get" -> properties[value.getString(1)]
            "!=" -> arg(1) != arg(2)
            "number" -> (1 until value.length()).map(::arg).first { it is Number }
            "min" -> (1 until value.length()).minOf { (arg(it) as Number).toDouble() }
            "max" -> (1 until value.length()).maxOf { (arg(it) as Number).toDouble() }
            else -> error("Unsupported expression $value")
        }
    }
}

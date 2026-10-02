package com.organicmoto.maps.assets

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM invariant for the POI symbol batching contract.
 *
 * MapLibre Native 13.5 renders a data-driven `symbol-sort-key` as one drawable
 * per feature (segment per sort-key range). The shipped style used
 * `["get", "rank"]` on `poi-icons`/`poi-labels`, which measured 929 draw calls
 * and ~1.2 s of render-thread encoding per frame in the dense Brisbane CBD
 * versus 52 draws with the key removed. The style therefore allows only:
 *
 * - no `symbol-sort-key` at all, or
 * - a coarse banded key that is guaranteed to collapse each layer to a small
 *   number of adjacent ranges, preserving rank *order* while batching equal
 *   keys.
 *
 * A future edit that reintroduces an unbounded per-feature key must fail here
 * before it can ship as a performance regression.
 */
class SymbolBatchingStyleTest {

    private val style: JSONObject by lazy {
        JSONObject(repoRoot().resolve("app/src/main/assets/style.json").readText())
    }

    private fun layersOfType(type: String): List<JSONObject> {
        val layers = style.getJSONArray("layers")
        return (0 until layers.length())
            .map { layers.getJSONObject(it) }
            .filter { it.optString("type") == type }
    }

    @Test
    fun symbolSortKeysAreAbsentOrBoundedRankBands() {
        val symbolLayers = layersOfType("symbol")
        assertTrue("The style must keep its symbol layers", symbolLayers.size >= 10)
        for (layer in symbolLayers) {
            val layout = layer.optJSONObject("layout") ?: JSONObject()
            if (!layout.has("symbol-sort-key")) continue
            val key = layout.get("symbol-sort-key")
            val bands = bandCountOf(key)
            assertTrue(
                "${layer.optString("id")}: symbol-sort-key must be a bounded rank band, got $key",
                bands in 2..8,
            )
        }
    }

    @Test
    fun poiLayersKeepTheirLabelsAndIcons() {
        val byId = layersOfType("symbol").associateBy { it.optString("id") }
        val icons = byId.getValue("poi-icons")
        val labels = byId.getValue("poi-labels")

        val iconLayout = icons.getJSONObject("layout")
        val iconMatch = iconLayout.get("icon-image") as JSONArray
        assertEquals("icon-image must stay a class match expression", "match", iconMatch.getString(0))
        assertTrue(
            "poi-icons must keep its POI class whitelist",
            iconMatch.length() > 40,
        )
        assertTrue("poi-icons must keep icon collision padding", iconLayout.has("icon-padding"))

        val labelLayout = labels.getJSONObject("layout")
        val textField = labelLayout.get("text-field") as JSONArray
        assertEquals("text-field must stay the name fallback expression", "coalesce", textField.getString(0))
        assertTrue("poi-labels must keep its text font", labelLayout.has("text-font"))
        assertTrue("poi-labels must keep its zoom-scaled text size", labelLayout.has("text-size"))

        for (layer in listOf(icons, labels)) {
            val filter = layer.get("filter") as JSONArray
            assertTrue(
                "${layer.optString("id")}: filter must stay a real POI filter, got ${filter.toString().length} chars",
                filter.toString().length > 500,
            )
            assertTrue(
                "${layer.optString("id")}: POI layers must render by the perf cameras (minzoom <= 18)",
                layer.optDouble("minzoom", 0.0) <= 18.0,
            )
        }
    }

    @Test
    fun rankBandsKeepImportantPoisFirstAndStayCoarse() {
        for (layer in layersOfType("symbol")) {
            val layout = layer.optJSONObject("layout") ?: continue
            if (!layout.has("symbol-sort-key")) continue
            val key = layout.get("symbol-sort-key")
            val bands = bandCountOf(key)
            var previous = -1
            for (rank in 0..30) {
                val band = evaluateBand(key, rank)
                assertTrue(
                    "${layer.optString("id")}: rank $rank sorted before a more important rank ($band < $previous)",
                    band >= previous,
                )
                previous = band
            }
            assertTrue("${layer.optString("id")}: bands must cover the rank range", previous < bands)
            assertEquals(
                "${layer.optString("id")}: a missing rank must sort with the least important band",
                bands - 1,
                evaluateBand(key, null),
            )
        }
    }

    /**
     * Recognizes the only allowed shape and returns its band count:
     * `["min", ["floor", ["/", ["coalesce", ["get", <prop>], <default>], <width>]], <maxBand>]`.
     * Returns 0 for anything else (an unbounded per-feature key included).
     */
    private fun bandCountOf(key: Any?): Int {
        if (key !is JSONArray || key.length() != 3 || key.getString(0) != "min") return 0
        val floor = key.get(1)
        if (floor !is JSONArray || floor.length() != 2 || floor.getString(0) != "floor") return 0
        val division = floor.get(1)
        if (division !is JSONArray || division.length() != 3 || division.getString(0) != "/") return 0
        val numerator = division.get(1)
        if (numerator !is JSONArray || numerator.length() != 3 || numerator.getString(0) != "coalesce") return 0
        val get = numerator.get(1)
        if (get !is JSONArray || get.length() != 2 || get.getString(0) != "get") return 0
        val width = division.optDouble(2, 0.0)
        val maxBand = key.optInt(2, -1)
        if (width <= 0.0 || width > 15.0) return 0
        if (maxBand < 0) return 0
        return maxBand + 1
    }

    /** Evaluates the recognized band expression, with `null` meaning a missing property. */
    private fun evaluateBand(key: Any?, rank: Int?): Int {
        val array = key as JSONArray
        val maxBand = array.optInt(2, Int.MAX_VALUE)
        val division = ((array.get(1) as JSONArray).get(1)) as JSONArray
        val numerator = division.get(1) as JSONArray
        val fallback = numerator.optInt(2, 30)
        val width = division.getDouble(2)
        val value = rank ?: fallback
        return minOf(Math.floor(value.toDouble() / width).toInt(), maxBand)
    }

    private companion object {
        fun repoRoot(): File {
            var directory = File(System.getProperty("user.dir")).absoluteFile
            while (!File(directory, "settings.gradle.kts").isFile) {
                directory = directory.parentFile ?: error("repo root not found from ${System.getProperty("user.dir")}")
            }
            return directory
        }
    }
}

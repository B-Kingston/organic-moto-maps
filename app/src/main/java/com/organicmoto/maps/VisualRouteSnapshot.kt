package com.organicmoto.maps

import com.organicmoto.maps.storage.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Exports the current debug-build route geometry for the ADB visual runner.
 * Release builds never write this cache file. Keeping all alternatives lets
 * the runner advance the same path the rider selected in the carousel.
 */
internal object VisualRouteSnapshot {

    const val FILE_NAME = "visual-route.json"
    const val FIX_ACTION = "com.organicmoto.maps.DEBUG_VISUAL_ROUTE_FIX"

    private const val PENDING_FIX_FILE = "visual-fix.json"
    private const val FIX_ACK_FILE = "visual-fix-ack.json"

    data class FixRequest(
        val requestId: Long,
        val lat: Double,
        val lon: Double,
    )

    fun write(cacheDir: File, routes: List<List<GeoPoint>>, selectedIndex: Int) {
        if (routes.isEmpty() || selectedIndex !in routes.indices) return
        val routeArray = JSONArray()
        routes.forEachIndexed { index, points ->
            val pointArray = JSONArray()
            points.forEach { point ->
                pointArray.put(JSONArray().put(point.lat).put(point.lon))
            }
            routeArray.put(
                JSONObject()
                    .put("index", index)
                    .put("points", pointArray),
            )
        }
        val snapshot = JSONObject()
            .put("version", 1)
            .put("selectedIndex", selectedIndex)
            .put("createdAtMillis", System.currentTimeMillis())
            .put("routes", routeArray)

        val output = File(cacheDir, FILE_NAME)
        val temporary = File(cacheDir, FILE_NAME + ".tmp")
        temporary.writeText(snapshot.toString())
        if (!temporary.renameTo(output)) {
            output.writeText(snapshot.toString())
            temporary.delete()
        }
    }

    fun clear(cacheDir: File) {
        File(cacheDir, FILE_NAME).delete()
        File(cacheDir, FILE_NAME + ".tmp").delete()
    }

    /** Called by the debug-only exported receiver used by tools/test/visual.py. */
    fun queueFix(cacheDir: File, requestId: Long, lat: Double, lon: Double) {
        val json = JSONObject()
            .put("requestId", requestId)
            .put("lat", lat)
            .put("lon", lon)
            .toString()
        writeAtomically(File(cacheDir, PENDING_FIX_FILE), json)
    }

    /** Takes at most one queued host request; the runner waits for its matching ack. */
    fun takeQueuedFix(cacheDir: File): FixRequest? {
        val file = File(cacheDir, PENDING_FIX_FILE)
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            FixRequest(
                requestId = json.getLong("requestId"),
                lat = json.getDouble("lat"),
                lon = json.getDouble("lon"),
            )
        }.getOrNull().also {
            file.delete()
        }
    }

    fun acknowledgeFix(cacheDir: File, requestId: Long) {
        writeAtomically(
            File(cacheDir, FIX_ACK_FILE),
            JSONObject().put("requestId", requestId).toString(),
        )
    }

    private fun writeAtomically(output: File, content: String) {
        val temporary = File(output.parentFile, output.name + ".tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(output)) {
            output.writeText(content)
            temporary.delete()
        }
    }
}

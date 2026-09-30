package com.organicmoto.maps

import com.organicmoto.maps.storage.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.camera.CameraPosition
import java.io.File

/**
 * Exports the current debug-build route geometry for the ADB visual runner.
 * Release builds never write this cache file. Keeping all alternatives lets
 * the runner advance the same path the rider selected in the carousel.
 */
internal object VisualRouteSnapshot {

    const val FILE_NAME = "visual-route.json"
    const val CAMERA_FILE_NAME = "visual-camera.json"
    const val FIX_ACTION = "com.organicmoto.maps.DEBUG_VISUAL_ROUTE_FIX"

    private const val PENDING_FIX_FILE = "visual-fix.json"
    private const val FIX_ACK_FILE = "visual-fix-ack.json"

    data class FixRequest(
        val requestId: Long,
        val lat: Double,
        val lon: Double,
        /**
         * Speed the runner wants the guidance camera to see, or null for a
         * stationary teleport (the default that keeps progress runs honest).
         */
        val speedMps: Double?,
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
    fun queueFix(cacheDir: File, requestId: Long, lat: Double, lon: Double, speedMps: Double?) {
        val json = JSONObject()
            .put("requestId", requestId)
            .put("lat", lat)
            .put("lon", lon)
        speedMps?.let { json.put("speedMps", it) }
        writeAtomically(File(cacheDir, PENDING_FIX_FILE), json.toString())
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
                speedMps = if (json.has("speedMps")) json.getDouble("speedMps") else null,
            )
        }.getOrNull().also {
            file.delete()
        }
    }

    /**
     * Publishes the settled camera for tools/test/visual.py so the guidance
     * framing (zoom band, forward tilt, heading) and the planning-camera
     * restore after END can be checked instead of only eyeballed.
     */
    fun writeCamera(cacheDir: File, camera: CameraPosition, guidance: Boolean) {
        runCatching {
            val target = camera.target
            val json = JSONObject()
                .put("version", 1)
                .put("zoom", camera.zoom.takeIf { it.isFinite() } ?: 0.0)
                .put("tilt", camera.tilt.takeIf { it.isFinite() } ?: 0.0)
                .put("bearing", camera.bearing.takeIf { it.isFinite() } ?: 0.0)
                .put("guidance", guidance)
                .put("createdAtMillis", System.currentTimeMillis())
            if (target != null) {
                json.put("lat", target.latitude).put("lon", target.longitude)
            }
            camera.padding?.let { padding ->
                if (padding.size >= 4 && padding.all { it.isFinite() }) {
                    json.put("paddingTop", padding[1])
                        .put("paddingLeft", padding[0])
                        .put("paddingRight", padding[2])
                        .put("paddingBottom", padding[3])
                }
            }
            writeAtomically(File(cacheDir, CAMERA_FILE_NAME), json.toString())
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

package com.organicmoto.maps.routing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.ResponsePath
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.config.Profile
import com.graphhopper.json.Statement
import com.graphhopper.util.CustomModel
import com.graphhopper.util.Parameters
import com.graphhopper.util.shapes.GHPoint
import java.io.File

private const val TAG = "OrganicMoto.Router"

/**
 * Profile name shared by the router and [MotorcycleWeightingFactory]. Must stay
 * `motorcycle` in all three places: `tools/gh/config.yml`, this constant, and
 * the request in [route] (GraphHopper matches profiles by name).
 */
internal const val MOTORCYCLE_PROFILE = "motorcycle"

class GraphHopperRouter(context: Context) {

    private val appContext = context.applicationContext
    private val graphDir = File(appContext.filesDir, "gh-cache")

    private val hopper: GraphHopper by lazy {
        Log.i(TAG, "Initializing GraphHopper (graph dir: ${graphDir.absolutePath})")
        val started = SystemClock.elapsedRealtime()
        copyGraphFromAssetsIfNeeded()
        if (!graphDir.isDirectory || graphDir.listFiles().isNullOrEmpty()) {
            throw IllegalStateException(
                "Graph data not found. Build the graph with tools/gh/config.yml " +
                    "and place it in app/src/main/assets/graph-cache"
            )
        }
        val config = GraphHopperConfig().apply {
            putObject("graph.location", graphDir.absolutePath)
            putObject("graph.dataaccess.default_type", "MMAP")
            putObject("import.osm.ignored_highways", "footway,steps,corridor,bridleway")
        }
        val loaded = object : GraphHopper() {
            // Install a factory that builds the custom motorcycle weighting from
            // the pre-compiled MotorcycleWeightingHelper instead of Janino (which
            // emits JVM bytecode ART cannot load).
            override fun createWeightingFactory(): WeightingFactory =
                MotorcycleWeightingFactory(baseGraph, encodingManager)
        }.apply {
            init(config)
            setProfiles(motorcycleProfile())
            importOrLoad()
        }
        Log.i(TAG, "GraphHopper ready in ${SystemClock.elapsedRealtime() - started} ms")
        loaded
    }

    /**
     * Rebuilds the import-time motorcycle profile bit-for-bit. The stored graph
     * was imported with `custom_model_files: [motorcycle.json]`, which GraphHopper
     * 11 resolves to the JAR's built-in model
     * (`/com/graphhopper/custom_models/motorcycle.json` classpath resource, see
     * `GraphHopper.resolveCustomModelFiles`). `tools/gh/motorcycle.json` is the
     * checked-in canonical reference copy of that model and must stay identical to
     * it (verified by `tools/gh/generate-weighting.sh`).
     *
     * At load time GraphHopper compares the stored `profiles` property
     * (`motorcycle|198752012` for the current graph) against the string rendered
     * from THIS profile, which hashes the whole hints PMap — including PMap hint
     * INSERTION ORDER. `Profile(name)` pre-seeds an empty `custom_model` hint, so
     * it must be removed first, then `custom_model_files` re-added, then the model
     * attached, mirroring the YAML/import path exactly. The custom_model_files
     * VALUE `[motorcycle.json]` is part of that hash too — do not rename it.
     * Do not reorder or "simplify" these steps or the load will fail with a
     * profile mismatch.
     */
    private fun motorcycleProfile(): Profile {
        val customModel = CustomModel().apply {
            setDistanceInfluence(90.0)
            addToPriority(Statement.If("!car_access", Statement.Op.MULTIPLY, "0"))
            addToPriority(Statement.If("track_type.ordinal() > 1", Statement.Op.MULTIPLY, "0"))
            addToPriority(Statement.If("road_access == PRIVATE", Statement.Op.MULTIPLY, "0"))
            addToPriority(Statement.If("road_access == DESTINATION", Statement.Op.MULTIPLY, "0.1"))
            addToPriority(
                Statement.If("road_class == MOTORWAY || road_class == TRUNK", Statement.Op.MULTIPLY, "0.1")
            )
            addToSpeed(Statement.If("true", Statement.Op.LIMIT, "0.9 * car_average_speed"))
            addToSpeed(Statement.If("true", Statement.Op.LIMIT, "120"))
            addToSpeed(
                Statement.If(
                    "surface==COBBLESTONE || surface==GRASS || surface==GRAVEL || surface==SAND || " +
                        "surface==PAVING_STONES || surface==DIRT || surface==GROUND || " +
                        "surface==UNPAVED || surface==COMPACTED",
                    Statement.Op.LIMIT,
                    "30",
                ),
            )
        }
        return Profile(MOTORCYCLE_PROFILE).apply {
            hints.remove("custom_model")
            putHint("custom_model_files", listOf("motorcycle.json"))
            setCustomModel(customModel)
        }
    }

    private fun copyGraphFromAssetsIfNeeded() {
        if (graphDir.isDirectory && !graphDir.listFiles().isNullOrEmpty()) return
        Log.i(TAG, "graph-cache missing or empty — copying from APK assets")
        val assets = appContext.assets
        val assetRoot = "graph-cache"
        val entries = assets.list(assetRoot)
            ?: throw IllegalStateException("No assets/$assetRoot found in the APK")
        graphDir.mkdirs()
        entries.forEach { copyAssetRecursive("$assetRoot/$it", graphDir) }
        Log.i(TAG, "graph-cache copy complete")
    }

    private fun copyAssetRecursive(assetPath: String, targetDir: File) {
        val name = assetPath.substringAfterLast('/')
        val target = File(targetDir, name)
        val children = assetsOf(assetPath)
        if (children != null) {
            target.mkdirs()
            children.forEach { copyAssetRecursive("$assetPath/$it", target) }
        } else {
            appContext.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun assetsOf(assetPath: String): Array<String>? = runCatching {
        appContext.assets.list(assetPath)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Routes [from] to [to] with the motorcycle profile.
     *
     * [blend] is the route-blend slider position in [0,1] (default 1.0):
     *  - 1.0: pure motorcycle CustomWeighting routed via CH — the stored CH
     *    graph was built with exactly this weighting, so no extra request hints
     *    are sent and behavior is identical to the pre-blend app.
     *  - < 1.0: flexible routing with a BlendedWeighting that linearly blends
     *    the CustomWeighting with fastest routing over the same motorcycle
     *    travel speeds (hard car_access blocking at every position).
     *
     * `ch.disable=true` is MANDATORY for blend < 1.0: the CH solver ignores
     * request hints and always uses the weighting baked into the CH graph at
     * import time, so without it the slider would silently do nothing. The
     * blend itself lives entirely in [MotorcycleWeightingFactory] — no graph,
     * profile, or helper changes are involved.
     */
    fun route(from: GHPoint, to: GHPoint, blend: Double = 1.0): ResponsePath {
        // Intentionally no coordinates in the log: from/to are user-supplied
        // (typed or geocoded) locations. Timings/stats are logged after routing.
        val clampedBlend = blend.coerceIn(0.0, 1.0)
        val request = GHRequest(from, to).setProfile(MOTORCYCLE_PROFILE)
        if (clampedBlend < 1.0) {
            // CH hashes the weighting into the graph at import time and ignores
            // per-request hints; the blend only exists in the weighting factory,
            // so the flexible path is mandatory for t < 1 (see KDoc above).
            request.putHint(Parameters.CH.DISABLE, true)
            request.putHint(MOTO_BLEND, clampedBlend)
            Log.i(TAG, "Requesting motorcycle route (blend $clampedBlend, flexible)")
        } else {
            Log.i(TAG, "Requesting motorcycle route")
        }
        val started = SystemClock.elapsedRealtime()
        val response = hopper.route(request)
        if (response.hasErrors()) {
            val details = response.errors.joinToString("; ") { it.message ?: it.javaClass.simpleName }
            Log.e(TAG, "GraphHopper returned errors after ${SystemClock.elapsedRealtime() - started} ms")
            throw IllegalStateException("Routing failed: $details")
        }
        val path = response.best
        Log.i(
            TAG,
            "Route found in ${SystemClock.elapsedRealtime() - started} ms: " +
                "${path.distance}m, ${path.time}ms, ${path.points.size()} points"
        )
        return path
    }
}

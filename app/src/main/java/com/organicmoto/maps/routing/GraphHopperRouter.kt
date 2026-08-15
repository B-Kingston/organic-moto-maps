package com.organicmoto.maps.routing

import android.content.Context
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.ResponsePath
import com.graphhopper.config.Profile
import com.graphhopper.util.shapes.GHPoint
import java.io.File

class GraphHopperRouter(context: Context) {

    private val appContext = context.applicationContext
    private val graphDir = File(appContext.filesDir, "gh-cache")

    private val hopper: GraphHopper by lazy {
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
        }
        GraphHopper().apply {
            init(config)
            setProfiles(Profile("motorcycle"))
            importOrLoad()
        }
    }

    private fun copyGraphFromAssetsIfNeeded() {
        if (graphDir.isDirectory && !graphDir.listFiles().isNullOrEmpty()) return
        val assets = appContext.assets
        val assetRoot = "graph-cache"
        val entries = assets.list(assetRoot)
            ?: throw IllegalStateException("No assets/$assetRoot found in the APK")
        graphDir.mkdirs()
        entries.forEach { copyAssetRecursive("$assetRoot/$it", graphDir) }
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

    fun route(from: GHPoint, to: GHPoint): ResponsePath {
        val response = hopper.route(GHRequest(from, to).setProfile("motorcycle"))
        if (response.hasErrors()) {
            val details = response.errors.joinToString("; ") { it.message ?: it.javaClass.simpleName }
            throw IllegalStateException("Routing failed: $details")
        }
        return response.best
    }
}

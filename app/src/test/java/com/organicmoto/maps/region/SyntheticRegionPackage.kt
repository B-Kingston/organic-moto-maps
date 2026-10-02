package com.organicmoto.maps.region

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds structurally valid `.motomap` archives for the JVM suites, mirroring
 * the Go writer's manifest schema. Tests can corrupt any part of the package
 * through [mutateManifest], [overrides], and [extraEntries].
 */
class SyntheticRegionPackage(private val target: File) {

    var regionId: String = "monaco"
    var regionName: String = "Monaco"
    var coverage: String? = "Monaco"
    var sourceDate: String = "260928"
    var sourceSha256: String = "a".repeat(64)
    var packageId: String = "monaco-260928-0123456789ab"
    var pipelineFingerprint: String = "b".repeat(64)
    var graphProfile: String = "motorcycle|198752012"
    var geocoderMagic: String = RegionPackageContract.GEOCODER_MAGIC
    var pmtilesVersion: Int = 3
    var attribution: String = "© OpenMapTiles.org © OpenStreetMap contributors"

    /** Overrides for the default component bytes, keyed by package-relative path. */
    val overrides = LinkedHashMap<String, ByteArray>()

    /** Entries appended after the manifest's own files. */
    val extraEntries = ArrayList<Pair<String, ByteArray>>()

    /** Extra copies of entries (duplicate-name attacks). */
    val duplicateEntries = ArrayList<Pair<String, ByteArray>>()

    /** Hook to corrupt the manifest document after it is computed. */
    var mutateManifest: ((JSONObject) -> Unit)? = null

    data class Built(
        val file: File,
        val manifest: RegionPackageManifest,
        val files: Map<String, ByteArray>,
    )

    fun build(): Built {
        val content = defaultContent()
        overrides.forEach { (path, bytes) -> content[path] = bytes }

        val graphFiles = content.keys
            .filter { it.startsWith("${RegionPackageContract.GRAPH_PATH}/") }
            .sorted()
        val components = JSONArray()
        components.put(
            component(
                name = RegionPackageContract.COMPONENT_TILES,
                kind = "file",
                path = RegionPackageContract.TILES_PATH,
                format = RegionPackageContract.TILES_FORMAT,
                files = listOf(RegionPackageContract.TILES_PATH to content.getValue(RegionPackageContract.TILES_PATH)),
            ),
        )
        components.put(
            component(
                name = RegionPackageContract.COMPONENT_GRAPH,
                kind = "directory",
                path = RegionPackageContract.GRAPH_PATH,
                format = RegionPackageContract.GRAPH_FORMAT,
                files = graphFiles.map { it to content.getValue(it) },
                profile = graphProfile,
                stripPrefix = "${RegionPackageContract.GRAPH_PATH}/",
            ),
        )
        components.put(
            component(
                name = RegionPackageContract.COMPONENT_GEOCODER,
                kind = "file",
                path = RegionPackageContract.GEOCODER_PATH,
                format = RegionPackageContract.GEOCODER_FORMAT,
                files = listOf(RegionPackageContract.GEOCODER_PATH to content.getValue(RegionPackageContract.GEOCODER_PATH)),
            ),
        )
        val manifestJson = JSONObject().apply {
            put("schemaVersion", RegionPackageContract.SCHEMA_VERSION)
            put("packageId", packageId)
            put("region", JSONObject().apply {
                put("id", regionId)
                put("name", regionName)
                coverage?.let { put("coverage", it) }
            })
            put("source", JSONObject().apply {
                put("date", sourceDate)
                put("sha256", sourceSha256)
            })
            put("generatedAt", "2026-10-01T00:00:00Z")
            put("generator", JSONObject().apply {
                put("name", "test")
                put("version", "1")
                put("pipelineFingerprint", pipelineFingerprint)
            })
            put("components", components)
            put("compatibility", JSONObject().apply {
                put("packageSchema", RegionPackageContract.SCHEMA_VERSION)
                put("graphProfile", graphProfile)
                put("geocoderMagic", geocoderMagic)
                put("tilesFormat", RegionPackageContract.TILES_FORMAT)
            })
            put("attribution", attribution)
        }
        mutateManifest?.invoke(manifestJson)
        val manifestText = manifestJson.toString(2)
        val parsed = RegionPackageParser.parse(manifestText)

        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestText.encodeToByteArray())
            zip.closeEntry()
            content.entries
                .sortedBy { it.key }
                .forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            extraEntries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            duplicateEntries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return Built(target, parsed, content)
    }

    private fun defaultContent(): LinkedHashMap<String, ByteArray> {
        val pmtiles = ByteArray(127)
        "PMTiles".encodeToByteArray().copyInto(pmtiles)
        pmtiles[7] = pmtilesVersion.toByte()
        val geocoder = (geocoderMagic.encodeToByteArray() + ByteArray(64))
        return linkedMapOf(
            RegionPackageContract.TILES_PATH to pmtiles,
            RegionPackageContract.GEOCODER_PATH to geocoder,
            "${RegionPackageContract.GRAPH_PATH}/properties" to
                "graph.profiles=$graphProfile\n".encodeToByteArray(),
            "${RegionPackageContract.GRAPH_PATH}/nodes" to "nodes-content".encodeToByteArray(),
            "${RegionPackageContract.GRAPH_PATH}/edges" to "edges-content".encodeToByteArray(),
        )
    }

    private fun component(
        name: String,
        kind: String,
        path: String,
        format: String,
        files: List<Pair<String, ByteArray>>,
        profile: String? = null,
        stripPrefix: String = "",
    ): JSONObject {
        val fileArray = JSONArray()
        var total = 0L
        files.forEach { (fullPath, bytes) ->
            val relative = fullPath.removePrefix(stripPrefix)
            fileArray.put(JSONObject().apply {
                put("path", relative)
                put("bytes", bytes.size.toLong())
                put("sha256", sha256(bytes))
            })
            total += bytes.size
        }
        return JSONObject().apply {
            put("name", name)
            put("kind", kind)
            put("path", path)
            put("format", format)
            put("bytes", if (kind == "file") files.first().second.size.toLong() else total)
            put("sha256", if (kind == "file") sha256(files.first().second) else directoryDigest(files, stripPrefix))
            profile?.let { put("profile", it) }
            if (kind == "directory") put("files", fileArray)
        }
    }

    private fun directoryDigest(files: List<Pair<String, ByteArray>>, stripPrefix: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.first }.forEach { (fullPath, bytes) ->
            val relative = fullPath.removePrefix(stripPrefix)
            digest.update("$relative\u0000${bytes.size}\u0000${sha256(bytes)}\n".encodeToByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** Locates a path relative to the repository root (JVM tests run in :app). */
        fun repoFile(relative: String): File {
            var dir: File? = File(".").absoluteFile
            while (dir != null) {
                if (File(dir, "settings.gradle.kts").isFile) return File(dir, relative)
                dir = dir.parentFile
            }
            error("repository root not found")
        }
    }
}

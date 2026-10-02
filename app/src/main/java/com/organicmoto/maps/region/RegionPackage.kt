package com.organicmoto.maps.region

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The versioned `.motomap` package contract. This mirrors the Go writer in
 * `map-server/internal/manifest/manifest.go`; any schema change must bump
 * [SCHEMA_VERSION] and update both sides plus `docs/map-server.md`.
 *
 * The manifest is parsed strictly: unknown fields, missing components, unsafe
 * paths, and unsupported compatibility markers are rejected before any file is
 * installed.
 */
object RegionPackageContract {
    const val SCHEMA_VERSION = 1
    const val PACKAGE_EXTENSION = ".motomap"
    const val GEOCODER_MAGIC = "OMGEO01\n"
    const val GEOCODER_FORMAT = "omgeo01"
    const val TILES_FORMAT = "pmtiles-v3"
    const val GRAPH_FORMAT = "graphhopper-graph"
    const val GRAPH_PROFILE_PREFIX = "motorcycle|"

    const val COMPONENT_TILES = "tiles"
    const val COMPONENT_GRAPH = "graph"
    const val COMPONENT_GEOCODER = "geocoder"

    const val TILES_PATH = "tiles/basemap.pmtiles"
    const val GRAPH_PATH = "graph"
    const val GEOCODER_PATH = "geocoder/geocoder.dat"

    val REGION_ID = Regex("^[a-z0-9][a-z0-9._-]{1,79}$")
    val PACKAGE_ID = Regex("^[a-z0-9][a-z0-9._-]{1,79}$")
    val SHA256 = Regex("^[0-9a-f]{64}$")
    val SOURCE_DATE = Regex("^([0-9]{6}|[0-9]{4}-[0-9]{2}-[0-9]{2})$")
    val GRAPH_PROFILE = Regex("^motorcycle\\|[0-9]{1,12}$")
}

/** One file pinned by a directory component's manifest entry. */
data class PackageFile(
    val path: String,
    val bytes: Long,
    val sha256: String,
)

/** One deliverable inside a package. */
data class PackageComponent(
    val name: String,
    val kind: String,
    val path: String,
    val format: String,
    val bytes: Long,
    val sha256: String,
    val files: List<PackageFile>,
    val profile: String?,
)

/** Parsed and validated `manifest.json`. */
data class RegionPackageManifest(
    val schemaVersion: Int,
    val packageId: String,
    val regionId: String,
    val regionName: String,
    val coverage: String?,
    val sourceDate: String,
    val sourceSha256: String,
    val generatedAt: String,
    val pipelineFingerprint: String,
    val components: List<PackageComponent>,
    val graphProfile: String,
    val geocoderMagic: String,
    val tilesFormat: String,
    val attribution: String,
) {
    fun component(name: String): PackageComponent? = components.firstOrNull { it.name == name }

    /** Every file the package must contain, as package-relative paths. */
    fun expectedFiles(): Map<String, PackageFile> {
        val out = LinkedHashMap<String, PackageFile>()
        components.forEach { component ->
            if (component.kind == "file") {
                out[component.path] = PackageFile(component.path, component.bytes, component.sha256)
            } else {
                component.files.forEach { file -> out["${component.path}/${file.path}"] = file }
            }
        }
        return out
    }
}

/** Raised for any package that cannot be trusted or installed. */
class RegionPackageException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Strict parser for `manifest.json`. */
object RegionPackageParser {

    fun parse(text: String): RegionPackageManifest {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw RegionPackageException("The package manifest is not valid JSON", e)
        }
        rejectUnknown(root, setOf(
            "schemaVersion", "packageId", "region", "source", "generatedAt",
            "generator", "components", "compatibility", "attribution",
        ), "manifest")

        val schemaVersion = root.requiredInt("schemaVersion")
        if (schemaVersion != RegionPackageContract.SCHEMA_VERSION) {
            throw RegionPackageException(
                "Unsupported package schema $schemaVersion (this app reads ${RegionPackageContract.SCHEMA_VERSION})",
            )
        }
        val packageId = root.requiredString("packageId")
        if (!RegionPackageContract.PACKAGE_ID.matches(packageId)) {
            throw RegionPackageException("Invalid package id \"$packageId\"")
        }
        val region = root.requiredObject("region")
        rejectUnknown(region, setOf("id", "name", "coverage"), "region")
        val regionId = region.requiredString("id")
        if (!RegionPackageContract.REGION_ID.matches(regionId)) {
            throw RegionPackageException("Invalid region id \"$regionId\"")
        }
        val regionName = region.requiredString("name")
        if (regionName.isBlank() || regionName.length > 120) {
            throw RegionPackageException("Invalid region name")
        }
        val coverage = region.optString("coverage").takeIf { it.isNotBlank() }

        val source = root.requiredObject("source")
        rejectUnknown(source, setOf("date", "url", "sha256", "bytes"), "source")
        val sourceDate = source.requiredString("date")
        if (!RegionPackageContract.SOURCE_DATE.matches(sourceDate)) {
            throw RegionPackageException("Invalid source date \"$sourceDate\"")
        }
        val sourceSha = source.requiredString("sha256")
        if (!RegionPackageContract.SHA256.matches(sourceSha)) {
            throw RegionPackageException("Invalid source sha256")
        }

        val generatedAt = root.requiredString("generatedAt")
        val generator = root.requiredObject("generator")
        rejectUnknown(generator, setOf("name", "version", "pipelineFingerprint"), "generator")
        val fingerprint = generator.requiredString("pipelineFingerprint")

        val components = parseComponents(root.requiredArray("components"))
        val compatibility = root.requiredObject("compatibility")
        rejectUnknown(
            compatibility,
            setOf("packageSchema", "graphProfile", "geocoderMagic", "tilesFormat", "minAppVersionCode"),
            "compatibility",
        )
        val graphProfile = compatibility.requiredString("graphProfile")
        if (!RegionPackageContract.GRAPH_PROFILE.matches(graphProfile)) {
            throw RegionPackageException("Unsupported routing graph profile \"$graphProfile\"")
        }
        if (compatibility.requiredString("geocoderMagic") != RegionPackageContract.GEOCODER_MAGIC) {
            throw RegionPackageException("Unsupported geocoder index format")
        }
        if (compatibility.requiredString("tilesFormat") != RegionPackageContract.TILES_FORMAT) {
            throw RegionPackageException("Unsupported basemap format")
        }
        if (compatibility.requiredInt("packageSchema") != RegionPackageContract.SCHEMA_VERSION) {
            throw RegionPackageException("Package compatibility schema mismatch")
        }
        val attribution = root.requiredString("attribution")
        if (attribution.isBlank()) {
            throw RegionPackageException("Package is missing its map attribution")
        }
        return RegionPackageManifest(
            schemaVersion = schemaVersion,
            packageId = packageId,
            regionId = regionId,
            regionName = regionName,
            coverage = coverage,
            sourceDate = sourceDate,
            sourceSha256 = sourceSha,
            generatedAt = generatedAt,
            pipelineFingerprint = fingerprint,
            components = components,
            graphProfile = graphProfile,
            geocoderMagic = RegionPackageContract.GEOCODER_MAGIC,
            tilesFormat = RegionPackageContract.TILES_FORMAT,
            attribution = attribution,
        )
    }

    private fun parseComponents(array: JSONArray): List<PackageComponent> {
        if (array.length() != 3) {
            throw RegionPackageException("A package must contain exactly three components")
        }
        val components = ArrayList<PackageComponent>(3)
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: throw RegionPackageException("Invalid component entry")
            rejectUnknown(
                obj,
                setOf("name", "kind", "path", "format", "bytes", "sha256", "files", "profile"),
                "component",
            )
            val name = obj.requiredString("name")
            val kind = obj.requiredString("kind")
            if (kind != "file" && kind != "directory") {
                throw RegionPackageException("Invalid component kind \"$kind\"")
            }
            val path = obj.requiredString("path")
            requireSafeRelative(path, "component path")
            val format = obj.requiredString("format")
            val bytes = obj.requiredLong("bytes")
            if (bytes <= 0) throw RegionPackageException("Component $name has no content")
            val sha = obj.requiredString("sha256")
            if (!RegionPackageContract.SHA256.matches(sha)) {
                throw RegionPackageException("Component $name has an invalid digest")
            }
            val profile = obj.optString("profile").takeIf { it.isNotBlank() }
            val files = ArrayList<PackageFile>()
            val filesArray = obj.optJSONArray("files")
            if (kind == "directory") {
                if (filesArray == null || filesArray.length() == 0) {
                    throw RegionPackageException("Component $name is missing its file list")
                }
                var previous = ""
                var total = 0L
                for (j in 0 until filesArray.length()) {
                    val fileObj = filesArray.optJSONObject(j)
                        ?: throw RegionPackageException("Invalid file entry in $name")
                    rejectUnknown(fileObj, setOf("path", "bytes", "sha256"), "file")
                    val filePath = fileObj.requiredString("path")
                    requireSafeRelative(filePath, "file path")
                    if (j > 0 && filePath <= previous) {
                        throw RegionPackageException("Component $name has an unsorted file list")
                    }
                    previous = filePath
                    val fileBytes = fileObj.requiredLong("bytes")
                    if (fileBytes <= 0) throw RegionPackageException("File $filePath is empty")
                    val fileSha = fileObj.requiredString("sha256")
                    if (!RegionPackageContract.SHA256.matches(fileSha)) {
                        throw RegionPackageException("File $filePath has an invalid digest")
                    }
                    total += fileBytes
                    files += PackageFile(filePath, fileBytes, fileSha)
                }
                if (total != bytes) {
                    throw RegionPackageException("Component $name size does not match its file list")
                }
            } else if (filesArray != null && filesArray.length() > 0) {
                throw RegionPackageException("File component $name must not list files")
            }
            val expected = when (name) {
                RegionPackageContract.COMPONENT_TILES -> Triple("file", RegionPackageContract.TILES_PATH, RegionPackageContract.TILES_FORMAT)
                RegionPackageContract.COMPONENT_GRAPH -> Triple("directory", RegionPackageContract.GRAPH_PATH, RegionPackageContract.GRAPH_FORMAT)
                RegionPackageContract.COMPONENT_GEOCODER -> Triple("file", RegionPackageContract.GEOCODER_PATH, RegionPackageContract.GEOCODER_FORMAT)
                else -> throw RegionPackageException("Unknown component \"$name\"")
            }
            if (kind != expected.first || path != expected.second || format != expected.third) {
                throw RegionPackageException("Component $name does not match the expected format")
            }
            if (name == RegionPackageContract.COMPONENT_GRAPH &&
                (profile == null || !profile.startsWith(RegionPackageContract.GRAPH_PROFILE_PREFIX))
            ) {
                throw RegionPackageException("Routing graph component has no profile marker")
            }
            components += PackageComponent(name, kind, path, format, bytes, sha, files, profile)
        }
        val names = components.map { it.name }.toSet()
        if (names != setOf(
                RegionPackageContract.COMPONENT_TILES,
                RegionPackageContract.COMPONENT_GRAPH,
                RegionPackageContract.COMPONENT_GEOCODER,
            )
        ) {
            throw RegionPackageException("Package is missing a required component")
        }
        return components
    }

    internal fun requireSafeRelative(path: String, what: String) {
        if (path.isEmpty() || path.length > 512 ||
            path.startsWith("/") || path.contains('\\') || path.contains('\u0000') ||
            path.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) {
            throw RegionPackageException("Unsafe $what \"$path\"")
        }
    }

    internal fun rejectUnknown(obj: JSONObject, allowed: Set<String>, what: String) {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in allowed) {
                throw RegionPackageException("Unknown field \"$key\" in $what")
            }
        }
    }
}

private fun JSONObject.requiredString(name: String): String {
    if (!has(name) || isNull(name)) throw RegionPackageException("Missing field \"$name\"")
    val value = optString(name)
    if (value.isEmpty()) throw RegionPackageException("Empty field \"$name\"")
    return value
}

private fun JSONObject.requiredInt(name: String): Int {
    if (!has(name) || isNull(name)) throw RegionPackageException("Missing field \"$name\"")
    return optInt(name, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        ?: throw RegionPackageException("Field \"$name\" is not a number")
}

private fun JSONObject.requiredLong(name: String): Long {
    if (!has(name) || isNull(name)) throw RegionPackageException("Missing field \"$name\"")
    return optLong(name, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
        ?: throw RegionPackageException("Field \"$name\" is not a number")
}

private fun JSONObject.requiredObject(name: String): JSONObject =
    optJSONObject(name) ?: throw RegionPackageException("Missing object \"$name\"")

private fun JSONObject.requiredArray(name: String): JSONArray =
    optJSONArray(name) ?: throw RegionPackageException("Missing array \"$name\"")

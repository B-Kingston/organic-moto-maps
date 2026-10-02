package com.organicmoto.maps.region

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Safe, transactional extraction of `.motomap` packages. Pure JVM (no Android
 * imports), so the hostile-input cases are unit-tested on the desktop:
 *
 *  - zip-slip (absolute paths, `..`, backslashes, NUL) is rejected;
 *  - duplicate, unexpected, directory, and oversized entries are rejected;
 *  - declared sizes are enforced while streaming, so a lying central directory
 *    cannot expand a zip bomb past the limits;
 *  - every file is hashed while it is written and compared with the manifest;
 *  - the archive's component formats (PMTiles v3, geocoder magic, graph
 *    profile) are verified before the staging directory is handed back.
 *
 * Extraction never touches the live region directories; callers rename the
 * completed staging directory into place ([RegionRegistry.install]).
 */
object RegionArchiveImporter {

    /** Hard limits for any archive the app is willing to look at. */
    data class Limits(
        val maxEntries: Int = 4096,
        val maxTotalBytes: Long = 6L shl 30,
        val maxEntryBytes: Long = 5L shl 30,
        val maxCompressionRatio: Long = 250,
        val minFreeBytesAfterInstall: Long = 64L shl 20,
        val maxManifestBytes: Long = 1L shl 20,
    )

    const val MANIFEST_NAME = "manifest.json"
    private const val PMTILES_HEADER_BYTES = 127

    /**
     * Extracts [archive] into [stagingDir] (created fresh) and returns the
     * validated manifest. The caller owns [stagingDir] afterwards.
     */
    fun extract(
        archive: File,
        stagingDir: File,
        limits: Limits = Limits(),
        freeSpace: (File) -> Long = { it.usableSpace },
    ): RegionPackageManifest {
        if (!archive.isFile || archive.length() == 0L) {
            throw RegionPackageException("The selected package file is empty")
        }
        if (stagingDir.exists() && !stagingDir.deleteRecursively()) {
            throw RegionPackageException("Could not clear the staging directory")
        }
        val zip = try {
            ZipFile(archive)
        } catch (e: ZipException) {
            throw RegionPackageException("The selected file is not a valid package archive", e)
        }
        zip.use { zf ->
            val entries = zf.entries().toList()
            if (entries.size > limits.maxEntries) {
                throw RegionPackageException("The package has too many entries (${entries.size})")
            }
            val names = HashSet<String>()
            var totalDeclared = 0L
            entries.forEach { entry ->
                val name = entry.name
                RegionPackageParser.requireSafeRelative(name, "archive entry")
                if (entry.isDirectory) {
                    throw RegionPackageException("The package contains an unexpected directory entry")
                }
                val lower = name.lowercase(Locale.ROOT)
                if (!names.add(lower)) {
                    throw RegionPackageException("The package contains duplicate entries")
                }
                if (entry.size > limits.maxEntryBytes) {
                    throw RegionPackageException("The package contains an oversized entry")
                }
                if (entry.size < 0) {
                    throw RegionPackageException("The package contains an entry with an invalid size")
                }
                val compressed = entry.compressedSize
                if (compressed > 0 && entry.size / compressed > limits.maxCompressionRatio) {
                    throw RegionPackageException("The package looks like a compression bomb")
                }
                totalDeclared += entry.size
                if (totalDeclared > limits.maxTotalBytes) {
                    throw RegionPackageException("The package is larger than this app accepts")
                }
            }
            val manifestEntry = entries.firstOrNull { it.name == MANIFEST_NAME }
                ?: throw RegionPackageException("The package has no manifest.json")
            if (manifestEntry.size > limits.maxManifestBytes) {
                throw RegionPackageException("The package manifest is unreasonably large")
            }
            val manifest = RegionPackageParser.parse(
                zf.getInputStream(manifestEntry).use { it.readBytes().decodeToString() },
            )
            val expected = manifest.expectedFiles()
            val expectedNames = expected.keys + MANIFEST_NAME
            if (names.size != expectedNames.size || !names.containsAll(expectedNames.map { it.lowercase(Locale.ROOT) })) {
                val unexpected = names - expectedNames.map { it.lowercase(Locale.ROOT) }.toSet()
                throw RegionPackageException(
                    if (unexpected.isEmpty()) {
                        "The package is missing files its manifest lists"
                    } else {
                        "The package contains unexpected files (${unexpected.take(3).joinToString()})"
                    },
                )
            }
            val parent = stagingDir.parentFile ?: throw RegionPackageException("No staging parent directory")
            val free = freeSpace(parent)
            if (free in 1 until (manifest.components.sumOf { it.bytes } + limits.minFreeBytesAfterInstall)) {
                throw RegionPackageException("Not enough free space to install this package")
            }
            stagingDir.mkdirs()
            expected.forEach { (path, file) ->
                val entry = entries.first { it.name == path }
                extractVerified(zf, entry, File(stagingDir, path), file.bytes, file.sha256)
            }
            writeManifestCopy(stagingDir, manifest)
            verifyComponentFormats(stagingDir, manifest, limits)
            return manifest
        }
    }

    /**
     * Cheap structural re-check of an installed directory: exact sizes and
     * format magic. Full hashing is available through [verifyFull].
     */
    fun verifyCheap(installDir: File, manifest: RegionPackageManifest): RegionPackageManifest {
        val expected = manifest.expectedFiles()
        expected.forEach { (path, file) ->
            val target = File(installDir, path)
            if (!target.isFile || target.length() != file.bytes) {
                throw RegionPackageException("Installed region data is incomplete ($path)")
            }
        }
        verifyComponentFormats(installDir, manifest, Limits())
        return manifest
    }

    /** Full SHA-256 verification of an installed directory. */
    fun verifyFull(installDir: File, manifest: RegionPackageManifest): RegionPackageManifest {
        manifest.expectedFiles().forEach { (path, file) ->
            val target = File(installDir, path)
            if (!target.isFile || target.length() != file.bytes) {
                throw RegionPackageException("Installed region data is incomplete ($path)")
            }
            val actual = sha256(target)
            if (actual != file.sha256) {
                throw RegionPackageException("Installed region data is corrupted ($path)")
            }
        }
        verifyComponentFormats(installDir, manifest, Limits())
        return manifest
    }

    /** Reads a manifest that is already on disk. */
    fun readManifest(installDir: File): RegionPackageManifest {
        val manifest = File(installDir, MANIFEST_NAME)
        if (!manifest.isFile) throw RegionPackageException("The region is missing its manifest")
        return RegionPackageParser.parse(manifest.readText())
    }

    private fun extractVerified(
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        target: File,
        expectedBytes: Long,
        expectedSha: String,
    ) {
        target.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        zip.getInputStream(entry).use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    if (written > expectedBytes) {
                        throw RegionPackageException("Package entry ${entry.name} is larger than its manifest claims")
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        if (written != expectedBytes) {
            throw RegionPackageException("Package entry ${entry.name} is truncated")
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expectedSha) {
            throw RegionPackageException("Package entry ${entry.name} failed its integrity check")
        }
        fsync(target)
    }

    private fun writeManifestCopy(installDir: File, manifest: RegionPackageManifest) {
        // Preserve the exact manifest document the package shipped so later
        // validations do not depend on the parser's field ordering.
        val source = File(installDir, MANIFEST_NAME)
        if (!source.isFile) {
            // The manifest was parsed from the archive; write a canonical copy.
            source.writeText(renderManifest(manifest))
            fsync(source)
        }
    }

    internal fun renderManifest(manifest: RegionPackageManifest): String {
        val root = org.json.JSONObject()
        root.put("schemaVersion", manifest.schemaVersion)
        root.put("packageId", manifest.packageId)
        root.put("region", org.json.JSONObject().apply {
            put("id", manifest.regionId)
            put("name", manifest.regionName)
            manifest.coverage?.let { put("coverage", it) }
        })
        root.put("source", org.json.JSONObject().apply {
            put("date", manifest.sourceDate)
            put("sha256", manifest.sourceSha256)
        })
        root.put("generatedAt", manifest.generatedAt)
        root.put("generator", org.json.JSONObject().apply {
            put("name", "curveMaps")
            put("version", "1")
            put("pipelineFingerprint", manifest.pipelineFingerprint)
        })
        root.put("components", org.json.JSONArray().apply {
            manifest.components.forEach { component ->
                put(org.json.JSONObject().apply {
                    put("name", component.name)
                    put("kind", component.kind)
                    put("path", component.path)
                    put("format", component.format)
                    put("bytes", component.bytes)
                    put("sha256", component.sha256)
                    component.profile?.let { put("profile", it) }
                    if (component.files.isNotEmpty()) {
                        put("files", org.json.JSONArray().apply {
                            component.files.forEach { file ->
                                put(org.json.JSONObject().apply {
                                    put("path", file.path)
                                    put("bytes", file.bytes)
                                    put("sha256", file.sha256)
                                })
                            }
                        })
                    }
                })
            }
        })
        root.put("compatibility", org.json.JSONObject().apply {
            put("packageSchema", manifest.schemaVersion)
            put("graphProfile", manifest.graphProfile)
            put("geocoderMagic", manifest.geocoderMagic)
            put("tilesFormat", manifest.tilesFormat)
        })
        root.put("attribution", manifest.attribution)
        return root.toString(2)
    }

    private fun verifyComponentFormats(installDir: File, manifest: RegionPackageManifest, limits: Limits) {
        val tiles = File(installDir, RegionPackageContract.TILES_PATH)
        val header = tiles.inputStream().use { it.readAtMost(8) }
        if (header.size < 8 || !header.copyOfRange(0, 7).decodeToString().startsWith("PMTiles")) {
            throw RegionPackageException("The basemap archive is not a PMTiles file")
        }
        if (header[7].toInt() != 3) {
            throw RegionPackageException("The basemap archive is not PMTiles v3")
        }
        if (tiles.length() < PMTILES_HEADER_BYTES) {
            throw RegionPackageException("The basemap archive is truncated")
        }
        val geocoder = File(installDir, RegionPackageContract.GEOCODER_PATH)
        val magic = geocoder.inputStream().use { it.readAtMost(manifest.geocoderMagic.length) }
        if (magic.decodeToString() != manifest.geocoderMagic) {
            throw RegionPackageException("The place-search index has an unsupported format")
        }
        val properties = File(installDir, RegionPackageContract.GRAPH_PATH + "/properties")
        if (!properties.isFile) {
            throw RegionPackageException("The routing graph is missing its properties file")
        }
        val marker = "profiles=${manifest.graphProfile}"
        val content = properties.inputStream().use { it.readAtMost(1 shl 20) }.decodeToString()
        if (!content.contains(marker)) {
            throw RegionPackageException("The routing graph does not match the app's motorcycle profile")
        }
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var offset = 0
        while (offset < limit) {
            val count = read(buffer, offset, limit - offset)
            if (count < 0) break
            offset += count
        }
        return buffer.copyOfRange(0, offset)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fsync(file: File) {
        try {
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE).use { it.force(true) }
        } catch (_: IOException) {
            // Best effort: the rename that publishes the directory is the
            // transaction boundary; fsync limits the crash window.
        }
    }
}

package com.organicmoto.maps.region

import org.json.JSONObject
import java.io.File

/**
 * The persisted state of one package download. The network bytes are staged in
 * app storage first (so the resumable `*.part` survives restarts), and the
 * record also remembers the user-visible SAF destination chosen for the save.
 *
 * [fileName] is the staged file inside the downloads directory; legacy records
 * from the pre-SAF flow did not store it, so it is reconstructed from the
 * region id and fingerprint exactly as the old flow named it.
 */
data class RegionDownloadRecord(
    val jobId: String,
    val regionId: String,
    val regionName: String,
    val fingerprint: String,
    val downloadUrl: String,
    val etag: String?,
    val bytes: Long,
    val total: Long?,
    val fileName: String,
    val destinationUri: String?,
    /** Server-advertised package digest; null for legacy records or servers without one. */
    val sha256: String? = null,
)

/**
 * Reads and writes `regions/download.json`. Deliberately pure JVM code (no
 * Android imports) so recovery behaviour — including not destroying files the
 * user may still be able to resume or save — is unit-tested on the desktop.
 */
class RegionDownloadStore(
    private val stateFile: File,
    private val downloadsDir: File,
) {

    fun save(record: RegionDownloadRecord) {
        runCatching {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(
                JSONObject().apply {
                    put("jobId", record.jobId)
                    put("regionId", record.regionId)
                    put("regionName", record.regionName)
                    put("fingerprint", record.fingerprint)
                    put("downloadUrl", record.downloadUrl)
                    record.etag?.let { put("etag", it) }
                    put("bytes", record.bytes)
                    record.total?.let { put("total", it) }
                    put("fileName", record.fileName)
                    record.destinationUri?.let { put("destinationUri", it) }
                    record.sha256?.let { put("sha256", it) }
                }.toString(),
            )
        }
    }

    fun read(): RegionDownloadRecord? = runCatching {
        if (!stateFile.isFile) return@runCatching null
        val doc = JSONObject(stateFile.readText())
        val url = doc.optString("downloadUrl")
        if (url.isBlank()) return@runCatching null
        val regionId = doc.optString("regionId")
        val fingerprint = doc.optString("fingerprint")
        val fileName = doc.optString("fileName").takeIf { it.isNotBlank() }
            ?: defaultFileName(regionId, fingerprint)
        RegionDownloadRecord(
            jobId = doc.optString("jobId"),
            regionId = regionId,
            regionName = doc.optString("regionName"),
            fingerprint = fingerprint,
            downloadUrl = url,
            etag = doc.optString("etag").takeIf { it.isNotBlank() },
            bytes = doc.optLong("bytes", 0),
            total = doc.optLong("total", 0).takeIf { it > 0 },
            fileName = fileName,
            destinationUri = doc.optString("destinationUri").takeIf { it.isNotBlank() },
            sha256 = doc.optString("sha256").takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    /** Clears only the metadata pointer; staged files are never deleted here. */
    fun clear() {
        stateFile.delete()
    }

    fun destinationFile(record: RegionDownloadRecord): File = File(downloadsDir, record.fileName)

    fun partFile(record: RegionDownloadRecord): File = File(downloadsDir, record.fileName + ".part")

    private fun defaultFileName(regionId: String, fingerprint: String): String =
        "${regionId.ifBlank { "region" }}-${fingerprint.ifBlank { "package" }}.motomap"
}

/**
 * Remembers an active-map deletion that is waiting for the routing graph to be
 * released. Persisting it means a process death between the switch to bundled
 * data and the directory delete cannot leave an orphaned install forever.
 */
data class PendingRemoval(
    val installId: String,
    val regionId: String,
    val regionName: String,
    val bytes: Long,
)

class RegionPendingRemovalStore(private val stateFile: File) {

    fun save(pending: PendingRemoval) {
        runCatching {
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(
                JSONObject().apply {
                    put("installId", pending.installId)
                    put("regionId", pending.regionId)
                    put("regionName", pending.regionName)
                    put("bytes", pending.bytes)
                }.toString(),
            )
        }
    }

    fun read(): PendingRemoval? = runCatching {
        if (!stateFile.isFile) return@runCatching null
        val doc = JSONObject(stateFile.readText())
        val installId = doc.optString("installId").takeIf { it.isNotBlank() }
            ?: return@runCatching null
        PendingRemoval(
            installId = installId,
            regionId = doc.optString("regionId"),
            regionName = doc.optString("regionName"),
            bytes = doc.optLong("bytes", 0),
        )
    }.getOrNull()

    fun clear() {
        stateFile.delete()
    }
}

/** File-name rules for the staged copy and the SAF save suggestion. */
object RegionDownloadNaming {

    /** Name used inside the private staging directory. */
    fun stagedFileName(regionId: String, fingerprint: String): String =
        "${sanitize(regionId)}-${fingerprint.ifBlank { "package" }}.motomap"

    /**
     * Sensible default shown in the system "Save to" dialog, e.g.
     * `queensland-260815.motomap`.
     */
    fun suggestedDisplayName(regionId: String, sourceDate: String?, fingerprint: String): String {
        val id = sanitize(regionId)
        val date = sourceDate.orEmpty().filter { it.isDigit() }.take(8)
        val suffix = date.ifBlank { fingerprint.take(8) }
        return if (suffix.isBlank()) "$id.motomap" else "$id-$suffix.motomap"
    }

    private fun sanitize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-').take(64).ifBlank { "region" }
}

package com.organicmoto.maps.region

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.organicmoto.maps.BuildConfig
import com.organicmoto.maps.region.net.DownloadCancelledException
import com.organicmoto.maps.region.net.RegionHttpClient
import com.organicmoto.maps.region.net.RegionHttpException
import com.organicmoto.maps.region.net.RegionServerClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.coroutineContext

private const val TAG = "OrganicMoto.Regions"

/** Which step of the download flow is running. */
enum class DownloadPhase { DOWNLOADING, VERIFYING, SAVING }

/** Live progress of a package download (or the copy to the user's file). */
data class DownloadProgress(
    val jobId: String,
    val regionName: String,
    val bytes: Long,
    val total: Long?,
    val phase: DownloadPhase = DownloadPhase.DOWNLOADING,
    /** Non-null while the transfer waits to retry after a transient failure. */
    val retry: DownloadRetry? = null,
) {
    val fraction: Float?
        get() = total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

/** A download that can be resumed after a restart. */
data class InterruptedDownload(
    val jobId: String,
    val regionId: String,
    val regionName: String,
    val fingerprint: String,
    val downloadUrl: String,
    val etag: String?,
    val bytes: Long,
    val total: Long?,
)

/** A package the user saved to a device-visible location (session grant). */
data class SavedPackage(
    val regionId: String,
    val regionName: String,
    val fileName: String,
    val bytes: Long,
    val uri: String,
)

/** A completed package kept in app storage, waiting to be saved or imported. */
data class RecoveredPackage(
    val regionId: String,
    val regionName: String,
    val fileName: String,
    val bytes: Long,
)

/** Everything the Maps settings screen renders. */
data class MapsState(
    val serverAddress: String = "",
    val allowInsecureLocal: Boolean = false,
    val serverChecked: Boolean = false,
    val serverVersion: String = "",
    val generationEnabled: Boolean = false,
    val generationMessage: String = "",
    val catalog: List<ServerRegion> = emptyList(),
    val activeJob: ServerJob? = null,
    val download: DownloadProgress? = null,
    val interrupted: InterruptedDownload? = null,
    val savedPackage: SavedPackage? = null,
    val recoveredPackage: RecoveredPackage? = null,
    val pendingRemoval: PendingRemoval? = null,
    val installed: List<InstalledRegion> = emptyList(),
    val activeInstallId: String? = null,
    val activeRegionName: String = RegionDatasets.BUNDLED_DISPLAY_NAME,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

/** A resolved downloadable artifact: either the cached catalog entry or a completed job. */
private data class DownloadTarget(
    val jobId: String,
    val regionId: String,
    val regionName: String,
    val fingerprint: String,
    val size: Long,
    val sha256: String,
    val downloadUrl: String,
    val sourceDate: String?,
)

/**
 * App-scoped controller for server-configured regional packages:
 * catalog → build request → polling → resumable download into private staging →
 * copy to a user-visible file the user chose (SAF) → explicit import → verified
 * install → activation. The active [RegionDataset] flow is what the route screen
 * keys its router, geocoder, and map style on.
 *
 * The download is never installed automatically: a completed transfer is a
 * `.motomap` file in the user's own storage, and installation only happens when
 * the user imports a file. Cancelling or failing leaves the staged partial in
 * place and keeps every control retryable; the UI never loses the Download
 * action to a terminal build job.
 *
 * Deleting the active map is a two-phase operation: the app switches to the
 * bundled dataset first (generation bump, so every reader is rebuilt), waits
 * for [onDatasetReadersReleased] to confirm the old routing graph is drained,
 * and only then removes the immutable install directory.
 */
class RegionManager(context: Context) {

    private val appContext = context.applicationContext
    private val registry = RegionRegistry(File(appContext.filesDir, "regions"))
    private val settings = RegionSettings(appContext)
    private val downloadsDir = File(appContext.filesDir, "regions/downloads")
    private val downloadStore = RegionDownloadStore(
        File(appContext.filesDir, "regions/download.json"),
        downloadsDir,
    )
    private val pendingRemovalStore = RegionPendingRemovalStore(
        File(appContext.filesDir, "regions/pending-removal.json"),
    )
    private val client = RegionServerClient(
        RegionHttpClient(
            allowInsecure = { url ->
                BuildConfig.DEBUG && settings.allowInsecureLocal && LocalNetworkPolicy.isLocalAddress(url)
            },
        ),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(MapsState())
    val state: StateFlow<MapsState> = _state.asStateFlow()

    private val _dataset = MutableStateFlow(registry.activeDataset(appContext))
    val dataset: StateFlow<RegionDataset> = _dataset.asStateFlow()

    private var pollJob: Job? = null
    private var downloadJob: Job? = null
    private var pendingRelease: CompletableDeferred<Unit>? = null

    @Volatile
    private var rideActive = false

    init {
        refresh()
        loadPersistedDownload()
        resumePendingRemoval()
    }

    // --- server configuration -------------------------------------------------

    fun saveServerAddress(address: String) {
        settings.serverAddress = address
        _state.update { it.copy(serverAddress = settings.serverAddress) }
    }

    fun setAllowInsecureLocal(allow: Boolean) {
        settings.allowInsecureLocal = allow
        _state.update { it.copy(allowInsecureLocal = allow) }
    }

    /** The UI disables deletion of the active map mid-ride; the manager refuses too. */
    fun setRideActive(active: Boolean) {
        rideActive = active
    }

    /** Checks the configured server and loads its region catalog. */
    fun checkServer() {
        val address = settings.serverAddress
        if (address.isBlank()) {
            _state.update { it.copy(error = "Enter the map server address first") }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                var lastError: Exception? = null
                for (base in client.candidateBaseUrls(address, ::plainHttpAllowed)) {
                    try {
                        connect(base)
                        return@launch
                    } catch (e: RegionHttpException) {
                        lastError = e
                        // An HTTP answer means this address reached a server:
                        // a different scheme would not be a better guess.
                        if (e.statusCode != 0) break
                    }
                }
                throw lastError ?: RegionHttpException("Could not reach the server")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(busy = false, serverChecked = false, catalog = emptyList(), error = friendly(e))
                }
            }
        }
    }

    private fun plainHttpAllowed(url: String): Boolean =
        BuildConfig.DEBUG && settings.allowInsecureLocal && LocalNetworkPolicy.isLocalAddress(url)

    /**
     * Loads health + catalog from [base]; on success the exact working
     * address is saved, so later requests never re-guess the scheme. A build
     * the server is still running for a catalog region (for example one
     * requested before the app restarted) is picked up and followed again.
     */
    private suspend fun connect(base: String) {
        val health = try {
            client.health(base)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val catalog = client.catalog(base)
        val generation = health?.optJSONObject("generation")
        settings.serverAddress = base
        _state.update {
            it.copy(
                serverAddress = base,
                serverChecked = true,
                serverVersion = health?.optString("version").orEmpty(),
                generationEnabled = generation?.optBoolean("enabled", false) ?: false,
                generationMessage = generation?.optString("message").orEmpty(),
                catalog = catalog,
                busy = false,
            )
        }
        val running = catalog.firstNotNullOfOrNull { it.activeJobId }
        if (running != null && _state.value.activeJob?.isActive != true) {
            runCatching { client.job(base, running) }.getOrNull()?.let { job ->
                _state.update { it.copy(activeJob = job) }
                if (job.isActive) pollJob(base, job.id)
            }
        }
    }

    /** Requests generation for a catalog region and follows the job. */
    fun requestBuild(regionId: String) {
        val address = settings.serverAddress
        if (address.isBlank()) {
            _state.update { it.copy(error = "Enter the map server address first") }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                val base = client.normalizeBaseUrl(address)
                val token = client.csrfToken(base)
                val job = client.requestBuild(base, token, regionId)
                // A completed job no longer starts a download by itself: the
                // user chooses where the package is saved and imports it
                // explicitly afterwards.
                _state.update { it.copy(activeJob = job, busy = false) }
                if (job.isActive) {
                    pollJob(base, job.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    private suspend fun pollJob(base: String, jobId: String) {
        pollJob?.cancel()
        val job = scope.launch {
            var failures = 0
            while (isActive) {
                // Back off while the server is unreachable instead of hammering it.
                delay((POLL_INTERVAL_MS shl failures.coerceAtMost(3)).coerceAtMost(MAX_POLL_INTERVAL_MS))
                try {
                    val current = client.job(base, jobId)
                    failures = 0
                    _state.update {
                        it.copy(
                            activeJob = current,
                            error = it.error.takeUnless { message -> message == POLL_LOST_MESSAGE },
                        )
                    }
                    if (current.isCompleted || current.isFailed || current.isCanceled) {
                        if (current.isCompleted) refreshCatalogQuietly(base)
                        return@launch
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RegionHttpException) {
                    if (e.statusCode == 404) {
                        // The server forgot the job (state wiped): stop
                        // following it and say so instead of polling forever.
                        _state.update {
                            it.copy(
                                activeJob = null,
                                error = "The server no longer has this build. Check the server and request it again.",
                            )
                        }
                        return@launch
                    }
                    // A transient poll failure must not lose the job; keep the
                    // last known state and try again.
                    failures++
                    Log.w(TAG, "job poll failed ($failures)", e)
                    if (failures == POLL_FAILURES_BEFORE_WARNING) {
                        _state.update { it.copy(error = POLL_LOST_MESSAGE) }
                    }
                } catch (e: Exception) {
                    failures++
                    Log.w(TAG, "job poll failed ($failures)", e)
                }
            }
        }
        pollJob = job
    }

    /** Re-reads the catalog so a finished build's artifact size/hash show up. */
    private suspend fun refreshCatalogQuietly(base: String) {
        runCatching { client.catalog(base) }.getOrNull()?.let { catalog ->
            _state.update { it.copy(catalog = catalog) }
        }
    }

    fun cancelActiveJob() {
        val job = _state.value.activeJob ?: return
        val address = settings.serverAddress
        pollJob?.cancel()
        scope.launch {
            try {
                val base = client.normalizeBaseUrl(address)
                val token = client.csrfToken(base)
                val canceled = client.cancelBuild(base, token, job.id)
                _state.update { it.copy(activeJob = canceled, notice = "Build canceled") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = friendly(e)) }
            }
        }
    }

    // --- download and user-visible save ---------------------------------------

    /** Default file name offered by the system "Save to" dialog. */
    fun suggestedFileName(regionId: String): String {
        val target = downloadTarget(regionId)
        return RegionDownloadNaming.suggestedDisplayName(
            regionId = regionId,
            sourceDate = target?.sourceDate,
            fingerprint = target?.fingerprint.orEmpty(),
        )
    }

    fun suggestedFileNameForResume(): String {
        val record = downloadStore.read()
        return RegionDownloadNaming.suggestedDisplayName(
            regionId = record?.regionId.orEmpty().ifBlank { "region" },
            sourceDate = null,
            fingerprint = record?.fingerprint.orEmpty(),
        )
    }

    fun suggestedFileNameForRecovered(): String {
        val recovered = _state.value.recoveredPackage ?: return "package.motomap"
        val record = downloadStore.read()?.takeIf { it.regionId == recovered.regionId }
        return RegionDownloadNaming.suggestedDisplayName(
            regionId = recovered.regionId,
            sourceDate = null,
            fingerprint = record?.fingerprint.orEmpty(),
        )
    }

    /**
     * Downloads [regionId] into private staging and copies it to the
     * user-visible [destination] once the transfer is verified complete. The
     * file only appears at the destination in full; a failed or cancelled
     * transfer leaves the resumable partial in app storage.
     */
    fun startDownload(regionId: String, destination: Uri) {
        val target = downloadTarget(regionId)
        if (target == null) {
            _state.update {
                it.copy(error = "This region has no downloadable package — request a build first")
            }
            return
        }
        val record = RegionDownloadRecord(
            jobId = target.jobId,
            regionId = target.regionId,
            regionName = target.regionName,
            fingerprint = target.fingerprint,
            downloadUrl = target.downloadUrl,
            etag = null,
            bytes = 0,
            total = target.size.takeIf { it > 0 },
            fileName = RegionDownloadNaming.stagedFileName(target.regionId, target.fingerprint),
            destinationUri = destination.toString(),
            sha256 = PackageChecksum.expected(target.sha256),
        )
        downloadStore.save(record)
        launchDownload(record, destination)
    }

    /** Resumes the persisted staged download and saves it to a chosen location. */
    fun resumeDownload(destination: Uri) {
        val record = downloadStore.read()
        if (record == null) {
            _state.update { it.copy(error = "There is no interrupted download to resume") }
            return
        }
        launchDownload(record.copy(destinationUri = destination.toString()), destination)
    }

    private fun launchDownload(record: RegionDownloadRecord, destination: Uri) {
        val address = settings.serverAddress
        if (address.isBlank()) {
            _state.update { it.copy(error = "Enter the map server address first") }
            return
        }
        downloadJob?.cancel()
        downloadJob = scope.launch {
            _state.update {
                it.copy(
                    download = DownloadProgress(
                        jobId = record.jobId,
                        regionName = record.regionName.ifBlank { record.regionId },
                        bytes = record.bytes,
                        total = record.total,
                        phase = DownloadPhase.DOWNLOADING,
                    ),
                    interrupted = null,
                    error = null,
                )
            }
            try {
                val base = client.normalizeBaseUrl(address)
                var current = record
                val staged = downloadStore.destinationFile(current)
                val complete = current.total?.let { staged.isFile && staged.length() >= it } ?: false
                if (!complete) {
                    current = transferWithRetries(base, current, staged)
                }
                verifyChecksum(current, staged)
                _state.update {
                    it.copy(
                        download = DownloadProgress(
                            jobId = current.jobId,
                            regionName = current.regionName.ifBlank { current.regionId },
                            bytes = staged.length(),
                            total = current.total ?: staged.length(),
                            phase = DownloadPhase.SAVING,
                        ),
                    )
                }
                val saved = saveToUserFile(current, staged, destination)
                _state.update {
                    it.copy(
                        download = null,
                        interrupted = null,
                        recoveredPackage = null,
                        savedPackage = saved,
                        notice = "Saved \"${saved.fileName}\" to your device — " +
                            "tap Import saved file to install it.",
                    )
                }
                Log.i(TAG, "saved ${current.regionId} to a user file: ${saved.bytes} bytes")
            } catch (e: DownloadCancelledException) {
                discardUntouchedDestination(destination)
                loadPersistedDownload()
                _state.update { it.copy(download = null) }
            } catch (e: CancellationException) {
                // A user cancel lands here when it hits a retry wait or the
                // checksum: keep the same truthful "interrupted" state.
                discardUntouchedDestination(destination)
                loadPersistedDownload()
                _state.update { it.copy(download = null) }
                throw e
            } catch (e: Exception) {
                discardUntouchedDestination(destination)
                loadPersistedDownload()
                _state.update { it.copy(download = null, error = friendly(e)) }
            }
        }
    }

    /**
     * Runs the transfer, retrying transient failures in place: each retry
     * resumes the staged partial (never restarts it), the ETag/size are
     * persisted the moment the server answers, and the row shows a countdown
     * while it waits. Permanent failures and exhausted retries propagate with
     * the partial kept for a manual Resume.
     */
    private suspend fun transferWithRetries(
        base: String,
        record: RegionDownloadRecord,
        staged: File,
    ): RegionDownloadRecord {
        val context = coroutineContext
        var current = record
        var failures = 0
        while (true) {
            val partFile = downloadStore.partFile(current)
            val before = if (partFile.isFile) partFile.length() else 0L
            try {
                val outcome = client.downloadArtifact(
                    baseUrl = base,
                    job = current.toServerJob(),
                    destination = staged,
                    etag = current.etag,
                    onProgress = { bytes, total -> publishDownload(current, bytes, total ?: current.total) },
                    isCancelled = { !context.isActive },
                    onResponse = { etag, total ->
                        current = current.copy(etag = etag ?: current.etag, total = current.total ?: total)
                        downloadStore.save(current)
                    },
                )
                current = current.copy(
                    bytes = outcome.bytes,
                    etag = outcome.etag ?: current.etag,
                    total = current.total ?: outcome.bytes,
                )
                downloadStore.save(current)
                return current
            } catch (e: RegionHttpException) {
                val after = if (partFile.isFile) partFile.length() else 0L
                if (after > before) failures = 0
                failures++
                if (!DownloadRetryPolicy.shouldRetry(e, failures)) throw e
                Log.w(TAG, "download attempt failed ($failures), retrying: ${e.message}")
                var remaining = DownloadRetryPolicy.delayMs(failures, e.retryAfterMs)
                while (remaining > 0) {
                    publishDownload(
                        current,
                        after,
                        current.total,
                        retry = DownloadRetry(
                            attempt = failures + 1,
                            maxAttempts = DownloadRetryPolicy.MAX_ATTEMPTS,
                            secondsUntilRetry = ((remaining + 999) / 1000).toInt(),
                            reason = e.message ?: "Connection lost",
                        ),
                    )
                    val step = remaining.coerceAtMost(1_000L)
                    delay(step)
                    remaining -= step
                }
            }
        }
    }

    private fun publishDownload(
        record: RegionDownloadRecord,
        bytes: Long,
        total: Long?,
        phase: DownloadPhase = DownloadPhase.DOWNLOADING,
        retry: DownloadRetry? = null,
    ) {
        _state.update {
            it.copy(
                download = DownloadProgress(
                    jobId = record.jobId,
                    regionName = record.regionName.ifBlank { record.regionId },
                    bytes = bytes,
                    total = total,
                    phase = phase,
                    retry = retry,
                ),
            )
        }
    }

    /**
     * Compares the staged package with the digest the server advertised. A
     * mismatch discards the staged bytes (resuming them could only reproduce
     * the corruption) and asks for a fresh download; it is never saved to
     * the user's file.
     */
    private suspend fun verifyChecksum(record: RegionDownloadRecord, staged: File) {
        val expected = PackageChecksum.expected(record.sha256) ?: return
        publishDownload(record, staged.length(), staged.length(), phase = DownloadPhase.VERIFYING)
        val context = coroutineContext
        val actual = withContext(Dispatchers.IO) {
            PackageChecksum.sha256Hex(staged) { !context.isActive }
        }
        if (actual != expected) {
            staged.delete()
            downloadStore.partFile(record).delete()
            downloadStore.save(record.copy(bytes = 0, etag = null))
            Log.w(TAG, "checksum mismatch for ${record.regionId}: expected $expected, got $actual")
            throw RegionPackageException(
                "The downloaded package was damaged in transit (checksum mismatch) and was discarded. " +
                    "Download it again.",
            )
        }
    }

    /**
     * Copies a staged package to the user's destination and verifies the byte
     * count before reporting success. A short write throws and keeps the staged
     * file for a retry.
     */
    private suspend fun saveToUserFile(
        record: RegionDownloadRecord,
        staged: File,
        destination: Uri,
    ): SavedPackage = withContext(Dispatchers.IO) {
        val length = staged.length()
        val output = appContext.contentResolver.openOutputStream(destination, "w")
            ?: throw RegionPackageException("The selected location could not be opened for writing")
        val written = RegionPackageTransfer.copyTo(staged, output)
        if (written != length) {
            throw RegionPackageException(
                "The package was not fully saved (wrote $written of $length bytes)",
            )
        }
        val displayName = displayNameOf(destination) ?: record.fileName
        staged.delete()
        downloadStore.partFile(record).delete()
        downloadStore.clear()
        SavedPackage(
            regionId = record.regionId,
            regionName = record.regionName.ifBlank { record.regionId },
            fileName = displayName,
            bytes = written,
            uri = destination.toString(),
        )
    }

    private fun displayNameOf(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /**
     * Removes a file the system picker created for this download when the
     * transfer failed before any byte was written. A non-empty destination is
     * left alone (it may be a partial save worth inspecting), and the staged
     * copy in app storage is always kept for a retry.
     */
    private fun discardUntouchedDestination(uri: Uri) {
        runCatching {
            val size = appContext.contentResolver.openFileDescriptor(uri, "r")
                ?.use { it.statSize } ?: return
            if (size > 0L) return
            DocumentsContract.deleteDocument(appContext.contentResolver, uri)
        }
    }

    /** Saves the recovered app-storage package to a chosen user-visible file. */
    fun saveRecoveredPackage(destination: Uri) {
        val recovered = _state.value.recoveredPackage ?: return
        val record = downloadStore.read()?.takeIf { it.regionId == recovered.regionId }
        val staged = record?.let { downloadStore.destinationFile(it) }
        if (record == null || staged == null || !staged.isFile) {
            loadPersistedDownload()
            _state.update { it.copy(error = "The downloaded package is no longer in app storage") }
            return
        }
        scope.launch {
            _state.update {
                it.copy(
                    download = DownloadProgress(
                        jobId = record.jobId,
                        regionName = recovered.regionName,
                        bytes = staged.length(),
                        total = staged.length(),
                        phase = DownloadPhase.SAVING,
                    ),
                    error = null,
                )
            }
            try {
                val saved = saveToUserFile(record, staged, destination)
                _state.update {
                    it.copy(
                        download = null,
                        recoveredPackage = null,
                        savedPackage = saved,
                        notice = "Saved \"${saved.fileName}\" to your device — " +
                            "tap Import saved file to install it.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                discardUntouchedDestination(destination)
                loadPersistedDownload()
                _state.update { it.copy(download = null, error = friendly(e)) }
            }
        }
    }

    /** Installs the package recovered from the old private-only flow. */
    fun importRecoveredPackage() {
        val recovered = _state.value.recoveredPackage ?: return
        val record = downloadStore.read()?.takeIf { it.regionId == recovered.regionId }
        val staged = record?.let { downloadStore.destinationFile(it) }
        if (record == null || staged == null || !staged.isFile) {
            loadPersistedDownload()
            _state.update { it.copy(error = "The downloaded package is no longer in app storage") }
            return
        }
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                val install = withContext(Dispatchers.IO) { registry.install(staged) }
                staged.delete()
                downloadStore.partFile(record).delete()
                downloadStore.clear()
                _state.update {
                    it.copy(
                        busy = false,
                        recoveredPackage = null,
                        notice = "${install.regionName} installed — activate it to use it offline.",
                    )
                }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        // Disconnecting can touch the socket; never do that on the main thread.
        scope.launch { client.cancelActive() }
        loadPersistedDownload()
        _state.update { it.copy(download = null) }
    }

    /** Installs a package the user picked with the system file picker. */
    fun importPackage(uri: Uri) {
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                val file = withContext(Dispatchers.IO) {
                    val target = File(appContext.cacheDir, "import-${System.currentTimeMillis()}.motomap")
                    val input = appContext.contentResolver.openInputStream(uri)
                        ?: throw RegionPackageException("The selected file could not be opened")
                    input.use { source -> target.outputStream().use { output -> source.copyTo(output) } }
                    target
                }
                try {
                    val install = withContext(Dispatchers.IO) { registry.install(file) }
                    Log.i(TAG, "imported region ${install.regionId} (${install.installId})")
                    _state.update {
                        it.copy(
                            busy = false,
                            // The saved file is now installed: drop its card so
                            // it cannot invite a second, duplicate import.
                            savedPackage = it.savedPackage?.takeUnless { saved -> saved.uri == uri.toString() },
                            notice = "${install.regionName} installed — activate it to use it offline. " +
                                "The selected file was left where it was.",
                        )
                    }
                } finally {
                    file.delete()
                }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    /** Re-imports the file saved in this session without another picker trip. */
    fun importSavedPackage() {
        val uri = _state.value.savedPackage?.uri?.takeIf { it.isNotBlank() } ?: return
        importPackage(Uri.parse(uri))
    }

    // --- activation and removal ----------------------------------------------

    /**
     * Activates an installed region. Callers must not do this mid-ride; the UI
     * disables the control while guidance is active.
     */
    fun activate(installId: String) {
        // Re-activating a map that was pending deletion cancels the deletion.
        if (_state.value.pendingRemoval?.installId == installId) {
            clearPendingRemoval()
        }
        scope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                withContext(Dispatchers.IO) { registry.activate(installId) }
                refresh()
                val name = _state.value.installed.firstOrNull { it.installId == installId }?.regionName
                _state.update {
                    it.copy(busy = false, notice = "${name ?: "The selected region"} is now the active map.")
                }
                Log.i(TAG, "activated region $installId")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    fun activateBundled() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { registry.activateBundled() }
                refresh()
                _state.update { it.copy(notice = "Using the bundled Queensland data") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = friendly(e)) }
            }
        }
    }

    /**
     * Deletes an installed map. An inactive map is removed immediately; the
     * active map is switched to the bundled dataset first and deleted only
     * after the old routing graph reports it is drained, so no file is removed
     * from under a live reader. The downloaded `.motomap` the user may have
     * saved in Files is never touched.
     */
    fun requestRemove(installId: String) {
        scope.launch {
            val install = _state.value.installed.firstOrNull { it.installId == installId }
                ?: runCatching { registry.find(installId) }.getOrNull()
            if (install == null) {
                _state.update { it.copy(error = "That map is not installed") }
                return@launch
            }
            val active = registry.active().installId == installId
            if (active && rideActive) {
                _state.update { it.copy(error = "Stop the ride before deleting the active map") }
                return@launch
            }
            if (!active) {
                _state.update { it.copy(busy = true, error = null, notice = null) }
                try {
                    withContext(Dispatchers.IO) { registry.remove(installId) }
                    refresh()
                    _state.update { it.copy(busy = false, notice = removalNotice(install)) }
                    Log.i(TAG, "removed inactive region $installId")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(busy = false, error = friendly(e)) }
                }
                return@launch
            }
            // Active map: switch to bundled first so every reader is rebuilt,
            // then wait for the drain signal before unlinking the directory.
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                withContext(Dispatchers.IO) { registry.activateBundled() }
                val pending = PendingRemoval(
                    installId = installId,
                    regionId = install.regionId,
                    regionName = install.regionName,
                    bytes = install.bytes,
                )
                pendingRemovalStore.save(pending)
                pendingRelease = CompletableDeferred()
                _state.update {
                    it.copy(
                        busy = false,
                        pendingRemoval = pending,
                        // The active pointer is already bundled; reflect it
                        // immediately so the row's ACTIVE tag cannot linger.
                        activeInstallId = null,
                        notice = "Switching to the bundled Queensland data…",
                    )
                }
                refresh()
                withTimeoutOrNull(PENDING_RELEASE_TIMEOUT_MS) { pendingRelease?.await() }
                deletePendingRemoval()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    /**
     * Called by the route screen once the routing graph of the previous dataset
     * has actually closed (null means "some dataset was released"). Completing
     * the signal lets a pending active-map deletion proceed.
     */
    fun onDatasetReadersReleased(installId: String?) {
        val pending = _state.value.pendingRemoval ?: return
        if (installId == null || installId == pending.installId) {
            pendingRelease?.complete(Unit)
        }
    }

    private suspend fun deletePendingRemoval() {
        val pending = _state.value.pendingRemoval ?: return
        // The user may have re-activated the map while we waited.
        if (registry.active().installId == pending.installId) {
            clearPendingRemoval()
            return
        }
        try {
            withContext(Dispatchers.IO) { registry.remove(pending.installId) }
            pendingRemovalStore.clear()
            pendingRelease = null
            _state.update { it.copy(pendingRemoval = null) }
            refresh()
            _state.update { it.copy(notice = removalNotice(pending)) }
            Log.i(TAG, "removed active region ${pending.installId} after draining readers")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            clearPendingRemoval()
            _state.update { it.copy(error = friendly(e)) }
        }
    }

    private fun clearPendingRemoval() {
        pendingRemovalStore.clear()
        pendingRelease = null
        _state.update { it.copy(pendingRemoval = null) }
    }

    private fun removalNotice(install: InstalledRegion): String = buildString {
        append("\"${install.regionName}\" was deleted from this device.")
        append(" Downloaded .motomap files in Files are not touched.")
        _state.value.savedPackage
            ?.takeIf { it.regionId == install.regionId }
            ?.let { append(" Your saved file \"${it.fileName}\" is still in Files.") }
    }

    private fun removalNotice(pending: PendingRemoval): String = buildString {
        append("\"${pending.regionName}\" was deleted from this device.")
        append(" Downloaded .motomap files in Files are not touched.")
        _state.value.savedPackage
            ?.takeIf { it.regionId == pending.regionId }
            ?.let { append(" Your saved file \"${it.fileName}\" is still in Files.") }
    }

    /**
     * Finishes a deletion that was interrupted by process death. A fresh
     * process has no reader for an install that is not active, so it is safe to
     * remove immediately.
     */
    private fun resumePendingRemoval() {
        val pending = pendingRemovalStore.read() ?: return
        if (registry.active().installId == pending.installId) {
            pendingRemovalStore.clear()
            return
        }
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { registry.remove(pending.installId) } }
                .onFailure { Log.w(TAG, "pending removal failed", it) }
            pendingRemovalStore.clear()
            refresh()
            _state.update { it.copy(notice = removalNotice(pending)) }
        }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    /** Re-reads the registry and active dataset (also called after installs). */
    fun refresh() {
        scope.launch {
            val installs = withContext(Dispatchers.IO) { registry.list() }
            val record = withContext(Dispatchers.IO) { registry.active() }
            val dataset = withContext(Dispatchers.IO) { registry.activeDataset(appContext) }
            _state.update {
                it.copy(
                    serverAddress = settings.serverAddress,
                    allowInsecureLocal = settings.allowInsecureLocal,
                    installed = installs,
                    activeInstallId = record.installId,
                    activeRegionName = dataset.displayName,
                )
            }
            _dataset.value = dataset
        }
    }

    fun close() {
        pollJob?.cancel()
        downloadJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    // --- persistence ----------------------------------------------------------

    /**
     * Re-reads the staged download. A complete file from an older flow is
     * offered for save/import instead of being discarded; partial bytes stay
     * resumable; nothing on disk is deleted here.
     */
    private fun loadPersistedDownload() {
        val record = downloadStore.read() ?: return
        val part = downloadStore.partFile(record)
        val complete = downloadStore.destinationFile(record)
        val completeBytes = complete.takeIf { it.isFile }?.length() ?: 0L
        val isComplete = completeBytes > 0 && (record.total == null || completeBytes >= record.total)
        val interrupted = if (!isComplete && part.isFile && part.length() > 0) {
            InterruptedDownload(
                jobId = record.jobId,
                regionId = record.regionId,
                regionName = record.regionName.ifBlank { record.regionId },
                fingerprint = record.fingerprint,
                downloadUrl = record.downloadUrl,
                etag = record.etag,
                bytes = part.length(),
                total = record.total,
            )
        } else {
            null
        }
        val recovered = if (isComplete) {
            RecoveredPackage(
                regionId = record.regionId,
                regionName = record.regionName.ifBlank { record.regionId },
                fileName = record.fileName,
                bytes = completeBytes,
            )
        } else {
            null
        }
        _state.update { it.copy(interrupted = interrupted, recoveredPackage = recovered) }
    }

    private fun downloadTarget(regionId: String): DownloadTarget? {
        val state = _state.value
        val job = state.activeJob
            ?.takeIf { it.regionId == regionId && it.isCompleted && it.downloadUrl.isNotBlank() }
        if (job != null) {
            return DownloadTarget(
                jobId = job.id,
                regionId = job.regionId,
                regionName = job.regionName.ifBlank { regionId },
                fingerprint = fingerprintFrom(job.downloadUrl),
                size = job.artifactSize,
                sha256 = job.artifactSha256,
                downloadUrl = job.downloadUrl,
                sourceDate = state.catalog.firstOrNull { it.id == regionId }?.sourceDate,
            )
        }
        val region = state.catalog.firstOrNull { it.id == regionId } ?: return null
        val artifact = region.artifact
            ?.takeIf { it.available && it.downloadUrl.isNotBlank() }
            ?: return null
        return DownloadTarget(
            jobId = "",
            regionId = region.id,
            regionName = region.name,
            fingerprint = artifact.fingerprint.ifBlank { fingerprintFrom(artifact.downloadUrl) },
            size = artifact.size,
            sha256 = artifact.sha256,
            downloadUrl = artifact.downloadUrl,
            sourceDate = region.sourceDate,
        )
    }

    private fun RegionDownloadRecord.toServerJob(): ServerJob = ServerJob(
        id = jobId,
        regionId = regionId,
        regionName = regionName,
        state = "completed",
        step = "verify",
        progress = 1.0,
        message = "",
        error = "",
        cached = true,
        artifactSize = total ?: 0,
        artifactSha256 = sha256.orEmpty(),
        downloadUrl = downloadUrl,
    )

    private fun fingerprintFrom(url: String): String =
        Regex("/([0-9a-f]{64})/").find(url)?.groupValues?.get(1).orEmpty()

    private fun friendly(e: Exception): String = when (e) {
        is RegionHttpException -> e.message ?: "Server request failed"
        is RegionPackageException -> e.message ?: "The package could not be installed"
        else -> e.message ?: "Something went wrong"
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
        const val MAX_POLL_INTERVAL_MS = 15_000L
        const val POLL_FAILURES_BEFORE_WARNING = 3
        const val POLL_LOST_MESSAGE =
            "Lost contact with the server while it builds — still retrying."

        /**
         * Safety backstop for a pending active-map deletion. The route screen
         * signals the drained graph normally; the timeout only matters if the
         * screen was disposed before it could report (for example, the process
         * is going away), where unlinking an inactive install is still safe.
         */
        const val PENDING_RELEASE_TIMEOUT_MS = 15_000L
    }
}

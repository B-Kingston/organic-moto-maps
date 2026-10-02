package com.organicmoto.maps.region

/**
 * Deterministic Maps-screen states used by `tools/test/visual.py` and by the
 * JVM model tests. They describe intermediate download/import/delete states so
 * those screens can be inspected and asserted without a live map server, a
 * network transfer, or a real SAF destination.
 *
 * This is debug visual tooling: nothing here is reachable from production UI
 * flows, and no scenario touches the registry or the network.
 */
object VisualMapsScenarios {

    const val CATALOG_READY = "catalog-ready"
    const val BUILD_FAILED = "build-failed"
    const val DOWNLOAD_PROGRESS = "download-progress"
    const val DOWNLOAD_INTERRUPTED = "download-interrupted"
    const val PACKAGE_SAVED = "package-saved"
    const val RECOVERED_PACKAGE = "recovered-package"
    const val INSTALLED = "installed"
    const val REMOVAL_PENDING = "removal-pending"
    const val DOWNLOAD_RETRYING = "download-retrying"
    const val DOWNLOAD_VERIFYING = "download-verifying"
    const val SERVER_ERROR = "server-error"

    val names: List<String> = listOf(
        CATALOG_READY,
        BUILD_FAILED,
        DOWNLOAD_PROGRESS,
        DOWNLOAD_INTERRUPTED,
        PACKAGE_SAVED,
        RECOVERED_PACKAGE,
        INSTALLED,
        REMOVAL_PENDING,
        DOWNLOAD_RETRYING,
        DOWNLOAD_VERIFYING,
        SERVER_ERROR,
    )

    private const val FINGERPRINT = "1ea03fcfc3100000000000000000000000000000000000000000000000000000"
    private const val PACKAGE_BYTES = 357_091_613L
    private const val SOURCE_DATE = "260815"

    fun state(name: String): MapsState? = when (name) {
        CATALOG_READY -> base(catalog = listOf(catalogRegion()))
        BUILD_FAILED -> base(
            catalog = listOf(catalogRegion().copy(artifact = null)),
            activeJob = ServerJob(
                id = "a1575e4b6fd941d81c10ab05",
                regionId = "queensland",
                regionName = "Queensland",
                state = "failed",
                step = "import",
                progress = 0.4,
                message = "Importing the routing graph",
                error = "The extract download returned an HTML page instead of a PBF",
                cached = false,
                artifactSize = 0,
                artifactSha256 = "",
                downloadUrl = "",
            ),
        )
        DOWNLOAD_PROGRESS -> base(
            catalog = listOf(catalogRegion()),
            download = DownloadProgress(
                jobId = "a1575e4b6fd941d81c10ab05",
                regionName = "Queensland",
                bytes = 120_000_000,
                total = PACKAGE_BYTES,
                phase = DownloadPhase.DOWNLOADING,
            ),
        )
        DOWNLOAD_INTERRUPTED -> base(
            catalog = listOf(catalogRegion()),
            interrupted = InterruptedDownload(
                jobId = "a1575e4b6fd941d81c10ab05",
                regionId = "queensland",
                regionName = "Queensland",
                fingerprint = FINGERPRINT,
                downloadUrl = "/api/v1/artifacts/queensland/$FINGERPRINT/queensland.motomap",
                etag = "\"artifact-v1\"",
                bytes = 180_000_000,
                total = PACKAGE_BYTES,
            ),
        )
        PACKAGE_SAVED -> base(
            catalog = listOf(catalogRegion()),
            savedPackage = SavedPackage(
                regionId = "queensland",
                regionName = "Queensland",
                fileName = "queensland-260815.motomap",
                bytes = PACKAGE_BYTES,
                uri = "",
            ),
            notice = "Saved \"queensland-260815.motomap\" to your device — tap Import saved file to install it.",
        )
        RECOVERED_PACKAGE -> base(
            catalog = listOf(catalogRegion()),
            recoveredPackage = RecoveredPackage(
                regionId = "queensland",
                regionName = "Queensland",
                fileName = "queensland-$FINGERPRINT.motomap",
                bytes = PACKAGE_BYTES,
            ),
        )
        INSTALLED -> base(
            catalog = listOf(catalogRegion()),
            installed = listOf(installedRegion()),
        )
        REMOVAL_PENDING -> base(
            catalog = listOf(catalogRegion()),
            installed = listOf(installedRegion()),
            pendingRemoval = PendingRemoval(
                installId = installId(),
                regionId = "queensland",
                regionName = "Queensland",
                bytes = PACKAGE_BYTES,
            ),
            notice = "Switching to the bundled Queensland data…",
        )
        DOWNLOAD_RETRYING -> base(
            catalog = listOf(catalogRegion()),
            download = DownloadProgress(
                jobId = "a1575e4b6fd941d81c10ab05",
                regionName = "Queensland",
                bytes = 214_000_000,
                total = PACKAGE_BYTES,
                phase = DownloadPhase.DOWNLOADING,
                retry = DownloadRetry(
                    attempt = 2,
                    maxAttempts = DownloadRetryPolicy.MAX_ATTEMPTS,
                    secondsUntilRetry = 4,
                    reason = "The connection dropped",
                ),
            ),
        )
        DOWNLOAD_VERIFYING -> base(
            catalog = listOf(catalogRegion()),
            download = DownloadProgress(
                jobId = "a1575e4b6fd941d81c10ab05",
                regionName = "Queensland",
                bytes = PACKAGE_BYTES,
                total = PACKAGE_BYTES,
                phase = DownloadPhase.VERIFYING,
            ),
        )
        SERVER_ERROR -> base().copy(
            serverAddress = "https://maps.example.net",
            error = "Couldn't connect to \"maps.example.net\". Check the address and port, " +
                "and that the server is running.",
        )
        else -> null
    }

    private fun base(
        catalog: List<ServerRegion> = emptyList(),
        activeJob: ServerJob? = null,
        download: DownloadProgress? = null,
        interrupted: InterruptedDownload? = null,
        savedPackage: SavedPackage? = null,
        recoveredPackage: RecoveredPackage? = null,
        pendingRemoval: PendingRemoval? = null,
        installed: List<InstalledRegion> = emptyList(),
        notice: String? = null,
    ): MapsState = MapsState(
        serverAddress = "https://maps.example.net",
        serverChecked = catalog.isNotEmpty(),
        serverVersion = "dev",
        generationEnabled = true,
        catalog = catalog,
        activeJob = activeJob,
        download = download,
        interrupted = interrupted,
        savedPackage = savedPackage,
        recoveredPackage = recoveredPackage,
        pendingRemoval = pendingRemoval,
        installed = installed,
        notice = notice,
    )

    private fun catalogRegion(): ServerRegion = ServerRegion(
        id = "queensland",
        name = "Queensland",
        coverage = "Queensland, Australia",
        disabled = false,
        maxZoom = 14,
        sourceDate = SOURCE_DATE,
        sourceSha256 = "b3e0b4cf98f8b9a4ef54a7c461be3b9471a64624ea3001229a585043942bd3fa",
        sourcePinned = true,
        artifact = ServerArtifact(
            available = true,
            fingerprint = FINGERPRINT,
            size = PACKAGE_BYTES,
            sha256 = "b3e0b4cf98f8b9a4ef54a7c461be3b9471a64624ea3001229a585043942bd3fa",
            generatedAt = "2026-08-15T00:00:00Z",
            downloadUrl = "/api/v1/artifacts/queensland/$FINGERPRINT/queensland.motomap",
        ),
        activeJobId = null,
    )

    private fun installId(): String = "queensland-${FINGERPRINT.take(12)}"

    private fun installedRegion(): InstalledRegion = InstalledRegion(
        installId = installId(),
        regionId = "queensland",
        regionName = "Queensland",
        coverage = "Queensland, Australia",
        sourceDate = SOURCE_DATE,
        sourceSha256 = "b3e0b4cf98f8b9a4ef54a7c461be3b9471a64624ea3001229a585043942bd3fa",
        pipelineFingerprint = FINGERPRINT,
        installedAtEpochMs = 1_780_000_000_000L,
        bytes = PACKAGE_BYTES,
        dirName = installId(),
    )
}

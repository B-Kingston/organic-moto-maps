package com.organicmoto.maps.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog-row decision is the fix for the stale-job trap: a completed,
 * failed, or canceled build job is informational only and must never hide the
 * Download/Retry action. It also pins the installed-map and delete-confirmation
 * wording (dataset name, real installed size, user Files untouched).
 */
class RegionUiModelTest {

    private fun region(
        id: String = "queensland",
        artifact: ServerArtifact? = artifact(),
        disabled: Boolean = false,
    ) = ServerRegion(
        id = id,
        name = "Queensland",
        coverage = "Queensland, Australia",
        disabled = disabled,
        maxZoom = 14,
        sourceDate = "260815",
        sourceSha256 = "a".repeat(64),
        sourcePinned = true,
        artifact = artifact,
        activeJobId = null,
    )

    private fun artifact(url: String = "/api/v1/artifacts/queensland/${"b".repeat(64)}/queensland.motomap") =
        ServerArtifact(
            available = true,
            fingerprint = "b".repeat(64),
            size = 357_091_613,
            sha256 = "b".repeat(64),
            generatedAt = "2026-08-15T00:00:00Z",
            downloadUrl = url,
        )

    private fun job(
        state: String,
        regionId: String = "queensland",
        downloadUrl: String = "/api/v1/artifacts/queensland/${"b".repeat(64)}/queensland.motomap",
        error: String = "",
    ) = ServerJob(
        id = "job-1",
        regionId = regionId,
        regionName = "Queensland",
        state = state,
        step = "import",
        progress = if (state == "completed") 1.0 else 0.5,
        message = "Importing",
        error = error,
        cached = false,
        artifactSize = 357_091_613,
        artifactSha256 = "b".repeat(64),
        downloadUrl = downloadUrl,
    )

    private fun model(
        region: ServerRegion = region(),
        activeJob: ServerJob? = null,
        busy: Boolean = false,
        downloadActive: Boolean = false,
        pendingRemoval: Boolean = false,
    ) = RegionUiModel.catalogRow(region, activeJob, busy, downloadActive, pendingRemoval)

    @Test
    fun aTerminalCompletedJobStillOffersDownload() {
        val model = model(activeJob = job("completed"))
        assertEquals(CatalogAction.DOWNLOAD, model.action)
        assertEquals("Download", model.actionLabel)
        assertTrue(model.actionEnabled)
        assertFalse("a terminal job must not render the progress branch", model.showProgress)
        assertFalse(model.showCancel)
        assertEquals("Build complete — ready to download", model.statusLine)
    }

    @Test
    fun aTerminalFailedJobOffersRetry() {
        val model = model(
            region = region(artifact = null),
            activeJob = job("failed", downloadUrl = "", error = "Extract download returned HTML"),
        )
        assertEquals(CatalogAction.RETRY_BUILD, model.action)
        assertEquals("Retry build", model.actionLabel)
        assertTrue(model.actionEnabled)
        assertFalse(model.showProgress)
        assertEquals("Build failed", model.statusLine)
        assertEquals("Extract download returned HTML", model.errorLine)
    }

    @Test
    fun aCanceledJobOffersRetry() {
        val model = model(region = region(artifact = null), activeJob = job("canceled", downloadUrl = ""))
        assertEquals(CatalogAction.RETRY_BUILD, model.action)
        assertEquals("Build canceled", model.statusLine)
    }

    @Test
    fun aCompletedJobWithoutACachedArtifactDownloadsFromTheJob() {
        val model = model(region = region(artifact = null), activeJob = job("completed"))
        assertEquals(CatalogAction.DOWNLOAD, model.action)
    }

    @Test
    fun anActiveJobOwnsTheRowWithProgressAndCancel() {
        val model = model(activeJob = job("running"))
        assertEquals(CatalogAction.NONE, model.action)
        assertTrue(model.showProgress)
        assertTrue(model.showCancel)
        assertTrue(model.statusLine!!.startsWith("running:"))
    }

    @Test
    fun aCachedArtifactOffersDownloadWithoutAJob() {
        val model = model()
        assertEquals(CatalogAction.DOWNLOAD, model.action)
        assertTrue(model.actionEnabled)
    }

    @Test
    fun anUncachedRegionWithoutAJobOffersRequestBuild() {
        val model = model(region = region(artifact = null))
        assertEquals(CatalogAction.REQUEST_BUILD, model.action)
        assertEquals("Request build", model.actionLabel)
    }

    @Test
    fun aDisabledRegionHasNoAction() {
        val model = model(region = region(disabled = true))
        assertEquals(CatalogAction.NONE, model.action)
        assertEquals("Disabled by the operator", model.statusLine)
    }

    @Test
    fun busyAndConcurrentOperationsDisableTheActionButKeepItVisible() {
        assertFalse(model(busy = true).actionEnabled)
        assertFalse(model(downloadActive = true).actionEnabled)
        assertFalse(model(pendingRemoval = true).actionEnabled)
        // The action itself is still offered so a finished download cannot
        // strand the row without a retry.
        assertEquals(CatalogAction.DOWNLOAD, model(downloadActive = true).action)
    }

    @Test
    fun aJobForAnotherRegionDoesNotOwnThisRow() {
        val model = model(
            region = region(id = "tasmania"),
            activeJob = job("running", regionId = "queensland"),
        )
        assertEquals(CatalogAction.DOWNLOAD, model.action)
        assertFalse(model.showProgress)
    }

    @Test
    fun installedSummaryNamesTheSourceAndActualInstalledBytes() {
        val install = InstalledRegion(
            installId = "queensland-abc",
            regionId = "queensland",
            regionName = "Queensland",
            coverage = "Queensland, Australia",
            sourceDate = "260815",
            sourceSha256 = "a".repeat(64),
            pipelineFingerprint = "b".repeat(64),
            installedAtEpochMs = 1L,
            bytes = 12 * 1024 * 1024,
            dirName = "queensland-abc",
        )
        val summary = RegionUiModel.installedSummary(install)
        assertTrue(summary, summary.contains("source 260815"))
        assertTrue(summary, summary.contains("12.0 MiB installed"))
    }

    @Test
    fun deleteConfirmationNamesTheDatasetTheReclaimedSpaceAndUserFiles() {
        val install = InstalledRegion(
            installId = "queensland-abc",
            regionId = "queensland",
            regionName = "Queensland",
            coverage = null,
            sourceDate = "260815",
            sourceSha256 = "a".repeat(64),
            pipelineFingerprint = "b".repeat(64),
            installedAtEpochMs = 1L,
            bytes = 357_091_613,
            dirName = "queensland-abc",
        )
        val inactive = RegionUiModel.deleteConfirmation(install, active = false)
        assertTrue(inactive, inactive.contains("Queensland"))
        assertTrue(inactive, inactive.contains("source 260815"))
        assertTrue(inactive, inactive.contains(RegionUiModel.formatBytes(357_091_613)))
        assertTrue(inactive, inactive.contains("in Files is not removed"))
        assertFalse(inactive, inactive.contains("bundled"))

        val active = RegionUiModel.deleteConfirmation(install, active = true)
        assertTrue(active, active.contains("bundled"))
        assertTrue(active, active.contains("releases the routing graph"))
    }

    @Test
    fun bundledDataIsNeverClaimedAsReclaimable() {
        val summary = RegionUiModel.bundledSummary()
        assertTrue(summary, summary.contains("cannot be deleted"))
        assertFalse(summary, summary.contains("MiB"))
    }

    @Test
    fun formatBytesIsHumanReadable() {
        assertEquals("512 B", RegionUiModel.formatBytes(512))
        assertEquals("1.0 KiB", RegionUiModel.formatBytes(1024))
        assertEquals("340.5 MiB", RegionUiModel.formatBytes(357_091_613))
    }

    @Test
    fun unknownRegionsAreNotInvented() {
        assertNull(RegionUiModel.catalogRow(region(), null, false, false, false).errorLine)
    }

    @Test
    fun anAlreadyInstalledPackageSaysSoButKeepsDownloadAvailable() {
        fun install(fingerprint: String) = InstalledRegion(
            installId = "queensland-${fingerprint.take(12)}",
            regionId = "queensland",
            regionName = "Queensland",
            coverage = null,
            sourceDate = "260815",
            sourceSha256 = "a".repeat(64),
            pipelineFingerprint = fingerprint,
            installedAtEpochMs = 1L,
            bytes = 1L,
            dirName = "queensland-${fingerprint.take(12)}",
        )
        val same = RegionUiModel.catalogRow(region(), null, false, false, false, listOf(install("b".repeat(64))))
        assertEquals(CatalogAction.DOWNLOAD, same.action)
        assertTrue(same.actionEnabled)
        assertEquals("This version is installed on this device", same.statusLine)

        // An older install of the same region is not the server's version.
        val older = RegionUiModel.catalogRow(region(), null, false, false, false, listOf(install("c".repeat(64))))
        assertNull(older.statusLine)
    }
}

package com.organicmoto.maps.region

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The visual-preview scenarios are the repeatable intermediate states that
 * `tools/test/visual.py` captures. They must keep describing the real UI
 * decisions: a preview that hides the Download action or invents an installed
 * size would make the screenshots lie.
 */
class VisualMapsScenariosTest {

    @Test
    fun everyNamedScenarioResolvesAndUnknownNamesDoNot() {
        assertTrue(VisualMapsScenarios.names.isNotEmpty())
        VisualMapsScenarios.names.forEach { name ->
            assertNotNull("scenario $name", VisualMapsScenarios.state(name))
        }
        assertNull(VisualMapsScenarios.state("no-such-scenario"))
    }

    @Test
    fun catalogReadyOffersDownloadWithTheTransferSize() {
        val state = VisualMapsScenarios.state(VisualMapsScenarios.CATALOG_READY)!!
        val region = state.catalog.single()
        assertTrue(region.hasCachedArtifact)
        val model = RegionUiModel.catalogRow(region, state.activeJob, false, false, false)
        assertEquals(CatalogAction.DOWNLOAD, model.action)
        assertEquals(357_091_613L, region.artifact!!.size)
    }

    @Test
    fun buildFailedKeepsRetryVisibleWithTheServerError() {
        val state = VisualMapsScenarios.state(VisualMapsScenarios.BUILD_FAILED)!!
        val model = RegionUiModel.catalogRow(state.catalog.single(), state.activeJob, false, false, false)
        assertEquals(CatalogAction.RETRY_BUILD, model.action)
        assertTrue(model.errorLine!!.isNotBlank())
    }

    @Test
    fun downloadProgressAndInterruptedStatesCarryRealByteCounts() {
        val downloading = VisualMapsScenarios.state(VisualMapsScenarios.DOWNLOAD_PROGRESS)!!
        assertEquals(DownloadPhase.DOWNLOADING, downloading.download!!.phase)
        assertTrue(downloading.download!!.bytes > 0)
        assertEquals(357_091_613L, downloading.download!!.total)

        val interrupted = VisualMapsScenarios.state(VisualMapsScenarios.DOWNLOAD_INTERRUPTED)!!
        assertTrue(interrupted.interrupted!!.bytes > 0)
        assertEquals(357_091_613L, interrupted.interrupted!!.total)
    }

    @Test
    fun savedAndRecoveredStatesNameTheFileAndArchiveSize() {
        val saved = VisualMapsScenarios.state(VisualMapsScenarios.PACKAGE_SAVED)!!
        assertEquals("queensland-260815.motomap", saved.savedPackage!!.fileName)
        assertEquals(357_091_613L, saved.savedPackage!!.bytes)

        val recovered = VisualMapsScenarios.state(VisualMapsScenarios.RECOVERED_PACKAGE)!!
        assertEquals(357_091_613L, recovered.recoveredPackage!!.bytes)
    }

    @Test
    fun installedStateShowsAnActualInstalledSizeAndNoPendingRemoval() {
        val state = VisualMapsScenarios.state(VisualMapsScenarios.INSTALLED)!!
        val install = state.installed.single()
        assertEquals(357_091_613L, install.bytes)
        assertTrue(RegionUiModel.installedSummary(install).contains("installed"))
        assertNull(state.pendingRemoval)
    }

    @Test
    fun removalPendingStateMarksTheMapBeingDeleted() {
        val state = VisualMapsScenarios.state(VisualMapsScenarios.REMOVAL_PENDING)!!
        val install = state.installed.single()
        assertEquals(install.installId, state.pendingRemoval!!.installId)
        assertTrue(state.notice!!.contains("bundled"))
    }

    @Test
    fun previewStatesNeverClaimABundledDelete() {
        VisualMapsScenarios.names.forEach { name ->
            val state = VisualMapsScenarios.state(name)!!
            state.pendingRemoval?.let { pending ->
                assertFalse(pending.installId.contains("bundled"))
            }
        }
    }

    @Test
    fun retryingAndVerifyingStatesKeepTheirBytesAndExplainTheWait() {
        val retrying = VisualMapsScenarios.state(VisualMapsScenarios.DOWNLOAD_RETRYING)!!.download!!
        assertEquals(DownloadPhase.DOWNLOADING, retrying.phase)
        assertTrue(retrying.bytes > 0)
        val retry = retrying.retry!!
        assertTrue(retry.attempt in 2..retry.maxAttempts)
        assertTrue(retry.secondsUntilRetry > 0)

        val verifying = VisualMapsScenarios.state(VisualMapsScenarios.DOWNLOAD_VERIFYING)!!.download!!
        assertEquals(DownloadPhase.VERIFYING, verifying.phase)
        assertEquals(verifying.total, verifying.bytes)
        assertNull(verifying.retry)
    }

    @Test
    fun serverErrorStateIsDisconnectedWithAnActionableMessage() {
        val state = VisualMapsScenarios.state(VisualMapsScenarios.SERVER_ERROR)!!
        assertFalse(state.serverChecked)
        assertTrue(state.catalog.isEmpty())
        assertTrue(state.error!!.contains("Check the address"))
    }
}

package com.organicmoto.maps.region

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.organicmoto.maps.MapsSettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Maps screen decisions that protect the user's ability to retry:
 * a terminal build job keeps Download/Retry visible (the stale-job regression),
 * every download/import intermediate state offers its next explicit action, and
 * the installed-map delete flow has a glove-sized rubbish bin with an honest
 * confirmation. The screen is rendered directly from synthetic [MapsState]s, so
 * the assertions never depend on a server or a SAF picker.
 */
class MapsSettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var downloadedRegionId: String? = null
    private var removedInstallId: String? = null

    private fun show(state: MapsState, rideActive: Boolean = false) {
        composeRule.setContent {
            MaterialTheme {
                MapsSettingsScreen(
                    state = state,
                    rideActive = rideActive,
                    onDismiss = {},
                    onCheckServer = {},
                    onToggleInsecure = {},
                    onRequestBuild = {},
                    onCancelBuild = {},
                    onDownloadPackage = { downloadedRegionId = it },
                    onCancelDownload = {},
                    onResumeDownload = {},
                    onSaveRecoveredPackage = {},
                    onImportRecoveredPackage = {},
                    onImportSavedPackage = {},
                    onImportPackage = {},
                    onActivate = {},
                    onActivateBundled = {},
                    onRemove = { removedInstallId = it },
                )
            }
        }
    }

    private fun catalogRegion(artifactAvailable: Boolean = true) = ServerRegion(
        id = "queensland",
        name = "Queensland",
        coverage = "Queensland, Australia",
        disabled = false,
        maxZoom = 14,
        sourceDate = "260815",
        sourceSha256 = "a".repeat(64),
        sourcePinned = true,
        artifact = if (artifactAvailable) {
            ServerArtifact(
                available = true,
                fingerprint = "b".repeat(64),
                size = 357_091_613,
                sha256 = "b".repeat(64),
                generatedAt = "2026-08-15T00:00:00Z",
                downloadUrl = "/api/v1/artifacts/queensland/${"b".repeat(64)}/queensland.motomap",
            )
        } else {
            null
        },
        activeJobId = null,
    )

    private fun job(state: String, downloadUrl: String = "/api/v1/artifacts/queensland/${"b".repeat(64)}/queensland.motomap") =
        ServerJob(
            id = "job-1",
            regionId = "queensland",
            regionName = "Queensland",
            state = state,
            step = "import",
            progress = 0.5,
            message = "Importing",
            error = if (state == "failed") "The extract download returned HTML" else "",
            cached = false,
            artifactSize = 357_091_613,
            artifactSha256 = "b".repeat(64),
            downloadUrl = downloadUrl,
        )

    private fun installedRegion(
        installId: String = "monaco-1234567890ab",
        regionId: String = "monaco",
        name: String = "Monaco",
    ) = InstalledRegion(
        installId = installId,
        regionId = regionId,
        regionName = name,
        coverage = "Monaco",
        sourceDate = "260928",
        sourceSha256 = "a".repeat(64),
        pipelineFingerprint = "b".repeat(64),
        installedAtEpochMs = 1L,
        bytes = 12 * 1024 * 1024,
        dirName = installId,
    )

    @Test
    fun aTerminalCompletedJobKeepsDownloadVisibleAndClickable() {
        show(
            MapsState(
                serverChecked = true,
                catalog = listOf(catalogRegion()),
                activeJob = job("completed"),
            ),
        )
        val download = composeRule.onNodeWithContentDescription("Download package queensland")
        download.assertExists()
        download.assertHasClickAction()
        download.assertIsEnabled()
        composeRule.onNodeWithText("Build complete — ready to download").assertExists()
        download.performClick()
        assertEquals("queensland", downloadedRegionId)
        composeRule.onNodeWithContentDescription("Cancel build queensland").assertDoesNotExist()
    }

    @Test
    fun aTerminalFailedJobOffersRetryWithTheServerError() {
        show(
            MapsState(
                serverChecked = true,
                catalog = listOf(catalogRegion(artifactAvailable = false)),
                activeJob = job("failed", downloadUrl = ""),
            ),
        )
        composeRule.onNodeWithContentDescription("Retry package queensland")
            .assertExists()
            .assertIsEnabled()
        composeRule.onNodeWithText("Build failed").assertExists()
        composeRule.onNodeWithText("The extract download returned HTML").assertExists()
        composeRule.onNodeWithContentDescription("Download package queensland").assertDoesNotExist()
    }

    @Test
    fun anActiveJobShowsProgressAndCancelOnly() {
        show(
            MapsState(
                serverChecked = true,
                catalog = listOf(catalogRegion()),
                activeJob = job("running"),
            ),
        )
        composeRule.onNodeWithContentDescription("Cancel build queensland").assertExists()
        composeRule.onNodeWithContentDescription("Download package queensland").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Retry package queensland").assertDoesNotExist()
    }

    @Test
    fun theInstalledMapRubbishBinIsGloveSizedAndConfirmsBeforeDeleting() {
        show(
            MapsState(
                installed = listOf(installedRegion()),
                activeInstallId = null,
            ),
        )
        val delete = composeRule.onNodeWithContentDescription("Delete Monaco map")
        delete.assertExists()
        delete.assertHasClickAction()
        val minimum = 48f * composeRule.density.density
        val bounds: Rect = delete.fetchSemanticsNode().boundsInRoot
        assertTrue("delete target ${bounds.width} x ${bounds.height}", bounds.width >= minimum && bounds.height >= minimum)
        delete.performClick()
        composeRule.onNodeWithText("Delete installed map?").assertExists()
        composeRule.onNodeWithText("Delete \"Monaco\" (source 260928)?", substring = true).assertExists()
        composeRule.onNodeWithText("This frees 12.0 MiB on this device.", substring = true).assertExists()
        composeRule.onNodeWithText("in Files is not removed", substring = true).assertExists()
        // Cancel keeps the map.
        composeRule.onNodeWithContentDescription("Cancel delete Monaco map").performClick()
        composeRule.onNodeWithText("Delete installed map?").assertDoesNotExist()
        assertEquals(null, removedInstallId)
        // Confirm reports the exact install.
        delete.performClick()
        composeRule.onNodeWithContentDescription("Confirm delete Monaco map").performClick()
        assertEquals("monaco-1234567890ab", removedInstallId)
    }

    @Test
    fun theActiveMapDeleteExplainsTheBundledSwitchAndIsDisabledMidRide() {
        show(
            MapsState(
                installed = listOf(installedRegion()),
                activeInstallId = "monaco-1234567890ab",
            ),
            rideActive = true,
        )
        composeRule.onNodeWithContentDescription("Delete Monaco map").assertIsNotEnabled()
        composeRule.onNodeWithText("Stop the ride before deleting the active map.").assertExists()
    }

    @Test
    fun theActiveMapDeleteIsAllowedOffRideAndNamesTheSwitch() {
        show(
            MapsState(
                installed = listOf(installedRegion()),
                activeInstallId = "monaco-1234567890ab",
            ),
            rideActive = false,
        )
        composeRule.onNodeWithContentDescription("Delete Monaco map").assertIsEnabled().performClick()
        composeRule.onNodeWithText("switches to the bundled", substring = true).assertExists()
        composeRule.onNodeWithText("releases the routing graph", substring = true).assertExists()
    }

    @Test
    fun bundledOnlyStateIsHonestAndHasNoDeleteControl() {
        show(MapsState(installed = emptyList()))
        composeRule.onNodeWithText("Queensland (bundled)").assertExists()
        composeRule.onNodeWithText("cannot be deleted", substring = true).assertExists()
        composeRule.onNodeWithText("No downloaded maps are installed.", substring = true).assertExists()
        assertTrue(
            composeRule.onAllNodes(hasContentDescription("Delete", substring = true))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun theSavingPhaseCannotBeCancelled() {
        show(
            MapsState(
                download = DownloadProgress(
                    jobId = "job-1",
                    regionName = "Queensland",
                    bytes = 357_091_613,
                    total = 357_091_613,
                    phase = DownloadPhase.SAVING,
                ),
            ),
        )
        composeRule.onNodeWithText("Saving Queensland to your file…").assertExists()
        composeRule.onNodeWithContentDescription("Cancel download").assertDoesNotExist()
    }

    @Test
    fun aSavedPackageNamesTheFileAndOffersImport() {
        show(
            MapsState(
                savedPackage = SavedPackage(
                    regionId = "queensland",
                    regionName = "Queensland",
                    fileName = "queensland-260815.motomap",
                    bytes = 357_091_613,
                    uri = "content://downloads/queensland-260815.motomap",
                ),
            ),
        )
        composeRule.onNodeWithText("queensland-260815.motomap", substring = true).assertExists()
        composeRule.onNodeWithText("stays in your Files", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Import saved file")
            .assertExists()
            .assertHasClickAction()
    }

    @Test
    fun anInterruptedDownloadOffersAResume() {
        show(
            MapsState(
                interrupted = InterruptedDownload(
                    jobId = "job-1",
                    regionId = "queensland",
                    regionName = "Queensland",
                    fingerprint = "b".repeat(64),
                    downloadUrl = "/api/v1/artifacts/queensland/${"b".repeat(64)}/queensland.motomap",
                    etag = null,
                    bytes = 180_000_000,
                    total = 357_091_613,
                ),
            ),
        )
        composeRule.onNodeWithText("171.7 MiB", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Resume download")
            .assertExists()
            .assertHasClickAction()
    }

    @Test
    fun fontScaleTwoKeepsTheDeleteTargetAndRowsOnScreen() {
        val density = composeRule.density
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
            ) {
                MaterialTheme {
                    MapsSettingsScreen(
                        state = MapsState(
                            installed = listOf(installedRegion()),
                            activeInstallId = "monaco-1234567890ab",
                        ),
                        rideActive = false,
                        onDismiss = {},
                        onCheckServer = {},
                        onToggleInsecure = {},
                        onRequestBuild = {},
                        onCancelBuild = {},
                        onDownloadPackage = {},
                        onCancelDownload = {},
                        onResumeDownload = {},
                        onSaveRecoveredPackage = {},
                        onImportRecoveredPackage = {},
                        onImportSavedPackage = {},
                        onImportPackage = {},
                        onActivate = {},
                        onActivateBundled = {},
                        onRemove = {},
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("Delete Monaco map").assertExists()
        composeRule.onNodeWithText("source 260928", substring = true).assertExists()
    }
}

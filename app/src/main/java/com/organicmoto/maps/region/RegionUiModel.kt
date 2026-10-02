package com.organicmoto.maps.region

import java.util.Locale

/**
 * Pure decisions for the Maps screen. Keeping the catalog-row and installed-map
 * rules here (instead of inline in Compose) makes the retry/terminal-job
 * contract and the delete-confirmation wording JVM-testable: a terminal build
 * job must never hide the Download action, and the delete text must name the
 * dataset, its real installed size, and the fact that user Files are untouched.
 */

/** Primary action a catalog row offers. */
enum class CatalogAction { REQUEST_BUILD, RETRY_BUILD, DOWNLOAD, NONE }

/** Everything the catalog row renders, derived only from its inputs. */
data class CatalogRowModel(
    val action: CatalogAction,
    val actionLabel: String,
    val actionDescription: String,
    val actionEnabled: Boolean,
    val showProgress: Boolean,
    val showCancel: Boolean,
    val statusLine: String?,
    val errorLine: String?,
)

object RegionUiModel {

    /**
     * The catalog row decision.
     *
     * A job only owns the row while it is genuinely active. Completed, failed,
     * and canceled jobs are informational: the row keeps offering Download (for
     * a cached artifact or a completed job with a download URL) or Retry build,
     * so a stale terminal job can never trap the user without a retry.
     */
    fun catalogRow(
        region: ServerRegion,
        activeJob: ServerJob?,
        busy: Boolean,
        downloadActive: Boolean,
        pendingRemoval: Boolean,
        installed: List<InstalledRegion> = emptyList(),
    ): CatalogRowModel {
        if (region.disabled) {
            return CatalogRowModel(
                action = CatalogAction.NONE,
                actionLabel = "",
                actionDescription = "",
                actionEnabled = false,
                showProgress = false,
                showCancel = false,
                statusLine = "Disabled by the operator",
                errorLine = null,
            )
        }
        val job = activeJob?.takeIf { it.regionId == region.id }
        if (job != null && job.isActive) {
            return CatalogRowModel(
                action = CatalogAction.NONE,
                actionLabel = "",
                actionDescription = "",
                actionEnabled = false,
                showProgress = true,
                showCancel = true,
                statusLine = "${job.state}: ${job.message.ifBlank { job.step }}",
                errorLine = job.error.takeIf { it.isNotBlank() },
            )
        }
        val artifactDownloadable = region.hasCachedArtifact &&
            !region.artifact?.downloadUrl.isNullOrBlank()
        val jobDownloadable = job?.isCompleted == true && job.downloadUrl.isNotBlank()
        val canDownload = artifactDownloadable || jobDownloadable
        val action = when {
            canDownload -> CatalogAction.DOWNLOAD
            job?.isFailed == true || job?.isCanceled == true -> CatalogAction.RETRY_BUILD
            else -> CatalogAction.REQUEST_BUILD
        }
        // The server's current package is already installed here: say so, so
        // Download reads as optional (a new copy) rather than still needed.
        val alreadyInstalled = region.artifact?.fingerprint?.takeIf { it.isNotBlank() }?.let { fingerprint ->
            installed.any { it.regionId == region.id && it.pipelineFingerprint == fingerprint }
        } ?: false
        val statusLine = when {
            job?.isCompleted == true && canDownload -> "Build complete — ready to download"
            job?.isCompleted == true -> "Build completed without a download link"
            job?.isFailed == true -> "Build failed"
            job?.isCanceled == true -> "Build canceled"
            alreadyInstalled -> "This version is installed on this device"
            else -> null
        }
        val (label, description) = when (action) {
            CatalogAction.DOWNLOAD -> "Download" to "Download package ${region.id}"
            CatalogAction.RETRY_BUILD -> "Retry build" to "Retry package ${region.id}"
            CatalogAction.REQUEST_BUILD -> "Request build" to "Request package ${region.id}"
            CatalogAction.NONE -> "" to ""
        }
        return CatalogRowModel(
            action = action,
            actionLabel = label,
            actionDescription = description,
            actionEnabled = !busy && !downloadActive && !pendingRemoval,
            showProgress = false,
            showCancel = false,
            statusLine = statusLine,
            errorLine = job?.error?.takeIf { it.isNotBlank() },
        )
    }

    /** Region, source date, and actual installed bytes — newest line first. */
    fun installedSummary(install: InstalledRegion): String = listOfNotNull(
        install.coverage,
        "source ${install.sourceDate}",
        "${formatBytes(install.bytes)} installed",
    ).joinToString(" · ")

    /**
     * Delete confirmation. It names the dataset and the space actually
     * reclaimed, and it never implies that a user-saved `.motomap` in Files is
     * deleted — that file is user-managed and stays where it is.
     */
    fun deleteConfirmation(install: InstalledRegion, active: Boolean): String = buildString {
        append("Delete \"${install.regionName}\" (source ${install.sourceDate})?\n\n")
        append("This frees ${formatBytes(install.bytes)} on this device. ")
        append("Any .motomap package you downloaded or saved in Files is not removed.")
        if (active) {
            append("\n\nThis is the active map, so the app switches to the bundled ")
            append("Queensland data first, releases the routing graph, and then deletes it.")
        }
    }

    /** The bundled fallback is immutable APK data; it is never fake-reclaimed. */
    fun bundledSummary(): String =
        "Bundled with the app — always available and cannot be deleted."

    /** Honest empty state when only the bundled data exists. */
    fun installedEmptySummary(): String =
        "No downloaded maps are installed. The bundled Queensland data is always available."

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KiB", "MiB", "GiB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}

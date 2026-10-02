package com.organicmoto.maps

import com.organicmoto.maps.routing.navigation.GpsFix
import com.organicmoto.maps.routing.navigation.LOCATION_FIX_MAX_AGE_MS
import com.organicmoto.maps.storage.GeoPoint

internal const val CURRENT_LOCATION_LABEL = "Current location"
internal const val CURRENT_LOCATION_FIX_TIMEOUT_MS = 10_000L

internal enum class CurrentLocationUnavailableReason(val hint: String) {
    PERMISSION_DENIED("Location permission denied"),
    NO_RECENT_FIX("No recent location fix. Check location settings."),
}

internal data class CurrentLocationRequest(
    val id: Long,
    val inputRevision: Long,
)

internal sealed interface CurrentLocationStatus {
    data object Idle : CurrentLocationStatus
    data class WaitingForPermission(val request: CurrentLocationRequest) : CurrentLocationStatus
    data class WaitingForFix(val request: CurrentLocationRequest) : CurrentLocationStatus
    data class Unavailable(val reason: CurrentLocationUnavailableReason) : CurrentLocationStatus
}

/** Resolved From label and coordinate selected by a fresh location fix. */
internal data class CurrentLocationEndpoint(
    val label: String,
    val point: GeoPoint,
)

/** Compose-owned immutable state for one pending From-location request. */
internal data class CurrentLocationSelectionState(
    val inputRevision: Long = 0L,
    val nextRequestId: Long = 0L,
    val status: CurrentLocationStatus = CurrentLocationStatus.Idle,
) {
    fun beginRequest(): CurrentLocationSelectionState {
        val revision = inputRevision + 1L
        val request = CurrentLocationRequest(nextRequestId + 1L, revision)
        return copy(
            inputRevision = revision,
            nextRequestId = request.id,
            status = CurrentLocationStatus.WaitingForPermission(request),
        )
    }

    /** Any manual edit or picked endpoint makes earlier permission/fix work stale. */
    fun cancel(): CurrentLocationSelectionState = copy(
        inputRevision = inputRevision + 1L,
        status = CurrentLocationStatus.Idle,
    )

    fun permissionResult(requestId: Long, granted: Boolean): CurrentLocationSelectionState {
        val waiting = status as? CurrentLocationStatus.WaitingForPermission ?: return this
        if (waiting.request.id != requestId || waiting.request.inputRevision != inputRevision) return this
        return if (granted) {
            copy(status = CurrentLocationStatus.WaitingForFix(waiting.request))
        } else {
            copy(status = CurrentLocationStatus.Unavailable(CurrentLocationUnavailableReason.PERMISSION_DENIED))
        }
    }

    fun currentPoint(
        request: CurrentLocationRequest,
        fix: GpsFix?,
        nowElapsedMs: Long,
    ): GeoPoint? {
        val waiting = status as? CurrentLocationStatus.WaitingForFix ?: return null
        if (waiting.request != request || request.inputRevision != inputRevision) return null
        val currentFix = fix ?: return null
        val ageMs = nowElapsedMs - currentFix.timestampMs
        if (ageMs !in 0..LOCATION_FIX_MAX_AGE_MS ||
            !currentFix.lat.isFinite() || !currentFix.lon.isFinite() ||
            currentFix.lat !in -90.0..90.0 || currentFix.lon !in -180.0..180.0
        ) {
            return null
        }
        return GeoPoint(currentFix.lat, currentFix.lon)
    }
    fun currentEndpoint(
        request: CurrentLocationRequest,
        fix: GpsFix?,
        nowElapsedMs: Long,
    ): CurrentLocationEndpoint? = currentPoint(request, fix, nowElapsedMs)?.let { point ->
        CurrentLocationEndpoint(CURRENT_LOCATION_LABEL, point)
    }

    fun noRecentFix(requestId: Long): CurrentLocationSelectionState =
        unavailable(requestId, CurrentLocationUnavailableReason.NO_RECENT_FIX)

    fun permissionLost(requestId: Long): CurrentLocationSelectionState =
        unavailable(requestId, CurrentLocationUnavailableReason.PERMISSION_DENIED)

    fun complete(requestId: Long): CurrentLocationSelectionState {
        val waiting = status as? CurrentLocationStatus.WaitingForFix ?: return this
        if (waiting.request.id != requestId || waiting.request.inputRevision != inputRevision) return this
        return copy(status = CurrentLocationStatus.Idle)
    }

    private fun unavailable(
        requestId: Long,
        reason: CurrentLocationUnavailableReason,
    ): CurrentLocationSelectionState {
        val waiting = status as? CurrentLocationStatus.WaitingForFix ?: return this
        if (waiting.request.id != requestId || waiting.request.inputRevision != inputRevision) return this
        return copy(status = CurrentLocationStatus.Unavailable(reason))
    }
}

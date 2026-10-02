package com.organicmoto.maps

/** The rider can inspect the map briefly while a camera lock stays enabled. */
internal const val FOLLOW_GESTURE_RESUME_DELAY_MS = 1_500L

/**
 * Camera-follow pause caused by a direct map gesture. The deadline is extended
 * at the end of every gesture, so a second gesture during the grace period
 * starts a fresh full delay.
 */
internal data class CameraFollowSuspension(
    val gestureActive: Boolean = false,
    val resumeAtElapsedRealtimeMs: Long = 0L,
) {
    fun gestureStarted(): CameraFollowSuspension = copy(gestureActive = true)

    fun gestureEnded(
        nowElapsedRealtimeMs: Long,
        delayMs: Long = FOLLOW_GESTURE_RESUME_DELAY_MS,
    ): CameraFollowSuspension = copy(
        gestureActive = false,
        resumeAtElapsedRealtimeMs = nowElapsedRealtimeMs + delayMs.coerceAtLeast(0L),
    )

    fun isSuspended(nowElapsedRealtimeMs: Long): Boolean =
        gestureActive || nowElapsedRealtimeMs < resumeAtElapsedRealtimeMs

    fun remainingDelayMs(nowElapsedRealtimeMs: Long): Long =
        if (gestureActive) Long.MAX_VALUE
        else (resumeAtElapsedRealtimeMs - nowElapsedRealtimeMs).coerceAtLeast(0L)

    fun cooldownElapsed(): CameraFollowSuspension = CameraFollowSuspension()
}

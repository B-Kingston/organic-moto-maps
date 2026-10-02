package com.organicmoto.maps.media

/** Panel inactivity timeout. User interaction starts this 15-second window again. */
internal const val MediaPanelTimeoutMillis = 15_000L

internal class MediaPanelInactivityTimer(
    openedAtMillis: Long,
    private val timeoutMillis: Long = MediaPanelTimeoutMillis,
) {
    private var lastInteractionAtMillis = openedAtMillis

    fun recordInteraction(nowMillis: Long) {
        lastInteractionAtMillis = nowMillis
    }

    fun remaining(nowMillis: Long): Float =
        mediaPanelRemaining(nowMillis - lastInteractionAtMillis, timeoutMillis)

    fun isExpired(nowMillis: Long): Boolean =
        nowMillis - lastInteractionAtMillis >= timeoutMillis
}

internal fun mediaPanelRemaining(
    elapsedMillis: Long,
    timeoutMillis: Long = MediaPanelTimeoutMillis,
): Float = if (timeoutMillis <= 0L) {
    0f
} else {
    (1f - elapsedMillis.coerceAtLeast(0).toFloat() / timeoutMillis).coerceIn(0f, 1f)
}

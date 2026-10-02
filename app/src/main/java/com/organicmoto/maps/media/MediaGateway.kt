package com.organicmoto.maps.media

/**
 * Narrow seams between the pure [MediaCenterController] and the Android
 * platform. Every method returns plain data and is safe to fake on the JVM.
 */

/** Active-session discovery and transport, backed by `MediaSessionManager`. */
interface MediaSessionGateway {
    /** True when notification-listener access lets us read active sessions. */
    fun hasAccess(): Boolean

    /** Current platform snapshot. Empty when access is missing or no player. */
    fun snapshot(): List<MediaSessionInfo>

    /**
     * Sends exactly one transport command to [key]. Returns false when the
     * session is gone, the command is unsupported, or the platform rejected
     * it. Callers must not retry or duplicate.
     */
    fun sendTransport(key: String, command: MediaCommand): Boolean

    /** Adjust the session's own volume; false when it has no usable control. */
    fun adjustSessionVolume(key: String, direction: VolumeDirection): Boolean

    /**
     * Registers (or clears with null) a listener fired on session changes.
     *
     * Returns true when the listener is actually registered with the platform.
     * The platform rejects the registration while notification-listener access
     * is missing or while the listener service is reconnecting, so callers must
     * be able to observe a failed attempt and retry once access is granted.
     * Implementations replace any previous registration instead of stacking
     * duplicates; repeated calls with the same listener are always safe.
     */
    fun setOnSessionsChanged(listener: (() -> Unit)?): Boolean
}

/** Local phone-stream volume, backed by `AudioManager`. */
interface LocalVolumeGateway {
    fun state(): VolumeState
    fun adjust(direction: VolumeDirection): VolumeState

    /**
     * Registers (or clears with null) a listener fired when the stream level
     * changes outside the app (hardware volume keys), which the app otherwise
     * cannot observe. Implementations must replace any previous registration
     * instead of stacking duplicates, and must stop observing when cleared.
     */
    fun setOnVolumeChanged(listener: (() -> Unit)?)
}

/**
 * Limited fallback used only when session discovery is unavailable: a single
 * paired media-key DOWN/UP press. Unknown actions return false rather than
 * pretending they worked.
 */
interface MediaKeyGateway {
    fun press(command: MediaCommand): Boolean
}

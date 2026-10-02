package com.organicmoto.maps.media

/**
 * Immutable snapshot rendered by the media control center. Pure data so the
 * controller and the UI can be reasoned about (and tested) without Android.
 */
data class MediaCenterState(
    val access: MediaAccessState = MediaAccessState.UNKNOWN,
    val sessions: List<MediaSessionInfo> = emptyList(),
    val selectedKey: String? = null,
    val pinnedKey: String? = null,
    val localVolume: VolumeState = VolumeState.unknown(),
    val resolvedVolume: VolumeState = VolumeState.unknown(),
    val generation: Long = 0L,
    val lastCommand: MediaCommand? = null,
    val lastCommandAccepted: Boolean? = null,
) {
    val selected: MediaSessionInfo? get() = sessions.firstOrNull { it.key == selectedKey }

    val status: MediaCenterStatus
        get() = when {
            access == MediaAccessState.UNKNOWN -> MediaCenterStatus.LOADING
            access == MediaAccessState.DENIED -> MediaCenterStatus.PERMISSION_NEEDED
            sessions.isEmpty() -> MediaCenterStatus.NO_PLAYER
            else -> MediaCenterStatus.READY
        }

    /** True only while a session is selected and it reports itself playing. */
    val isPlaying: Boolean get() = selected?.isPlaying == true

    /**
     * True only when a selected session actually reported a `PlaybackState`.
     * When false the panel must offer explicit Play and Pause instead of an
     * ambiguous toggle, because a toggle would silently invert itself.
     */
    val transportStateKnown: Boolean
        get() = access == MediaAccessState.GRANTED && selected?.playbackKnown == true

    /** Media-key fallback is the only transport when discovery is unavailable. */
    val usingFallback: Boolean
        get() = access == MediaAccessState.DENIED || selected == null

    val canSelectPlayer: Boolean get() = sessions.size > 1

    val volume: VolumeState get() = resolvedVolume

    /**
     * Whether the controller may issue [command]. Transport commands follow
     * the selected session's advertised actions (or the fallback when there is
     * no session); volume commands follow the resolved target limits.
     */
    fun canIssue(command: MediaCommand): Boolean = when (command) {
        MediaCommand.VOLUME_UP -> volume.canRaise
        MediaCommand.VOLUME_DOWN -> volume.canLower
        else -> {
            val session = selected
            if (session == null) usingFallback else session.supports(command)
        }
    }
}

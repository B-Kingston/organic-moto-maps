package com.organicmoto.maps.media

/**
 * Media control center vocabulary and pure logic.
 *
 * This file (and [MediaSessionSelection], [MediaCenterState]) is deliberately
 * free of Android imports so the selection, capability, volume and state
 * derivation rules can be exercised by plain JVM tests. The Android glue in
 * [AndroidMediaSessionGateway] / [AndroidVolumeController] only translates
 * platform objects into these values.
 */

/** One transport or volume action the control center can issue. */
enum class MediaCommand {
    PREVIOUS,
    PLAY,
    PAUSE,
    NEXT,
    VOLUME_UP,
    VOLUME_DOWN,
}

/** Which way a volume adjustment goes. */
enum class VolumeDirection { UP, DOWN }

/**
 * Mirror of the `android.media.session.PlaybackState` action bit flags by
 * value, so capability mapping never needs to touch the Android framework.
 * The platform values are stable public ABI constants.
 */
object MediaActionBits {
    const val STOP = 0x1L
    const val PAUSE = 0x2L
    const val PLAY = 0x4L
    const val SKIP_TO_PREVIOUS = 0x10L
    const val SKIP_TO_NEXT = 0x20L
    const val PLAY_PAUSE = 0x200L
}

/**
 * Mirror of `android.media.session.PlaybackInfo` volume-control modes, which
 * are the `android.media.VolumeProvider` constants by value:
 * `VOLUME_CONTROL_FIXED = 0`, `VOLUME_CONTROL_RELATIVE = 1`,
 * `VOLUME_CONTROL_ABSOLUTE = 2`.
 *
 * [ABSOLUTE]/[RELATIVE] mean the session accepts remote volume commands;
 * [FIXED] means it does not expose a usable volume control. The numeric values
 * are platform ABI: the on-device suite pins them against the real
 * `VolumeProvider` constants so a mirrored value can never drift.
 */
object MediaVolumeControl {
    const val FIXED = 0
    const val RELATIVE = 1
    const val ABSOLUTE = 2
}

/** Whether the volume buttons act on the phone or on the selected session. */
enum class VolumeTarget { PHONE, SESSION }

/**
 * A platform media session, flattened to plain values. [actions] is the raw
 * `PlaybackState` action bitmask; [commands] maps it to the honest set of
 * transport commands the player advertises.
 */
data class MediaSessionInfo(
    val key: String,
    val packageName: String,
    val playerName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val isPlaying: Boolean,
    val actions: Long,
    val lastActiveTime: Long,
    val volumeControl: Int = MediaVolumeControl.FIXED,
    val volume: Int = 0,
    val maxVolume: Int = 0,
    /**
     * False when the platform returned no `PlaybackState` at all: the session
     * exists but its playing/paused state is genuinely unknown, so the control
     * center must not pretend it knows which direction a toggle would take.
     */
    val playbackKnown: Boolean = true,
) {
    val commands: Set<MediaCommand> get() = MediaCapabilities.commandsFromActions(actions)

    fun supports(command: MediaCommand): Boolean = command in commands

    /** Name shown for the player; falls back to the package when unnamed. */
    val displayName: String get() = playerName.trim().ifBlank { packageName }

    /** One-line track label; never blank so the panel always has a title. */
    val trackTitle: String get() = title?.trim().orEmpty().ifBlank { "Unknown track" }

    val trackSubtitle: String get() = artist?.trim().orEmpty().ifBlank { displayName }
}

/** Capability + volume-state derivation shared by the controller and tests. */
object MediaCapabilities {

    /**
     * Honest mapping from a `PlaybackState` action bitmask to the transport
     * commands the control center may enable. `PLAY_PAUSE` advertises both
     * directions. Volume commands are never in this set: they are handled by
     * the dedicated volume target so a player that omits them from its action
     * mask still gets working volume.
     */
    fun commandsFromActions(actions: Long): Set<MediaCommand> = buildSet {
        val playBits = MediaActionBits.PLAY or MediaActionBits.PLAY_PAUSE
        val pauseBits = MediaActionBits.PAUSE or MediaActionBits.PLAY_PAUSE
        if (actions and playBits != 0L) add(MediaCommand.PLAY)
        if (actions and pauseBits != 0L) add(MediaCommand.PAUSE)
        if (actions and MediaActionBits.SKIP_TO_PREVIOUS != 0L) add(MediaCommand.PREVIOUS)
        if (actions and MediaActionBits.SKIP_TO_NEXT != 0L) add(MediaCommand.NEXT)
    }

    /**
     * True when the session exposes a volume it will accept remote commands
     * for. ABSOLUTE sessions report a real level; RELATIVE sessions accept
     * raise/lower but cannot report a position.
     */
    fun supportsRemoteVolume(session: MediaSessionInfo): Boolean =
        session.volumeControl == MediaVolumeControl.ABSOLUTE ||
            session.volumeControl == MediaVolumeControl.RELATIVE

    /** Builds the volume state the panel should show for the selected target. */
    fun volumeStateFor(session: MediaSessionInfo?): VolumeState {
        if (session == null) return VolumeState.unknown(target = VolumeTarget.PHONE)
        return when (session.volumeControl) {
            MediaVolumeControl.ABSOLUTE -> VolumeState(
                target = VolumeTarget.SESSION,
                current = session.volume.coerceAtLeast(0),
                min = 0,
                max = session.maxVolume.coerceAtLeast(0),
                readable = session.maxVolume > 0,
                fixed = session.maxVolume <= 0,
                label = session.displayName,
            )
            MediaVolumeControl.RELATIVE -> VolumeState(
                target = VolumeTarget.SESSION,
                current = 0,
                min = 0,
                max = 0,
                readable = false,
                fixed = false,
                label = session.displayName,
            )
            else -> VolumeState(
                target = VolumeTarget.SESSION,
                current = 0,
                min = 0,
                max = 0,
                readable = false,
                fixed = true,
                label = session.displayName,
            )
        }
    }
}

/**
 * Volume readback for either the phone music stream or the selected session.
 * [readable] false means the target accepted adjustments but cannot report a
 * position (relative remote volume); [fixed] means adjustments are disabled.
 */
data class VolumeState(
    val target: VolumeTarget = VolumeTarget.PHONE,
    val current: Int = 0,
    val min: Int = 0,
    val max: Int = 0,
    val readable: Boolean = false,
    val fixed: Boolean = false,
    val label: String = "Phone",
) {
    val steps: Int get() = (max - min).coerceAtLeast(0)

    val position: Int get() = (current - min).coerceAtLeast(0)

    val fraction: Float
        get() = if (steps <= 0) 0f else (position.toFloat() / steps).coerceIn(0f, 1f)

    val canRaise: Boolean get() = !fixed && (!readable || current < max)

    val canLower: Boolean get() = !fixed && (!readable || current > min)

    companion object {
        fun unknown(target: VolumeTarget = VolumeTarget.PHONE, label: String = "Phone") =
            VolumeState(target = target, label = label)

        /** Phone-stream state from real AudioManager min/max/current reads. */
        fun phone(
            current: Int,
            min: Int,
            max: Int,
            fixedPolicy: Boolean = max <= min,
        ): VolumeState = VolumeState(
            target = VolumeTarget.PHONE,
            current = current,
            min = min,
            max = max,
            readable = true,
            fixed = fixedPolicy || max <= min,
            label = "Phone",
        )
    }
}

/** Notification-listener access state, distinct from "no player found". */
enum class MediaAccessState { UNKNOWN, GRANTED, DENIED }

/**
 * Stable session key assignment.
 *
 * A platform session is identified by its `MediaSession.Token` (binder-based
 * equality); [previousKey] is the key that token already had, or null for a new
 * session. Reusing the previous key is what keeps a package with two sessions,
 * or a snapshot whose order changed, from swapping identities. A brand-new
 * session takes the bare package name when it is free, otherwise the first free
 * `package#N` suffix. [taken] holds the keys already assigned in this snapshot.
 */
internal fun sessionKeyFor(
    packageName: String,
    previousKey: String?,
    taken: Set<String>,
): String {
    if (previousKey != null && previousKey !in taken) return previousKey
    if (packageName !in taken) return packageName
    var occurrence = 1
    while ("$packageName#$occurrence" in taken) occurrence += 1
    return "$packageName#$occurrence"
}

/** The single high-level state the panel renders. */
enum class MediaCenterStatus {
    /** Access has not been resolved yet. */
    LOADING,

    /** Access granted and at least one session is available. */
    READY,

    /** Access granted but no active player. */
    NO_PLAYER,

    /** Notification access has not been granted; only the media-key fallback works. */
    PERMISSION_NEEDED,
}

/**
 * Deterministic active-session selection.
 *
 * Rules, in order:
 * 1. A user-pinned session stays selected while it still exists (player
 *    selection for multiple sessions).
 * 2. Otherwise the playing session wins; ties break by most recent activity,
 *    then package name, then session key — fully deterministic.
 * 3. With nothing playing, a previously selected session is retained so its
 *    Resume command keeps working ("retain paused selected session").
 * 4. Otherwise the deterministic first session.
 *
 * Returns null only when there are no sessions.
 */
object MediaSessionSelection {
    fun select(
        sessions: List<MediaSessionInfo>,
        previouslySelectedKey: String? = null,
        pinnedKey: String? = null,
    ): String? {
        if (sessions.isEmpty()) return null
        if (pinnedKey != null && sessions.any { it.key == pinnedKey }) return pinnedKey
        val winner = sessions.minWith(
            compareByDescending<MediaSessionInfo> { it.isPlaying }
                .thenByDescending { it.lastActiveTime }
                .thenBy { it.packageName }
                .thenBy { it.key },
        )
        val retainable = previouslySelectedKey?.takeIf { key -> sessions.any { it.key == key } }
        if (!winner.isPlaying && retainable != null) return retainable
        return winner.key
    }
}

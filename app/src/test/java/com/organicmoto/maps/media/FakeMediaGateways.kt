package com.organicmoto.maps.media

/** Hand-written JVM fakes for the media seams (no mocking library). */

class FakeMediaSessionGateway(
    var access: Boolean = true,
    var sessions: List<MediaSessionInfo> = emptyList(),
) : MediaSessionGateway {
    val transportCalls = mutableListOf<Pair<String, MediaCommand>>()
    val volumeCalls = mutableListOf<Pair<String, VolumeDirection>>()
    var transportResult: Boolean = true
    var volumeResult: Boolean = true
    var listener: (() -> Unit)? = null
        private set

    /** Every registration attempt, including the platform refusals. */
    val listenerRegistrations = mutableListOf<(() -> Unit)?>()

    /** Forced registration refusals, decremented once per attempt. */
    var forcedRefusals: Int = 0

    val listenerRegistered: Boolean get() = listener != null

    override fun hasAccess(): Boolean = access

    override fun snapshot(): List<MediaSessionInfo> = sessions

    override fun sendTransport(key: String, command: MediaCommand): Boolean {
        transportCalls.add(key to command)
        return transportResult
    }

    override fun adjustSessionVolume(key: String, direction: VolumeDirection): Boolean {
        volumeCalls.add(key to direction)
        return volumeResult
    }

    override fun setOnSessionsChanged(listener: (() -> Unit)?): Boolean {
        listenerRegistrations.add(listener)
        if (listener == null) {
            this.listener = null
            return false
        }
        val refused = !access || forcedRefusals > 0
        if (refused) {
            if (forcedRefusals > 0) forcedRefusals -= 1
            this.listener = null
            return false
        }
        this.listener = listener
        return true
    }

    /** Fires the platform callback the way a real playback change would. */
    fun fireSessionsChanged() {
        listener?.invoke()
    }
}

class FakeLocalVolume(
    var current: Int = 5,
    private val min: Int = 0,
    private val max: Int = 15,
) : LocalVolumeGateway {
    var adjustCalls: Int = 0
        private set

    var listener: (() -> Unit)? = null
        private set

    /** Every registration change, including the initial null. */
    val listenerRegistrations = mutableListOf<(() -> Unit)?>()

    override fun state(): VolumeState = VolumeState.phone(current, min, max)

    override fun adjust(direction: VolumeDirection): VolumeState {
        adjustCalls += 1
        current = when (direction) {
            VolumeDirection.UP -> (current + 1).coerceAtMost(max)
            VolumeDirection.DOWN -> (current - 1).coerceAtLeast(min)
        }
        return state()
    }

    override fun setOnVolumeChanged(listener: (() -> Unit)?) {
        listenerRegistrations.add(listener)
        this.listener = listener
    }

    /** Fires the observer the way a hardware volume press would. */
    fun fireVolumeChanged() {
        listener?.invoke()
    }
}

class FakeMediaKeys(var result: Boolean = true) : MediaKeyGateway {
    val pressed = mutableListOf<MediaCommand>()

    override fun press(command: MediaCommand): Boolean {
        pressed.add(command)
        return result
    }
}

/** Builds a session with an explicit action mask. */
fun session(
    key: String,
    packageName: String = key,
    playerName: String = packageName,
    isPlaying: Boolean = false,
    actions: Long = ALL_TRANSPORT_ACTIONS,
    lastActiveTime: Long = 0L,
    volumeControl: Int = MediaVolumeControl.ABSOLUTE,
    volume: Int = 7,
    maxVolume: Int = 15,
    title: String? = "Track",
    artist: String? = "Artist",
): MediaSessionInfo = MediaSessionInfo(
    key = key,
    packageName = packageName,
    playerName = playerName,
    title = title,
    artist = artist,
    album = null,
    isPlaying = isPlaying,
    actions = actions,
    lastActiveTime = lastActiveTime,
    volumeControl = volumeControl,
    volume = volume,
    maxVolume = maxVolume,
)

/** PLAY | PAUSE | SKIP_TO_PREVIOUS | SKIP_TO_NEXT */
const val ALL_TRANSPORT_ACTIONS: Long = 0x4L or 0x2L or 0x10L or 0x20L

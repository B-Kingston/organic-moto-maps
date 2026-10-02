package com.organicmoto.maps.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the media control center state.
 *
 * It never issues more than one platform call per user action, never retries a
 * command, and records the honest accepted/rejected result so the UI cannot
 * claim success for a command the platform refused. All platform access goes
 * through the [MediaSessionGateway] / [LocalVolumeGateway] / [MediaKeyGateway]
 * seams, so this class runs unchanged on the JVM with fakes.
 *
 * Listener registration is failure-aware: the platform refuses an
 * active-sessions listener while notification access is missing (for example
 * when the panel was attached before the user granted it), so [attach] records
 * the failed attempt and [refresh] / [onResume] retry it. A grant or a
 * notification-listener reconnect therefore starts live session updates
 * without the user reopening the panel.
 */
class MediaCenterController(
    private val gateway: MediaSessionGateway,
    private val localVolume: LocalVolumeGateway,
    private val mediaKeys: MediaKeyGateway,
    private val onLog: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(MediaCenterState())
    val state: StateFlow<MediaCenterState> = _state.asStateFlow()

    private var generation = 0L
    private var panelOpen = false

    /** The screen wants session callbacks (set by [attach], cleared by [detach]). */
    private var attached = false

    /** Whether the platform currently accepted the callback registration. */
    private var listenerRegistered = false

    // Refresh only while the panel is visible; playback callbacks can fire at
    // any time but there is no reason to re-read the platform off-screen.
    private val sessionListener: () -> Unit = { if (panelOpen) refresh() }

    // A hardware volume press is invisible to the app: this fires from the
    // stream observer while the panel is open and re-reads the real level.
    private val localVolumeListener: () -> Unit = { if (panelOpen) pollLocalVolume() }

    fun attach() {
        attached = true
        listenerRegistered = gateway.setOnSessionsChanged(sessionListener)
        onLog("media listener attached registered=$listenerRegistered")
    }

    fun detach() {
        attached = false
        listenerRegistered = false
        // Clearing releases every per-controller callback in the gateway, so a
        // disposed panel cannot be woken by a stale session.
        gateway.setOnSessionsChanged(null)
        localVolume.setOnVolumeChanged(null)
    }

    /** Opens silently sets the refresh gate; opening reads the platform once. */
    fun setPanelOpen(open: Boolean) {
        if (panelOpen == open) return
        panelOpen = open
        // Observe the local stream only while the panel is visible.
        localVolume.setOnVolumeChanged(if (open) localVolumeListener else null)
        if (open) refresh()
    }

    /**
     * Screen resumed (or the notification-listener service reconnected):
     * re-asserts the session listener so a grant made while we were in Settings
     * — or a failed registration from before the grant — recovers, then re-reads
     * access and sessions.
     */
    fun onResume() {
        forceReRegisterListener()
        if (panelOpen) refresh()
    }

    /**
     * Notification-listener service connected/disconnected. Access and the
     * platform binding both change; re-register and refresh an open panel.
     */
    fun onListenerServiceChanged() {
        onResume()
    }

    fun refresh() {
        reRegisterListener()
        rebuild()
    }

    /** Pins a player explicitly; it stays selected while it remains present. */
    fun selectSession(key: String) {
        _state.value = _state.value.copy(pinnedKey = key)
        rebuild()
    }

    /**
     * Re-reads the local music stream while the panel is visible. Hardware
     * volume changes bypass the app entirely, so the panel polls the real
     * `AudioManager` readback (public API) instead of caching a stale level.
     * Only the local target is touched: a remote session's level comes from its
     * own `onAudioInfoChanged` callback and must not be overwritten by phone
     * readback.
     */
    fun pollLocalVolume() {
        val previous = _state.value
        val local = runCatching { localVolume.state() }.getOrDefault(previous.localVolume)
        onLog("media poll local=${local.current}/${local.max} previous=${previous.localVolume.current}/${previous.localVolume.max}")
        if (local == previous.localVolume) return
        generation += 1
        _state.value = previous.copy(
            localVolume = local,
            resolvedVolume = if (previous.resolvedVolume.target == VolumeTarget.PHONE) {
                local
            } else {
                previous.resolvedVolume
            },
            generation = generation,
        )
    }

    /**
     * Issues exactly one command. Transport follows the selected session's
     * advertised actions, or the media-key fallback when discovery is
     * unavailable. Volume follows the resolved target. Returns the honest
     * platform result.
     */
    fun dispatch(command: MediaCommand): Boolean {
        val accepted = when (command) {
            MediaCommand.VOLUME_UP -> performVolume(VolumeDirection.UP)
            MediaCommand.VOLUME_DOWN -> performVolume(VolumeDirection.DOWN)
            MediaCommand.PLAY, MediaCommand.PAUSE, MediaCommand.NEXT, MediaCommand.PREVIOUS ->
                performTransport(command)
        }
        record(command, accepted)
        return accepted
    }

    fun adjustVolume(direction: VolumeDirection): Boolean {
        val command = when (direction) {
            VolumeDirection.UP -> MediaCommand.VOLUME_UP
            VolumeDirection.DOWN -> MediaCommand.VOLUME_DOWN
        }
        val accepted = performVolume(direction)
        record(command, accepted)
        return accepted
    }

    private fun performTransport(command: MediaCommand): Boolean {
        val current = _state.value
        val session = current.selected
        return when {
            current.usingFallback -> mediaKeys.press(command)
            session != null && session.supports(command) ->
                gateway.sendTransport(session.key, command)
            else -> false
        }
    }

    private fun performVolume(direction: VolumeDirection): Boolean {
        val current = _state.value
        val volume = current.volume
        if (direction == VolumeDirection.UP && !volume.canRaise) return false
        if (direction == VolumeDirection.DOWN && !volume.canLower) return false
        val accepted = when (volume.target) {
            VolumeTarget.SESSION -> current.selected
                ?.let { gateway.adjustSessionVolume(it.key, direction) }
                ?: false
            VolumeTarget.PHONE -> {
                localVolume.adjust(direction)
                true
            }
        }
        // Read the level back so the displayed position is real, not optimistic.
        // A remote session applies the change asynchronously and reports it via
        // onAudioInfoChanged, which re-enters through the session listener.
        rebuild()
        return accepted
    }

    private fun record(command: MediaCommand, accepted: Boolean) {
        generation += 1
        _state.value = _state.value.copy(
            generation = generation,
            lastCommand = command,
            lastCommandAccepted = accepted,
        )
        onLog("media command $command accepted=$accepted")
    }

    /**
     * Retries a registration the platform refused (no access yet, listener
     * service reconnecting). Successful registrations are not repeated, so no
     * duplicate platform listeners can accumulate.
     */
    private fun reRegisterListener() {
        if (!attached || listenerRegistered) return
        listenerRegistered = gateway.setOnSessionsChanged(sessionListener)
        onLog("media listener re-registered=$listenerRegistered")
    }

    /**
     * Replaces the registration unconditionally. Used on resume and on a
     * listener-service reconnect, where the platform may have dropped the old
     * binding silently; the gateway replaces instead of stacking, so this
     * cannot create duplicate callbacks.
     */
    private fun forceReRegisterListener() {
        if (!attached) return
        listenerRegistered = gateway.setOnSessionsChanged(sessionListener)
        onLog("media listener re-asserted registered=$listenerRegistered")
    }

    private fun rebuild() {
        val access = try {
            if (gateway.hasAccess()) MediaAccessState.GRANTED else MediaAccessState.DENIED
        } catch (_: Throwable) {
            MediaAccessState.DENIED
        }
        val sessions = if (access == MediaAccessState.GRANTED) {
            runCatching { gateway.snapshot() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val previous = _state.value
        val pinned = previous.pinnedKey?.takeIf { key -> sessions.any { it.key == key } }
        val selectedKey = MediaSessionSelection.select(sessions, previous.selectedKey, pinned)
        val selected = sessions.firstOrNull { it.key == selectedKey }
        val local = runCatching { localVolume.state() }.getOrDefault(VolumeState.unknown())
        val resolved = resolveVolume(access, selected, local)
        generation += 1
        _state.value = previous.copy(
            access = access,
            sessions = sessions,
            selectedKey = selectedKey,
            pinnedKey = pinned,
            localVolume = local,
            resolvedVolume = resolved,
            generation = generation,
        )
    }

    private fun resolveVolume(
        access: MediaAccessState,
        selected: MediaSessionInfo?,
        local: VolumeState,
    ): VolumeState {
        if (access != MediaAccessState.GRANTED || selected == null) return local
        // The selected session is the honest target whenever it exposes a
        // volume control, including a fixed one (shown disabled) — falling back
        // to the phone stream would silently adjust the wrong thing.
        return when (selected.volumeControl) {
            MediaVolumeControl.ABSOLUTE, MediaVolumeControl.RELATIVE, MediaVolumeControl.FIXED ->
                MediaCapabilities.volumeStateFor(selected)
            else -> local
        }
    }
}

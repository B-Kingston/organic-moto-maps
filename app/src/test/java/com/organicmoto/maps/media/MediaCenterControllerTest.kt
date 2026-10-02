package com.organicmoto.maps.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** State-safety and single-command dispatch contract of the control center. */
class MediaCenterControllerTest {

    private fun controller(
        gateway: FakeMediaSessionGateway,
        volume: FakeLocalVolume = FakeLocalVolume(),
        keys: FakeMediaKeys = FakeMediaKeys(),
    ): MediaCenterController = MediaCenterController(gateway, volume, keys)

    private fun MediaCenterController.open() {
        attach()
        setPanelOpen(true)
    }

    @Test
    fun deniedAccessReportsPermissionNeededWithoutThrowing() {
        val gateway = FakeMediaSessionGateway(access = false)
        val control = controller(gateway)
        control.open()

        val state = control.state.value
        assertEquals(MediaAccessState.DENIED, state.access)
        assertEquals(MediaCenterStatus.PERMISSION_NEEDED, state.status)
        assertNull(state.selected)
        assertTrue(state.usingFallback)
    }

    @Test
    fun readyWithOnePlayingSessionBecomesReady() {
        val gateway = FakeMediaSessionGateway(
            sessions = listOf(session("spotify", isPlaying = true)),
        )
        val control = controller(gateway)
        control.open()

        val state = control.state.value
        assertEquals(MediaCenterStatus.READY, state.status)
        assertEquals("spotify", state.selectedKey)
        assertTrue(state.isPlaying)
        assertFalse(state.usingFallback)
    }

    @Test
    fun oneUserActionSendsExactlyOneTransportCommand() {
        val gateway = FakeMediaSessionGateway(sessions = listOf(session("p", isPlaying = true)))
        val control = controller(gateway)
        control.open()

        assertTrue(control.dispatch(MediaCommand.NEXT))
        assertEquals(listOf("p" to MediaCommand.NEXT), gateway.transportCalls)
    }

    @Test
    fun unsupportedCommandIsHonestlyFalseWithNoPlatformCall() {
        // Only PLAY advertised: PAUSE and NEXT are disabled/refused.
        val paused = session("p", isPlaying = false, actions = MediaActionBits.PLAY)
        val gateway = FakeMediaSessionGateway(sessions = listOf(paused))
        val control = controller(gateway)
        control.open()

        assertFalse(control.dispatch(MediaCommand.PAUSE))
        assertFalse(control.dispatch(MediaCommand.NEXT))
        assertTrue(gateway.transportCalls.isEmpty())
        assertEquals(false, control.state.value.lastCommandAccepted)
    }

    @Test
    fun failedTransportIsNeitherRetriedNorReportedAsSuccess() {
        val gateway = FakeMediaSessionGateway(sessions = listOf(session("p", isPlaying = true)))
        gateway.transportResult = false
        val control = controller(gateway)
        control.open()

        assertFalse(control.dispatch(MediaCommand.PLAY))
        assertEquals(1, gateway.transportCalls.size)
        assertFalse(control.state.value.lastCommandAccepted ?: true)
    }

    @Test
    fun fallbackUsesMediaKeysOncePerCommand() {
        val gateway = FakeMediaSessionGateway(access = false)
        val keys = FakeMediaKeys()
        val control = controller(gateway, keys = keys)
        control.open()

        assertTrue(control.dispatch(MediaCommand.PLAY))
        assertEquals(listOf(MediaCommand.PLAY), keys.pressed)
        assertTrue(gateway.transportCalls.isEmpty())
    }

    @Test
    fun noPlayerStillAllowsMediaKeyTransport() {
        val gateway = FakeMediaSessionGateway(access = true, sessions = emptyList())
        val keys = FakeMediaKeys()
        val control = controller(gateway, keys = keys)
        control.open()

        assertEquals(MediaCenterStatus.NO_PLAYER, control.state.value.status)
        assertTrue(control.dispatch(MediaCommand.NEXT))
        assertEquals(listOf(MediaCommand.NEXT), keys.pressed)
    }

    @Test
    fun volumeTargetsPhoneWhenNoSessionIsSelected() {
        val gateway = FakeMediaSessionGateway(access = false)
        val volume = FakeLocalVolume(current = 5)
        val control = controller(gateway, volume = volume)
        control.open()

        assertTrue(control.adjustVolume(VolumeDirection.UP))
        assertEquals(1, volume.adjustCalls)
        assertTrue(gateway.volumeCalls.isEmpty())
        assertEquals(VolumeTarget.PHONE, control.state.value.volume.target)
        assertEquals(6, control.state.value.volume.current)
    }

    @Test
    fun volumeTargetsTheSelectedSessionWhenItExposesControl() {
        val gateway = FakeMediaSessionGateway(
            sessions = listOf(
                session("p", volumeControl = MediaVolumeControl.ABSOLUTE, volume = 7, maxVolume = 15),
            ),
        )
        val volume = FakeLocalVolume(current = 2)
        val control = controller(gateway, volume = volume)
        control.open()

        assertTrue(control.adjustVolume(VolumeDirection.DOWN))
        assertEquals(listOf("p" to VolumeDirection.DOWN), gateway.volumeCalls)
        assertEquals(0, volume.adjustCalls)
        assertEquals(VolumeTarget.SESSION, control.state.value.volume.target)
    }

    @Test
    fun fixedSessionVolumeRefusesBothDirections() {
        val gateway = FakeMediaSessionGateway(
            sessions = listOf(session("cast", volumeControl = MediaVolumeControl.FIXED, maxVolume = 0)),
        )
        val volume = FakeLocalVolume()
        val control = controller(gateway, volume = volume)
        control.open()

        assertFalse(control.state.value.canIssue(MediaCommand.VOLUME_UP))
        assertFalse(control.dispatch(MediaCommand.VOLUME_UP))
        assertTrue(gateway.volumeCalls.isEmpty())
        assertEquals(0, volume.adjustCalls)
    }

    @Test
    fun selectSessionPinsAndSurvivesRefresh() {
        val gateway = FakeMediaSessionGateway(
            sessions = listOf(
                session("chosen", isPlaying = false),
                session("loud", isPlaying = true),
            ),
        )
        val control = controller(gateway)
        control.open()
        assertEquals("loud", control.state.value.selectedKey)

        control.selectSession("chosen")
        assertEquals("chosen", control.state.value.selectedKey)
        assertEquals("chosen", control.state.value.pinnedKey)

        control.refresh()
        assertEquals("chosen", control.state.value.selectedKey)
    }

    @Test
    fun accessRevokedWhileRunningFallsBackHonestly() {
        val gateway = FakeMediaSessionGateway(sessions = listOf(session("p", isPlaying = true)))
        val keys = FakeMediaKeys()
        val control = controller(gateway, keys = keys)
        control.open()
        assertFalse(control.state.value.usingFallback)

        gateway.access = false
        control.onResume()
        assertEquals(MediaAccessState.DENIED, control.state.value.access)
        assertTrue(control.state.value.usingFallback)
    }

    @Test
    fun destroyedSessionIsDroppedOnNextSnapshot() {
        val gateway = FakeMediaSessionGateway(sessions = listOf(session("p", isPlaying = true)))
        val control = controller(gateway)
        control.open()
        assertEquals("p", control.state.value.selectedKey)

        gateway.sessions = emptyList()
        control.refresh()
        assertNull(control.state.value.selectedKey)
        assertEquals(MediaCenterStatus.NO_PLAYER, control.state.value.status)
    }

    @Test
    fun nullPlaybackStateIsSafe() {
        val gateway = FakeMediaSessionGateway(
            sessions = listOf(session("p", actions = 0L, title = null, artist = null)),
        )
        val control = controller(gateway)
        control.open()

        val state = control.state.value
        assertEquals(MediaCenterStatus.READY, state.status)
        assertFalse(state.canIssue(MediaCommand.PLAY))
        assertFalse(state.canIssue(MediaCommand.PAUSE))
        assertFalse(control.dispatch(MediaCommand.PLAY))
    }

    @Test
    fun panelCallbacksRefreshOnlyWhileOpen() {
        val gateway = FakeMediaSessionGateway(sessions = listOf(session("p", isPlaying = true)))
        val control = controller(gateway)
        control.attach()

        // Closed: a platform change callback is ignored.
        gateway.sessions = emptyList()
        gateway.listener?.invoke()
        assertEquals(MediaCenterStatus.LOADING, control.state.value.status)

        gateway.sessions = listOf(session("p", isPlaying = true))
        control.setPanelOpen(true)
        assertEquals("p", control.state.value.selectedKey)
        gateway.sessions = emptyList()
        gateway.listener?.invoke()
        assertEquals(MediaCenterStatus.NO_PLAYER, control.state.value.status)
    }

    @Test
    fun generationAdvancesForEveryReadAndAction() {
        val gateway = FakeMediaSessionGateway(sessions = listOf(session("p", isPlaying = true)))
        val control = controller(gateway)
        control.open()
        val afterOpen = control.state.value.generation
        control.dispatch(MediaCommand.PLAY)
        assertTrue(control.state.value.generation > afterOpen)
    }

    @Test
    fun listenerRegistrationRetriesAfterGrantAndDeliversUpdatesWithoutReopening() {
        // The panel is attached before the user grants access: the platform
        // refuses the registration and the panel honestly says PERMISSION_NEEDED.
        val gateway = FakeMediaSessionGateway(access = false)
        val control = controller(gateway)
        control.open()
        assertEquals(MediaCenterStatus.PERMISSION_NEEDED, control.state.value.status)
        assertFalse(gateway.listenerRegistered)
        assertTrue(gateway.listenerRegistrations.isNotEmpty())

        // The user grants access in Settings and returns: resume retries the
        // registration, and the same open panel now tracks live session changes.
        gateway.access = true
        gateway.sessions = listOf(session("first", isPlaying = true))
        control.onResume()
        assertEquals(MediaCenterStatus.READY, control.state.value.status)
        assertEquals("first", control.state.value.selectedKey)
        assertTrue(gateway.listenerRegistered)

        // A new session appears: the callback updates the open panel.
        gateway.sessions = listOf(
            session("first", isPlaying = false),
            session("second", isPlaying = true, lastActiveTime = 100),
        )
        gateway.fireSessionsChanged()
        assertEquals("second", control.state.value.selectedKey)

        // The selected session goes away: removal also propagates.
        gateway.sessions = emptyList()
        gateway.fireSessionsChanged()
        assertEquals(MediaCenterStatus.NO_PLAYER, control.state.value.status)
    }

    @Test
    fun notificationListenerReconnectReassertsTheRegistration() {
        val gateway = FakeMediaSessionGateway(sessions = listOf(session("p", isPlaying = true)))
        val control = controller(gateway)
        control.open()
        assertEquals(1, gateway.listenerRegistrations.size)

        gateway.forcedRefusals = 1
        control.onListenerServiceChanged()
        // The reconnect re-asserted the listener, the platform refused once,
        // and the refresh retried immediately — no reopen needed.
        assertTrue(gateway.listenerRegistrations.size >= 2)
        assertTrue(gateway.listenerRegistered)

        gateway.sessions = listOf(session("q", isPlaying = true))
        gateway.fireSessionsChanged()
        assertEquals("q", control.state.value.selectedKey)
    }

    @Test
    fun detachClearsTheRegistrationAndNeverRegistersDuplicates() {
        val gateway = FakeMediaSessionGateway(sessions = listOf(session("p", isPlaying = true)))
        val control = controller(gateway)
        control.attach()
        assertEquals(1, gateway.listenerRegistrations.size)
        assertTrue(gateway.listenerRegistered)

        // A resume re-asserts (replacing, never stacking) and a successful
        // refresh does not register again.
        control.onResume()
        assertEquals(2, gateway.listenerRegistrations.size)
        control.refresh()
        assertEquals(2, gateway.listenerRegistrations.size)

        control.detach()
        assertFalse(gateway.listenerRegistered)
        // A detached screen is not woken by a stale platform callback.
        val statusAfterDetach = control.state.value.status
        assertEquals(MediaCenterStatus.READY, statusAfterDetach)
        gateway.sessions = emptyList()
        gateway.fireSessionsChanged()
        assertEquals(statusAfterDetach, control.state.value.status)
    }

    @Test
    fun pollLocalVolumeTracksHardwareChangesForThePhoneTarget() {
        val gateway = FakeMediaSessionGateway(access = false)
        val volume = FakeLocalVolume(current = 5)
        val control = controller(gateway, volume = volume)
        control.open()
        assertEquals(5, control.state.value.volume.current)

        // A hardware volume press changes the real stream with no app event.
        volume.current = 9
        control.pollLocalVolume()
        assertEquals(9, control.state.value.volume.current)
        assertEquals(VolumeTarget.PHONE, control.state.value.volume.target)

        // No change: no generation churn.
        val generation = control.state.value.generation
        control.pollLocalVolume()
        assertEquals(generation, control.state.value.generation)
    }

    @Test
    fun pollLocalVolumeNeverOverwritesARemoteSessionTarget() {
        val gateway = FakeMediaSessionGateway(
            sessions = listOf(
                session("remote", volumeControl = MediaVolumeControl.ABSOLUTE, volume = 7, maxVolume = 15),
            ),
        )
        val volume = FakeLocalVolume(current = 5)
        val control = controller(gateway, volume = volume)
        control.open()
        assertEquals(VolumeTarget.SESSION, control.state.value.volume.target)

        volume.current = 12
        control.pollLocalVolume()
        // The displayed target stays the session's real value.
        assertEquals(VolumeTarget.SESSION, control.state.value.volume.target)
        assertEquals(7, control.state.value.volume.current)
        // The cached local readback is still refreshed for a later handover.
        assertEquals(12, control.state.value.localVolume.current)
    }

    @Test
    fun observedRemoteVolumeUpdateRefreshesTheDisabledLimits() {
        val gateway = FakeMediaSessionGateway(
            sessions = listOf(
                session("remote", volumeControl = MediaVolumeControl.ABSOLUTE, volume = 14, maxVolume = 15),
            ),
        )
        val control = controller(gateway)
        control.open()
        assertTrue(control.state.value.canIssue(MediaCommand.VOLUME_UP))

        // The session applies the raise asynchronously and reports the
        // observed value; the panel must follow the observation, not an
        // optimistic guess.
        gateway.sessions = listOf(
            session("remote", volumeControl = MediaVolumeControl.ABSOLUTE, volume = 15, maxVolume = 15),
        )
        gateway.fireSessionsChanged()
        assertEquals(15, control.state.value.volume.current)
        assertFalse(control.state.value.canIssue(MediaCommand.VOLUME_UP))
        assertTrue(control.state.value.canIssue(MediaCommand.VOLUME_DOWN))
    }

    @Test
    fun unknownPlaybackStateIsReportedSoExplicitControlsAreUsed() {
        val known = FakeMediaSessionGateway(sessions = listOf(session("p", isPlaying = false)))
        val knownControl = controller(known)
        knownControl.open()
        assertTrue(knownControl.state.value.transportStateKnown)

        val unknownSession = session("p", isPlaying = false).copy(playbackKnown = false)
        val unknown = FakeMediaSessionGateway(sessions = listOf(unknownSession))
        val unknownControl = controller(unknown)
        unknownControl.open()
        assertFalse(unknownControl.state.value.transportStateKnown)
        // The fallback states (denied / no player) are unknown too.
        val fallback = FakeMediaSessionGateway(access = false)
        val fallbackControl = controller(fallback)
        fallbackControl.open()
        assertFalse(fallbackControl.state.value.transportStateKnown)
    }

    @Test
    fun fallbackSendsEachExplicitDirectionExactlyOnce() {
        val gateway = FakeMediaSessionGateway(access = false)
        val keys = FakeMediaKeys()
        val control = controller(gateway, keys = keys)
        control.open()

        assertTrue(control.dispatch(MediaCommand.PLAY))
        assertTrue(control.dispatch(MediaCommand.PAUSE))
        assertEquals(listOf(MediaCommand.PLAY, MediaCommand.PAUSE), keys.pressed)
    }

    @Test
    fun localVolumeObserverIsRegisteredOnlyWhileThePanelIsOpen() {
        val gateway = FakeMediaSessionGateway(access = false)
        val volume = FakeLocalVolume(current = 5)
        val control = controller(gateway, volume = volume)
        control.attach()
        // Closed: nothing observes the stream.
        assertNull(volume.listener)

        control.setPanelOpen(true)
        assertTrue(volume.listener != null)

        // A hardware change pushes a real readback of the level.
        volume.current = 9
        volume.fireVolumeChanged()
        assertEquals(9, control.state.value.volume.current)

        control.setPanelOpen(false)
        assertNull(volume.listener)
        // A late observer callback after close is ignored.
        volume.current = 12
        volume.fireVolumeChanged()
        assertEquals(9, control.state.value.volume.current)

        // Reopening observes again, with no duplicate registration left behind.
        control.setPanelOpen(true)
        assertTrue(volume.listener != null)
        control.detach()
        assertNull(volume.listener)
    }

    @Test
    fun observedLocalVolumeNeverOverwritesARemoteSessionTarget() {
        val gateway = FakeMediaSessionGateway(
            sessions = listOf(
                session("remote", volumeControl = MediaVolumeControl.ABSOLUTE, volume = 7, maxVolume = 15),
            ),
        )
        val volume = FakeLocalVolume(current = 5)
        val control = controller(gateway, volume = volume)
        control.open()
        assertEquals(VolumeTarget.SESSION, control.state.value.volume.target)

        // A local hardware press while a remote session is the target must not
        // move the displayed remote level; the cached phone level still tracks.
        volume.current = 12
        volume.fireVolumeChanged()
        assertEquals(VolumeTarget.SESSION, control.state.value.volume.target)
        assertEquals(7, control.state.value.volume.current)
        assertEquals(12, control.state.value.localVolume.current)
    }
}

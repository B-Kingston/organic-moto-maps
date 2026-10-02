package com.organicmoto.maps.media

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Remote session volume readback against a real `MediaSession` and
 * `VolumeProvider`: the panel must follow the session's observed
 * `onAudioInfoChanged` value (never an optimistic guess), including refreshing
 * the disabled limits when the session reaches its maximum.
 *
 * The provider mirrors the real platform contract: `onAdjustVolume` receives
 * the direction and the app publishes the new level with `setCurrentVolume`,
 * which is what makes the controller see an audio-info change.
 */
@RunWith(AndroidJUnit4::class)
class MediaSessionVolumeOnDeviceTest {

    private lateinit var context: Context
    private var session: MediaSession? = null
    private var provider: TestVolumeProvider? = null
    private var originalAccess = false

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        originalAccess = NotificationListenerAccess.isGranted(context)
    }

    @After
    fun tearDown() {
        runCatching { session?.release() }
        session = null
        provider = null
        NotificationListenerAccess.setGranted(context, originalAccess)
    }

    @Test
    fun remoteVolumeFollowsObservedAudioInfoChangesAndRefreshesLimits() {
        assumeTrue(
            "notification-listener access could not be enabled in this environment",
            NotificationListenerAccess.setGranted(context, true),
        )
        val remoteProvider = TestVolumeProvider(maxVolume = 15, initialVolume = 7)
        provider = remoteProvider
        session = MediaSession(context, "curveMapsVolumeSession").also {
            it.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Volume")
                    .build(),
            )
            it.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build(),
            )
            it.setPlaybackToRemote(remoteProvider)
            it.setActive(true)
        }

        val gateway = AndroidMediaSessionGateway(context)
        val controller = MediaCenterController(
            gateway = gateway,
            localVolume = AndroidVolumeController(context),
            mediaKeys = AndroidMediaKeyController(context),
        )
        controller.attach()
        controller.setPanelOpen(true)
        assertTrue(
            "session never appeared",
            waitUntil(8_000) { controller.state.value.selected != null },
        )
        waitUntil(8_000) { controller.state.value.volume.target == VolumeTarget.SESSION }
        assertEquals(VolumeTarget.SESSION, controller.state.value.volume.target)
        assertEquals(7, controller.state.value.volume.current)
        assertTrue(controller.state.value.volume.readable)

        // One up press reaches the provider; the observed readback (not an
        // optimistic +1) drives the panel.
        assertTrue(controller.adjustVolume(VolumeDirection.UP))
        assertTrue(
            "observed remote volume never arrived",
            waitUntil(5_000) { controller.state.value.volume.current == 8 },
        )

        // Raise to the ceiling: the observed value disables only the raise.
        repeat(10) { controller.adjustVolume(VolumeDirection.UP) }
        assertTrue(
            "panel never observed the maximum",
            waitUntil(8_000) { controller.state.value.volume.current == 15 },
        )
        assertFalse(controller.state.value.volume.canRaise)
        assertTrue(controller.state.value.volume.canLower)

        // And lowering works again from the observed position.
        assertTrue(controller.adjustVolume(VolumeDirection.DOWN))
        assertTrue(
            "panel never observed the lowered value",
            waitUntil(5_000) { controller.state.value.volume.current == 14 },
        )
        assertTrue(controller.state.value.volume.canRaise)

        controller.detach()
    }

    private class TestVolumeProvider(
        maxVolume: Int,
        initialVolume: Int,
    ) : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, maxVolume, initialVolume) {

        private val maximum = maxVolume
        var observed: Int = initialVolume
            private set

        override fun onAdjustVolume(direction: Int) {
            val next = when (direction) {
                AudioManager.ADJUST_RAISE -> (observed + 1).coerceAtMost(maximum)
                AudioManager.ADJUST_LOWER -> (observed - 1).coerceAtLeast(0)
                else -> observed
            }
            observed = next
            // Publishing the new level is what raises onAudioInfoChanged for
            // every MediaController watching this session.
            setCurrentVolume(next)
        }

        override fun onSetVolumeTo(volume: Int) {
            observed = volume.coerceIn(0, maximum)
            setCurrentVolume(observed)
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}

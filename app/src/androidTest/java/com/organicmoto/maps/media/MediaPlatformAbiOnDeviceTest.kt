package com.organicmoto.maps.media

import android.media.VolumeProvider
import android.media.session.PlaybackState
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the app's mirrored platform ABI against the real `android.*` constants.
 *
 * The JVM tests can only assert the mirrored numbers against each other; this
 * on-device suite proves they equal what the platform actually sends, so a
 * wrong value (for example treating `VOLUME_CONTROL_FIXED` as 2 instead of 0)
 * can never ship silently.
 */
@RunWith(AndroidJUnit4::class)
class MediaPlatformAbiOnDeviceTest {

    @Test
    fun volumeControlModesMatchVolumeProvider() {
        assertEquals(VolumeProvider.VOLUME_CONTROL_FIXED, MediaVolumeControl.FIXED)
        assertEquals(VolumeProvider.VOLUME_CONTROL_RELATIVE, MediaVolumeControl.RELATIVE)
        assertEquals(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, MediaVolumeControl.ABSOLUTE)
        // The platform's own values, spelled out: a session that reports
        // "fixed" sends 0 and an "absolute" session sends 2.
        assertEquals(0, MediaVolumeControl.FIXED)
        assertEquals(1, MediaVolumeControl.RELATIVE)
        assertEquals(2, MediaVolumeControl.ABSOLUTE)
    }

    @Test
    fun playbackActionBitsMatchPlaybackState() {
        assertEquals(PlaybackState.ACTION_STOP.toLong(), MediaActionBits.STOP)
        assertEquals(PlaybackState.ACTION_PAUSE.toLong(), MediaActionBits.PAUSE)
        assertEquals(PlaybackState.ACTION_PLAY.toLong(), MediaActionBits.PLAY)
        assertEquals(
            PlaybackState.ACTION_SKIP_TO_PREVIOUS.toLong(),
            MediaActionBits.SKIP_TO_PREVIOUS,
        )
        assertEquals(PlaybackState.ACTION_SKIP_TO_NEXT.toLong(), MediaActionBits.SKIP_TO_NEXT)
        assertEquals(PlaybackState.ACTION_PLAY_PAUSE.toLong(), MediaActionBits.PLAY_PAUSE)
    }

    @Test
    fun capabilityMappingFollowsTheRealActionMask() {
        val realMask = (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS).toLong()
        assertEquals(
            setOf(
                MediaCommand.PLAY,
                MediaCommand.PAUSE,
                MediaCommand.NEXT,
                MediaCommand.PREVIOUS,
            ),
            MediaCapabilities.commandsFromActions(realMask),
        )
        // PLAY_PAUSE alone advertises both directions, exactly as the platform
        // documents the mask.
        assertEquals(
            setOf(MediaCommand.PLAY, MediaCommand.PAUSE),
            MediaCapabilities.commandsFromActions(PlaybackState.ACTION_PLAY_PAUSE.toLong()),
        )
        assertTrue(
            MediaCapabilities.commandsFromActions(PlaybackState.ACTION_PLAY.toLong())
                .none { it == MediaCommand.PAUSE },
        )
    }

    @Test
    fun fallbackTransportUsesExplicitPlatformKeys() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY, AndroidMediaKeyController.mediaKeyCode(MediaCommand.PLAY))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PAUSE, AndroidMediaKeyController.mediaKeyCode(MediaCommand.PAUSE))
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, AndroidMediaKeyController.mediaKeyCode(MediaCommand.NEXT))
        assertEquals(
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            AndroidMediaKeyController.mediaKeyCode(MediaCommand.PREVIOUS),
        )
        assertNull(AndroidMediaKeyController.mediaKeyCode(MediaCommand.VOLUME_UP))
        assertNull(AndroidMediaKeyController.mediaKeyCode(MediaCommand.VOLUME_DOWN))
        // Explicit keys, not the ambiguous toggle: the panel offers both
        // directions in the unknown-state fallback.
        assertTrue(
            AndroidMediaKeyController.mediaKeyCode(MediaCommand.PLAY) !=
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        )
        assertTrue(
            AndroidMediaKeyController.mediaKeyCode(MediaCommand.PAUSE) !=
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        )
    }

    @Test
    fun fallbackPressIsExactlyOnePairedDownUp() {
        val events = AndroidMediaKeyController.mediaKeyEvents(KeyEvent.KEYCODE_MEDIA_PLAY)
        assertEquals(2, events.size)
        assertEquals(KeyEvent.ACTION_DOWN, events[0].action)
        assertEquals(KeyEvent.ACTION_UP, events[1].action)
        assertEquals(events[0].keyCode, events[1].keyCode)
        assertEquals(events[0].downTime, events[1].downTime)
        // A paired press must not be marked as a repeat/second press.
        assertEquals(0, events[0].repeatCount)
        assertEquals(0, events[1].repeatCount)
        assertNotNull(events[0].keyCode)
    }

    @Test
    fun everyTransportCommandHasOnePlatformMeaning() {
        // No command silently maps to another, and volume never travels as a
        // media key (it goes through the resolved volume target).
        val codes = MediaCommand.entries
            .mapNotNull { AndroidMediaKeyController.mediaKeyCode(it) }
        assertEquals(
            listOf(
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            ).sorted(),
            codes.sorted(),
        )
    }
}

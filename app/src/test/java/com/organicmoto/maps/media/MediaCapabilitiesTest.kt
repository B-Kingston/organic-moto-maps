package com.organicmoto.maps.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capability mapping and volume-state maths that must stay honest. */
class MediaCapabilitiesTest {

    @Test
    fun emptyActionMaskAdvertisesNothing() {
        assertTrue(MediaCapabilities.commandsFromActions(0L).isEmpty())
    }

    @Test
    fun playPauseBitAdvertisesBothDirections() {
        val commands = MediaCapabilities.commandsFromActions(MediaActionBits.PLAY_PAUSE)
        assertTrue(commands.contains(MediaCommand.PLAY))
        assertTrue(commands.contains(MediaCommand.PAUSE))
        assertFalse(commands.contains(MediaCommand.NEXT))
        assertFalse(commands.contains(MediaCommand.PREVIOUS))
    }

    @Test
    fun skipBitsMapOnlyToTheirDirection() {
        val commands = MediaCapabilities.commandsFromActions(
            MediaActionBits.PLAY or MediaActionBits.SKIP_TO_NEXT,
        )
        assertEquals(setOf(MediaCommand.PLAY, MediaCommand.NEXT), commands)
    }

    @Test
    fun playOnlyNeverAdvertisesPause() {
        val commands = MediaCapabilities.commandsFromActions(MediaActionBits.PLAY)
        assertEquals(setOf(MediaCommand.PLAY), commands)
    }

    @Test
    fun volumeCommandsAreNeverPlaybackCapabilities() {
        val commands = MediaCapabilities.commandsFromActions(0xFFFF_FFFFL)
        assertFalse(commands.contains(MediaCommand.VOLUME_UP))
        assertFalse(commands.contains(MediaCommand.VOLUME_DOWN))
    }

    @Test
    fun absoluteVolumeMapsToReadableSessionTarget() {
        val state = MediaCapabilities.volumeStateFor(
            session("p", volumeControl = MediaVolumeControl.ABSOLUTE, volume = 7, maxVolume = 15),
        )
        assertEquals(VolumeTarget.SESSION, state.target)
        assertTrue(state.readable)
        assertFalse(state.fixed)
        assertEquals(7, state.current)
        assertEquals(15, state.max)
        assertEquals(7f / 15f, state.fraction, 1e-6f)
        assertTrue(state.canRaise)
        assertTrue(state.canLower)
    }

    @Test
    fun absoluteVolumeAtTheTopCannotRaise() {
        val state = MediaCapabilities.volumeStateFor(
            session("p", volume = 15, maxVolume = 15),
        )
        assertFalse(state.canRaise)
        assertTrue(state.canLower)
    }

    @Test
    fun relativeVolumeIsAdjustableButUnreadable() {
        val state = MediaCapabilities.volumeStateFor(
            session("p", volumeControl = MediaVolumeControl.RELATIVE),
        )
        assertEquals(VolumeTarget.SESSION, state.target)
        assertFalse(state.readable)
        assertFalse(state.fixed)
        assertTrue(state.canRaise)
        assertTrue(state.canLower)
    }

    @Test
    fun fixedVolumeDisablesBothDirections() {
        val state = MediaCapabilities.volumeStateFor(
            session("p", volumeControl = MediaVolumeControl.FIXED, maxVolume = 0),
        )
        assertTrue(state.fixed)
        assertFalse(state.canRaise)
        assertFalse(state.canLower)
    }

    @Test
    fun nullSessionFallsBackToPhoneTarget() {
        val state = MediaCapabilities.volumeStateFor(null)
        assertEquals(VolumeTarget.PHONE, state.target)
        assertFalse(state.readable)
    }

    @Test
    fun phoneVolumeReportsTruthfulLimitsAndFixedPolicy() {
        val normal = VolumeState.phone(current = 5, min = 0, max = 15)
        assertTrue(normal.readable)
        assertFalse(normal.fixed)
        assertTrue(normal.canRaise)
        assertTrue(normal.canLower)

        val atFloor = VolumeState.phone(current = 0, min = 0, max = 15)
        assertFalse(atFloor.canLower)
        assertTrue(atFloor.canRaise)

        val fixed = VolumeState.phone(current = 0, min = 0, max = 0)
        assertTrue(fixed.fixed)
        assertFalse(fixed.canRaise)
        assertFalse(fixed.canLower)
    }

    @Test
    fun trackFallbacksAreNeverBlank() {
        val bare = session("p", playerName = "", title = null, artist = null)
        assertEquals("p", bare.displayName)
        assertEquals("Unknown track", bare.trackTitle)
        assertEquals("p", bare.trackSubtitle)
    }

    @Test
    fun volumeControlConstantsMirrorThePlatformAbi() {
        // Mirrors android.media.VolumeProvider by value. The on-device suite
        // pins these against the real platform constants; this JVM check keeps
        // the mirror honest without a device.
        assertEquals(0, MediaVolumeControl.FIXED)
        assertEquals(1, MediaVolumeControl.RELATIVE)
        assertEquals(2, MediaVolumeControl.ABSOLUTE)
    }

    @Test
    fun playbackActionBitsMirrorThePlatformAbi() {
        // Mirrors android.media.session.PlaybackState action masks by value.
        assertEquals(0x1L, MediaActionBits.STOP)
        assertEquals(0x2L, MediaActionBits.PAUSE)
        assertEquals(0x4L, MediaActionBits.PLAY)
        assertEquals(0x10L, MediaActionBits.SKIP_TO_PREVIOUS)
        assertEquals(0x20L, MediaActionBits.SKIP_TO_NEXT)
        assertEquals(0x200L, MediaActionBits.PLAY_PAUSE)
    }

    @Test
    fun fixedControlValueIsTheZeroThePlatformSends() {
        // A platform session that reports "no usable control" reports 0, so
        // mapping it to anything but FIXED would treat it as adjustable.
        val state = MediaCapabilities.volumeStateFor(
            session("cast", volumeControl = MediaVolumeControl.FIXED, volume = 0, maxVolume = 0),
        )
        assertEquals(VolumeTarget.SESSION, state.target)
        assertTrue(state.fixed)
        assertFalse(state.canRaise)
        assertFalse(state.canLower)
    }

    @Test
    fun relativeControlValueIsAdjustableAndUnreadable() {
        // 1 is RELATIVE on the platform; it must never be treated as FIXED.
        val state = MediaCapabilities.volumeStateFor(
            session("cast", volumeControl = MediaVolumeControl.RELATIVE, volume = 0, maxVolume = 0),
        )
        assertEquals(VolumeTarget.SESSION, state.target)
        assertFalse(state.fixed)
        assertTrue(state.canRaise)
        assertTrue(state.canLower)
    }
}

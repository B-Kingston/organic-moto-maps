package com.organicmoto.maps.media

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
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
 * Real `AudioManager` volume behaviour: truthful min/max readback, monotonic
 * adjustment within limits, and the fixed-volume guard. Volume must work with
 * no notification access, so this test grants none.
 */
@RunWith(AndroidJUnit4::class)
class MediaVolumeOnDeviceTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private var originalVolume = 0

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    @After
    fun tearDown() {
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
        }
    }

    @Test
    fun stateReportsRealLimitsAndPosition() {
        val controller = AndroidVolumeController(context)
        val state = controller.state()
        assertEquals(VolumeTarget.PHONE, state.target)
        assertTrue(state.readable)
        assertEquals(audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), state.max)
        assertEquals(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), state.current)
        assertTrue(state.current in state.min..state.max)
        assertEquals(
            "the fixed flag must mirror AudioManager.isVolumeFixed",
            audioManager.isVolumeFixed ||
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) <=
                audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC),
            state.fixed,
        )
    }

    @Test
    fun readbackSeesChangesMadeOutsideTheApp() {
        val controller = AndroidVolumeController(context)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        assumeTrue("the music stream has no adjustable range on this device", max > min)
        // An external change (hardware volume) must appear on the next read:
        // the controller never caches a level.
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, min, 0)
        assertEquals(min, controller.state().current)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        assertEquals(max, controller.state().current)
        assertFalse(controller.state().canRaise)
    }

    @Test
    fun adjustmentStaysWithinLimitsAndMovesMonotonically() {
        val controller = AndroidVolumeController(context)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }
        val midpoint = (min + max) / 2
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, midpoint, 0)

        val raised = controller.adjust(VolumeDirection.UP)
        assertTrue(raised.current >= midpoint)
        val lowered = controller.adjust(VolumeDirection.DOWN)
        assertTrue(lowered.current <= raised.current)
        assertTrue(lowered.current in min..max)

        // Floor: repeated lowers never go below the reported minimum.
        repeat(max + 2) { controller.adjust(VolumeDirection.DOWN) }
        assertEquals(min, controller.adjust(VolumeDirection.DOWN).current)
    }

    @Test
    fun controllerPollObservesExternalVolumeChanges() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }
        assumeTrue("the music stream has no adjustable range on this device", max > min)
        val controller = MediaCenterController(
            gateway = AndroidMediaSessionGateway(context),
            localVolume = AndroidVolumeController(context),
            mediaKeys = AndroidMediaKeyController(context),
        )
        controller.setPanelOpen(true)

        // With no notification access the resolved target is the real phone
        // stream.
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, min, 0)
        controller.refresh()
        assertEquals(VolumeTarget.PHONE, controller.state.value.volume.target)
        assertEquals(min, controller.state.value.volume.current)

        // An external change is observed by the poll, and the disabled limit
        // follows the real readback.
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
        controller.pollLocalVolume()
        assertEquals(max, controller.state.value.volume.current)
        assertFalse(controller.state.value.canIssue(MediaCommand.VOLUME_UP))

        controller.detach()
    }

    @Test
    fun permissionHelpersAgreeWithTheGateway() {
        val gateway = AndroidMediaSessionGateway(context)
        assertEquals(gateway.hasAccess(), MediaPermission.isGranted(context))
        val component = MediaPermission.component(context)
        assertEquals(context.packageName, component.packageName)
        assertTrue(component.className.contains("MediaNotificationListenerService"))
        assertEquals(
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            MediaPermission.settingsIntent().action,
        )
    }
}

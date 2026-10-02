package com.organicmoto.maps.media

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof of the listener-recovery contract: a controller that
 * attached while notification access was denied must recover the platform
 * callback after the user grants access, and must then see new sessions and
 * session removal through that callback without the panel being reopened.
 *
 * The test restores the exact notification-access state it started with, and
 * never rewrites other apps' listeners (see [NotificationListenerAccess]).
 */
@RunWith(AndroidJUnit4::class)
class MediaAccessRecoveryOnDeviceTest {

    private lateinit var context: Context
    private var session: MediaSession? = null
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
        NotificationListenerAccess.setGranted(context, originalAccess)
    }

    @Test
    fun panelRecoversRegistrationAfterGrantAndTracksSessionChanges() {
        assumeTrue(
            "notification-listener access could not be revoked in this environment",
            NotificationListenerAccess.setGranted(context, false),
        )
        val gateway = AndroidMediaSessionGateway(context)
        val controller = MediaCenterController(
            gateway = gateway,
            localVolume = AndroidVolumeController(context),
            mediaKeys = AndroidMediaKeyController(context),
        )
        controller.attach()
        controller.setPanelOpen(true)
        assertEquals(
            "attachment before the grant must stay honest",
            MediaAccessState.DENIED,
            controller.state.value.access,
        )

        // The player appears while access is still denied.
        session = MediaSession(context, "curveMapsRecoverySession").also {
            it.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "Recovered")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
                    .build(),
            )
            publish(it, PlaybackState.STATE_PLAYING)
            it.setActive(true)
        }

        assumeTrue(
            "notification-listener access could not be granted in this environment",
            NotificationListenerAccess.setGranted(context, true),
        )
        // The user comes back from Settings: each resume re-asserts the
        // registration until the platform has rebound the listener service.
        val discovered = waitUntil(20_000) {
            controller.onResume()
            controller.state.value.access == MediaAccessState.GRANTED &&
                controller.state.value.status == MediaCenterStatus.READY
        }
        assertTrue("granted panel never discovered the session", discovered)
        assertEquals(context.packageName, controller.state.value.selectedKey)

        // A live playback change must reach the open panel through the
        // recovered callback (not just a periodic snapshot read).
        session?.let { publish(it, PlaybackState.STATE_PAUSED) }
        assertTrue(
            "playback callback did not update the open panel",
            waitUntil(5_000) { controller.state.value.selected?.isPlaying == false },
        )

        // Removing the session must also propagate.
        session?.release()
        session = null
        assertTrue(
            "session removal did not update the open panel",
            waitUntil(5_000) { controller.state.value.status == MediaCenterStatus.NO_PLAYER },
        )

        // Detach releases the registration so nothing is left listening.
        controller.detach()
    }

    private fun publish(session: MediaSession, state: Int) {
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                )
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
}

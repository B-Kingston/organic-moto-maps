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
 * Deterministic on-device proof that [AndroidMediaSessionGateway] discovers a
 * native `MediaSession` and forwards transport commands to it.
 *
 * The test creates its own session in the instrumentation process, enables the
 * notification listener with the shell (nothing privileged is declared in the
 * app manifest), and asserts play/pause actually change the session state.
 */
@RunWith(AndroidJUnit4::class)
class MediaSessionTransportOnDeviceTest {

    private lateinit var context: Context
    private lateinit var session: MediaSession
    private var lastCommand: String? = null
    private var originalAccess = false

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        originalAccess = MediaPermission.isGranted(context)
        session = MediaSession(context, "curveMapsTestSession")
    }

    @After
    fun tearDown() {
        runCatching { session.release() }
        setListenerAccess(originalAccess)
    }

    @Test
    fun gatewayDiscoversTheSessionAndDrivesTransport() {
        assumeTrue(
            "notification-listener access could not be enabled in this environment",
            setListenerAccess(true),
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Song")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Artist")
                .build(),
        )
        // MediaSession.setCallback() derives a Handler from the calling
        // thread's Looper, which this instrumentation thread does not have;
        // bind the callback to the main looper explicitly.
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() {
                    lastCommand = "play"
                    publish(PlaybackState.STATE_PLAYING)
                }

                override fun onPause() {
                    lastCommand = "pause"
                    publish(PlaybackState.STATE_PAUSED)
                }

                override fun onSkipToNext() {
                    lastCommand = "next"
                }

                override fun onSkipToPrevious() {
                    lastCommand = "previous"
                }
            },
            android.os.Handler(android.os.Looper.getMainLooper()),
        )
        publish(PlaybackState.STATE_PLAYING)
        session.setActive(true)

        val gateway = AndroidMediaSessionGateway(context)
        assertTrue(gateway.hasAccess())
        val info = waitForSession(gateway)
        assertEquals(context.packageName, info.key)
        assertTrue("session must report playing", info.isPlaying)
        assertTrue("session must advertise PAUSE", info.supports(MediaCommand.PAUSE))
        assertTrue("session must advertise NEXT", info.supports(MediaCommand.NEXT))
        assertEquals("Song", info.title)

        assertTrue(gateway.sendTransport(info.key, MediaCommand.PAUSE))
        assertTrue(
            "pause must reach the native session",
            waitUntil(5_000) { lastCommand == "pause" },
        )

        assertTrue(gateway.sendTransport(info.key, MediaCommand.PLAY))
        assertTrue(
            "play must reach the native session",
            waitUntil(5_000) { lastCommand == "play" },
        )

        // Next is advertised; one click sends exactly one command.
        lastCommand = null
        assertTrue(gateway.sendTransport(info.key, MediaCommand.NEXT))
        assertTrue(waitUntil(5_000) { lastCommand == "next" })
    }

    @Test
    fun missingSessionIsHonestlyFalse() {
        val gateway = AndroidMediaSessionGateway(context)
        assertTrue(gateway.sendTransport("com.example.absent", MediaCommand.PLAY) == false)
        assertTrue(gateway.adjustSessionVolume("com.example.absent", VolumeDirection.UP) == false)
    }

    private fun publish(state: Int) {
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

    private fun waitForSession(gateway: AndroidMediaSessionGateway): MediaSessionInfo {
        var found: MediaSessionInfo? = null
        waitUntil(8_000) {
            found = gateway.snapshot().firstOrNull { it.key == context.packageName }
            found != null
        }
        return found ?: throw AssertionError("gateway never discovered the test session")
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

    private fun setListenerAccess(granted: Boolean): Boolean =
        NotificationListenerAccess.setGranted(context, granted)
}

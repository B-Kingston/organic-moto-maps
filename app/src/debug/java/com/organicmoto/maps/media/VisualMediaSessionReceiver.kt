package com.organicmoto.maps.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * Debug-build-only synthetic media session for the ADB visual runner.
 *
 * It lives in the `debug` source set, so it is never part of a release APK. It
 * exists only so `tools/test/visual.py media-controls` can screenshot the
 * playing/paused/no-player states on an emulator that has no real media app.
 * Production builds create no synthetic session.
 */
class VisualMediaSessionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        val state = intent.getStringExtra("state") ?: return
        currentTitle = intent.getStringExtra("title") ?: currentTitle
        currentArtist = intent.getStringExtra("artist") ?: currentArtist
        when (state) {
            "playing" -> ensureSession(context).also { publish(it, PlaybackState.STATE_PLAYING) }
            "paused" -> ensureSession(context).also { publish(it, PlaybackState.STATE_PAUSED) }
            "stop" -> stopSession()
        }
    }

    private fun ensureSession(context: Context): MediaSession {
        session?.let { return it }
        val created = MediaSession(context.applicationContext, "curveMapsDebugMedia")
        created.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                publish(created, PlaybackState.STATE_PLAYING)
            }

            override fun onPause() {
                publish(created, PlaybackState.STATE_PAUSED)
            }
        })
        session = created
        return created
    }

    private fun stopSession() {
        session?.let { active ->
            active.isActive = false
            active.release()
        }
        session = null
    }

    companion object {
        const val ACTION = "com.organicmoto.maps.DEBUG_VISUAL_MEDIA_SESSION"

        private var session: MediaSession? = null
        private var currentTitle: String = "Debug Track"
        private var currentArtist: String = "Debug Artist"

        private fun publish(session: MediaSession, state: Int) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, "Debug")
                    .build(),
            )
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS,
                    )
                    .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                    .build(),
            )
            session.isActive = true
        }
    }
}

package com.organicmoto.maps.media

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * Limited fallback transport: exactly one paired media-key DOWN/UP press
 * through `AudioManager.dispatchMediaKeyEvent`. Used only when session
 * discovery is unavailable. It cannot know which player (if any) will react,
 * so it returns the honest platform result and never retries or duplicates the
 * press.
 *
 * PLAY and PAUSE map to the explicit `KEYCODE_MEDIA_PLAY` / `KEYCODE_MEDIA_PAUSE`
 * keys the platform defines, not the ambiguous `KEYCODE_MEDIA_PLAY_PAUSE`
 * toggle: the panel offers both directions in the unknown-state fallback, so a
 * press must mean exactly what it says.
 */
class AndroidMediaKeyController(context: Context) : MediaKeyGateway {

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    override fun press(command: MediaCommand): Boolean {
        val keyCode = mediaKeyCode(command) ?: return false
        val events = mediaKeyEvents(keyCode)
        return try {
            events.forEach { audioManager.dispatchMediaKeyEvent(it) }
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** Explicit platform key for each transport command; null when none exists. */
        internal fun mediaKeyCode(command: MediaCommand): Int? = when (command) {
            MediaCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaCommand.VOLUME_UP, MediaCommand.VOLUME_DOWN -> null
        }

        /**
         * One paired DOWN/UP press: both events share one down-time so the
         * platform sees a single key gesture, never a repeat or a second press.
         */
        internal fun mediaKeyEvents(keyCode: Int): List<KeyEvent> {
            val downTime = android.os.SystemClock.uptimeMillis()
            return listOf(
                KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0),
                KeyEvent(downTime, downTime, KeyEvent.ACTION_UP, keyCode, 0),
            )
        }
    }
}

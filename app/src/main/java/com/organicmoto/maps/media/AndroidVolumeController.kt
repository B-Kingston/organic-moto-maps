package com.organicmoto.maps.media

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Local phone volume via `AudioManager` `STREAM_MUSIC`, with real min/max
 * readback and fixed-volume handling. This needs no notification access, so
 * volume keeps working even when session discovery is unavailable.
 *
 * The readback is real, never cached. Hardware volume presses are invisible to
 * the app, so while the panel is visible the control center both observes the
 * stream's system setting (immediate, pushes a refresh) and polls at a slow
 * interval as a backstop; either path reads the real `AudioManager` level.
 */
class AndroidVolumeController(context: Context) : LocalVolumeGateway {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var listener: (() -> Unit)? = null

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            listener?.invoke()
        }
    }

    override fun state(): VolumeState {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }
        // isVolumeFixed() is the public platform signal for "the hardware/policy
        // owns the volume"; both it and a collapsed min/max range disable the
        // controls honestly instead of pretending an adjustment worked.
        val fixed = audioManager.isVolumeFixed || max <= min
        return VolumeState.phone(current = current, min = min, max = max, fixedPolicy = fixed)
    }

    override fun adjust(direction: VolumeDirection): VolumeState {
        val before = state()
        if (before.fixed) return before
        val androidDirection = when (direction) {
            VolumeDirection.UP -> AudioManager.ADJUST_RAISE
            VolumeDirection.DOWN -> AudioManager.ADJUST_LOWER
        }
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, androidDirection, 0)
        } catch (_: Exception) {
            // Fixed policy or restricted stream: return the honest current state.
        }
        return state()
    }

    override fun setOnVolumeChanged(listener: (() -> Unit)?) {
        val previous = this.listener
        if (previous === listener) return
        if (previous != null) {
            runCatching { appContext.contentResolver.unregisterContentObserver(observer) }
        }
        this.listener = listener
        if (listener != null) {
            // The platform stores each stream's level in Settings.System; the
            // music key is stable public data ("volume_music", the value of the
            // hidden VOLUME_SETTINGS[STREAM_MUSIC] constant) and is notified on
            // every volume change, including hardware keys.
            runCatching {
                appContext.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(VOLUME_SETTING_MUSIC),
                    false,
                    observer,
                )
                appContext.contentResolver.registerContentObserver(
                    Settings.System.getUriFor(VOLUME_SETTING_MUSIC_SPEAKER),
                    false,
                    observer,
                )
            }
        }
    }

    private companion object {
        const val VOLUME_SETTING_MUSIC = "volume_music"
        const val VOLUME_SETTING_MUSIC_SPEAKER = "volume_music_speaker"
    }
}

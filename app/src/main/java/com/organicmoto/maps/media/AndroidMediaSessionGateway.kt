package com.organicmoto.maps.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * `MediaSessionManager` + `MediaController` implementation of
 * [MediaSessionGateway].
 *
 * Access requires the user to enable this app's notification listener; there
 * is no privileged `MEDIA_CONTENT_CONTROL` and no Spotify SDK. We only read the
 * session list and standard `PlaybackState`/`MediaMetadata`/`PlaybackInfo`
 * fields — never notification content. Every platform call is guarded so a
 * revoked grant or a tearing-down session degrades to an honest false instead
 * of crashing.
 *
 * Session identity is the `MediaSession.Token`, whose `equals`/`hashCode` are
 * binder-based on every supported API level: a package that exposes two
 * sessions, or reorders them between snapshots, keeps each session's key
 * instead of swapping them by snapshot position. The package name remains the
 * first key for a single session so logs and tests stay readable.
 */
class AndroidMediaSessionGateway(context: Context) : MediaSessionGateway {

    private val appContext = context.applicationContext
    private val manager =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val handler = Handler(Looper.getMainLooper())
    private val listenerComponent =
        ComponentName(appContext, MediaNotificationListenerService::class.java)

    private val controllersByKey = LinkedHashMap<String, MediaController>()
    private val callbacksByKey = HashMap<String, MediaController.Callback>()

    /** Stable keys per live token; entries are pruned when the token goes away. */
    private val keysByToken = HashMap<MediaSession.Token, String>()
    private var platformListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    @Volatile private var externalListener: (() -> Unit)? = null

    override fun hasAccess(): Boolean = MediaPermission.isGranted(appContext)

    override fun snapshot(): List<MediaSessionInfo> {
        val active: List<MediaController> = try {
            manager.getActiveSessions(listenerComponent)
        } catch (_: SecurityException) {
            clearControllers()
            return emptyList()
        } catch (_: RuntimeException) {
            clearControllers()
            return emptyList()
        }

        val infos = ArrayList<MediaSessionInfo>(active.size)
        val liveKeys = HashSet<String>()
        val liveTokens = HashSet<MediaSession.Token>()
        for (controller in active) {
            val packageName = controller.packageName ?: continue
            val token = controller.sessionToken ?: continue
            if (!liveTokens.add(token)) continue
            val key = sessionKeyFor(packageName, keysByToken[token], liveKeys)
            keysByToken[token] = key
            liveKeys.add(key)
            register(key, controller)
            infos.add(toInfo(key, controller))
        }
        keysByToken.keys.retainAll(liveTokens)
        pruneControllers(liveKeys)
        return infos
    }

    override fun sendTransport(key: String, command: MediaCommand): Boolean {
        val controller = controllersByKey[key] ?: return false
        return try {
            when (command) {
                MediaCommand.PLAY -> {
                    controller.transportControls.play(); true
                }
                MediaCommand.PAUSE -> {
                    controller.transportControls.pause(); true
                }
                MediaCommand.NEXT -> {
                    controller.transportControls.skipToNext(); true
                }
                MediaCommand.PREVIOUS -> {
                    controller.transportControls.skipToPrevious(); true
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun adjustSessionVolume(key: String, direction: VolumeDirection): Boolean {
        val controller = controllersByKey[key] ?: return false
        val info = controller.playbackInfo ?: return false
        if (info.volumeControl == MediaVolumeControl.FIXED) return false
        return try {
            val androidDirection = when (direction) {
                VolumeDirection.UP -> AudioManager.ADJUST_RAISE
                VolumeDirection.DOWN -> AudioManager.ADJUST_LOWER
            }
            controller.adjustVolume(androidDirection, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun setOnSessionsChanged(listener: (() -> Unit)?): Boolean {
        platformListener?.let { existing ->
            try {
                manager.removeOnActiveSessionsChangedListener(existing)
            } catch (_: Exception) {
                // Already detached / process tearing down.
            }
        }
        platformListener = null
        externalListener = listener
        if (listener == null) {
            // Detached (screen gone): release every per-controller callback so
            // no stale session callback can fire after the panel is disposed.
            clearControllers()
            return false
        }
        val platform = MediaSessionManager.OnActiveSessionsChangedListener {
            externalListener?.invoke()
        }
        return try {
            manager.addOnActiveSessionsChangedListener(platform, listenerComponent, handler)
            platformListener = platform
            true
        } catch (_: SecurityException) {
            // Access missing or just revoked: the caller retries after a grant.
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun register(key: String, controller: MediaController) {
        val existing = controllersByKey[key]
        if (existing != null && existing.sessionToken == controller.sessionToken) {
            // Same live session: the callback registration is still valid. No
            // re-registration churn and no duplicate callbacks.
            return
        }
        callbacksByKey.remove(key)?.let { callback ->
            runCatching { existing?.unregisterCallback(callback) }
        }
        val callback = sessionCallback(key, controller)
        controller.registerCallback(callback, handler)
        controllersByKey[key] = controller
        callbacksByKey[key] = callback
    }

    /**
     * Callbacks are guarded against staleness: a queued callback from a
     * controller that has since been replaced for the same key is ignored, so
     * an old session cannot refresh the panel with its own state.
     */
    private fun sessionCallback(key: String, controller: MediaController): MediaController.Callback =
        object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                notifyIfLive()
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                notifyIfLive()
            }

            override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) {
                // Remote volume is applied asynchronously; this is the observed
                // value the panel must show instead of an optimistic guess.
                notifyIfLive()
            }

            override fun onSessionDestroyed() {
                notifyIfLive()
            }

            private fun notifyIfLive() {
                if (controllersByKey[key]?.sessionToken == controller.sessionToken) {
                    externalListener?.invoke()
                }
            }
        }

    private fun pruneControllers(liveKeys: Set<String>) {
        val stale = controllersByKey.keys - liveKeys
        for (key in stale) {
            val controller = controllersByKey.remove(key)
            callbacksByKey.remove(key)?.let { callback ->
                runCatching { controller?.unregisterCallback(callback) }
            }
        }
    }

    private fun clearControllers() {
        for (key in controllersByKey.keys.toList()) {
            val controller = controllersByKey.remove(key)
            callbacksByKey.remove(key)?.let { callback ->
                runCatching { controller?.unregisterCallback(callback) }
            }
        }
        keysByToken.clear()
    }

    private fun toInfo(key: String, controller: MediaController): MediaSessionInfo {
        val playback = controller.playbackState
        val metadata = controller.metadata
        val info = controller.playbackInfo
        return MediaSessionInfo(
            key = key,
            packageName = controller.packageName ?: key,
            playerName = appLabel(controller.packageName) ?: (controller.packageName ?: key),
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            actions = playback?.actions ?: 0L,
            lastActiveTime = playback?.lastPositionUpdateTime ?: 0L,
            volumeControl = info?.volumeControl ?: MediaVolumeControl.FIXED,
            volume = info?.currentVolume ?: 0,
            maxVolume = info?.maxVolume ?: 0,
            playbackKnown = playback != null,
        )
    }

    private fun appLabel(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return try {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            null
        }
    }
}

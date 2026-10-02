package com.organicmoto.maps.media

import android.service.notification.NotificationListenerService

/**
 * Minimal notification listener that exists only so the app may query
 * `MediaSessionManager.getActiveSessions` for the media control center. It
 * deliberately overrides no notification callback: notification content is
 * never read, stored, or forwarded.
 *
 * The platform grant is controlled by the user through the standard
 * notification-access settings screen.
 */
class MediaNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        MediaListenerBridge.notifyChanged()
    }

    override fun onListenerDisconnected() {
        MediaListenerBridge.notifyChanged()
    }
}

/** Fan-out from the service lifecycle to any open control center. */
object MediaListenerBridge {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    internal fun notifyChanged() {
        listeners.forEach { it() }
    }
}

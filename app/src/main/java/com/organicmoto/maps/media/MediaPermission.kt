package com.organicmoto.maps.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Notification-listener access helpers. The app queries and opens the standard
 * system screen; it never requests privileged access or an accessibility
 * service. Access is re-checked on resume, so a grant or revocation made while
 * the app is backgrounded is picked up.
 */
object MediaPermission {

    private const val ENABLED_LISTENERS = "enabled_notification_listeners"

    fun component(context: Context): ComponentName =
        ComponentName(context.packageName, MediaNotificationListenerService::class.java.name)

    fun isGranted(context: Context): Boolean {
        val target = component(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
                ?: return false
            return manager.isNotificationListenerAccessGranted(target)
        }
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            ENABLED_LISTENERS,
        ) ?: return false
        return enabled.split(':').any { it == target.flattenToString() }
    }

    /** The system notification-access screen; the user grants the toggle there. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

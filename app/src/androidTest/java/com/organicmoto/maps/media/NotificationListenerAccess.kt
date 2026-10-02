package com.organicmoto.maps.media

import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Grants or revokes **this app's** notification-listener access for on-device
 * tests without disturbing any other listener the device already has enabled.
 *
 * The previous implementations wrote `settings put secure
 * enabled_notification_listeners <component>` (replacing the whole list) and
 * `settings delete secure enabled_notification_listeners` (wiping every
 * listener), which could break unrelated apps — and the developer's own
 * device — for the rest of the session. This helper edits only our component
 * in the list and restores the exact original value.
 */
object NotificationListenerAccess {

    private const val ENABLED_LISTENERS = "enabled_notification_listeners"

    /** True when the platform reports our listener as granted. */
    fun isGranted(context: Context): Boolean = MediaPermission.isGranted(context)

    /**
     * Adds or removes our component and waits for the platform to agree.
     * Returns whether the requested state was reached.
     */
    fun setGranted(context: Context, granted: Boolean): Boolean {
        val component = MediaPermission.component(context).flattenToString()
        // Preferred path: the notification service command rebinds the listener
        // properly. It is a no-op (or missing) on some builds, so the secure
        // setting edit below is the fallback.
        val command = if (granted) "allow_listener" else "disallow_listener"
        runCatching { shell("cmd notification $command $component") }
        if (isGranted(context) != granted) {
            val components = enabledComponents(context).toMutableSet()
            if (granted) components.add(component) else components.remove(component)
            runCatching { writeEnabledComponents(components) }
        }
        return waitUntil(5_000) { isGranted(context) == granted }
    }

    /** Raw `enabled_notification_listeners` value, for preservation checks. */
    fun rawEnabledListenerSetting(context: Context): String? =
        Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS)

    private fun enabledComponents(context: Context): List<String> =
        rawEnabledListenerSetting(context)
            ?.split(':')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    private fun writeEnabledComponents(components: Set<String>) {
        if (components.isEmpty()) {
            shell("settings delete secure $ENABLED_LISTENERS")
        } else {
            shell("settings put secure $ENABLED_LISTENERS ${components.joinToString(":")}")
        }
    }

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .use { stream -> stream.readBytes().toString(Charsets.UTF_8) }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}

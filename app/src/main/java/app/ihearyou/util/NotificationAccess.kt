package app.ihearyou.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import app.ihearyou.media.MediaBridgeService

/**
 * Notification-listener access cannot be granted programmatically. The best a normal app can do
 * is land the user directly on its own toggle (Android 11+), with the list as a fallback.
 */
object NotificationAccess {

    private val listener = ComponentName(
        "app.ihearyou",
        MediaBridgeService::class.java.name,
    )

    fun isGranted(context: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners",
        ) ?: return@runCatching false
        enabled.split(":").any { entry ->
            val comp = ComponentName.unflattenFromString(entry) ?: return@any false
            comp.packageName == context.packageName && comp.className == listener.className
        }
    }.getOrDefault(false)

    /** Opens the detail page for our listener on Android 11+, the list otherwise. */
    fun openSettings(context: Context) {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
            putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                listener.flattenToString(),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val ok = runCatching { context.startActivity(detail); true }.getOrDefault(false)
        if (!ok) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

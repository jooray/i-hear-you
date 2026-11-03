package app.ihearyou.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The remote is only useful if the button bridge survives a reboot, and an app update (which
 * kills the process), without the user remembering to open the app. BOOT_COMPLETED and
 * MY_PACKAGE_REPLACED are allowed exemptions for starting a connectedDevice foreground service.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { RemoteService.start(context) }
    }
}

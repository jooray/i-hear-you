package app.ihearyou.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.ihearyou.service.Cmd
import app.ihearyou.service.RemoteService

/** Notification actions: same-process broadcast straight into the service's command queue. */
class ControlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_QUIETER = "app.ihearyou.QUIETER"
        const val ACTION_LOUDER = "app.ihearyou.LOUDER"
        const val ACTION_MUTE = "app.ihearyou.MUTE_TOGGLE"
        const val ACTION_VOICE_STOP = "app.ihearyou.VOICE_STOP"
        const val ACTION_VOICE_RETRY = "app.ihearyou.VOICE_RETRY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = when (intent.action) {
            ACTION_QUIETER -> Cmd.VolumeAll(-1)
            ACTION_LOUDER -> Cmd.VolumeAll(1)
            ACTION_MUTE -> Cmd.Mute()
            ACTION_VOICE_STOP -> Cmd.VoiceStop
            ACTION_VOICE_RETRY -> Cmd.VoiceRetry(intent.getStringExtra("id") ?: return)
            else -> return
        }
        RemoteService.commands.trySend(cmd)
    }
}

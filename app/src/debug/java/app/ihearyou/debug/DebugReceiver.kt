package app.ihearyou.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.ihearyou.service.Cmd
import app.ihearyou.service.RemoteService
import kotlinx.coroutines.launch

/**
 * Debug builds only: drives the service from adb, so button flows can be tested without pressing
 * the aids. `adb shell am broadcast -n app.ihearyou/.debug.DebugReceiver -a app.ihearyou.debug.GESTURE
 * --es side right --es gesture LONG`
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "app.ihearyou.debug.GESTURE" -> RemoteService.commands.trySend(
                Cmd.SimulateGesture(
                    side = intent.getStringExtra("side") ?: "right",
                    gesture = intent.getStringExtra("gesture") ?: "SHORT",
                ),
            )
            "app.ihearyou.debug.LOG" -> RemoteService.addLog("debug: ${intent.getStringExtra("text")}")
            // --es path files/test.wav (16 kHz mono PCM16 WAV inside the app's data dir)
            "app.ihearyou.debug.TRANSCRIBE" -> {
                val file = java.io.File(context.dataDir, intent.getStringExtra("path") ?: return)
                // not goAsync: a long transcription outlives the broadcast timeout and the ANR
                // kills the process; the button service keeps the process alive meanwhile
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        val t = app.ihearyou.voice.Transcriber(context)
                        val c = t.resolve(app.ihearyou.voice.VoiceSettings(context).transcriber)
                        RemoteService.addLog("debug: transcribing ${file.name} with ${c?.flattenToShortString()}")
                        val text = t.transcribe(file, c ?: error("no provider"), (file.length() - 44) / 32)
                        RemoteService.addLog("debug: transcript: $text")
                    } catch (e: Throwable) {
                        RemoteService.addLog("debug: transcription failed: ${e.message}")
                    }
                }
            }
            // --es path files/test.wav --es dest WEBHOOK
            "app.ihearyou.debug.VOICE_IMPORT" -> RemoteService.commands.trySend(
                Cmd.VoiceImport(
                    java.io.File(context.dataDir, intent.getStringExtra("path") ?: return),
                    app.ihearyou.voice.Destination.valueOf(intent.getStringExtra("dest") ?: "SAVE"),
                ),
            )
            // --es group <groupIdHex prefix>: accept the invite and make it the voice-note group
            "app.ihearyou.debug.WN_ACCEPT" -> {
                val prefix = intent.getStringExtra("group") ?: return
                val g = app.ihearyou.marmot.MarmotHost.groups.value.firstOrNull { it.id.startsWith(prefix) } ?: return
                RemoteService.commands.trySend(Cmd.AcceptInvite(g.id, true))
                context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit().putString("wn_group", g.id).apply()
            }
            "app.ihearyou.debug.VOICE_START" -> RemoteService.commands.trySend(Cmd.VoiceStart)
            "app.ihearyou.debug.VOICE_STOP" -> RemoteService.commands.trySend(Cmd.VoiceStop)
            // --es key transcription --es value AUDIO_AND_TEXT (any VoiceSettings preference string)
            "app.ihearyou.debug.VOICE_SET" -> {
                val key = intent.getStringExtra("key") ?: return
                val value = intent.getStringExtra("value")
                val prefs = context.getSharedPreferences("voice", Context.MODE_PRIVATE).edit()
                when {
                    value == null -> prefs.remove(key)
                    value == "true" || value == "false" -> prefs.putBoolean(key, value.toBoolean())
                    value.toIntOrNull() != null && key == "max_minutes" -> prefs.putInt(key, value.toInt())
                    else -> prefs.putString(key, value)
                }
                prefs.apply()
                RemoteService.addLog("debug: voice $key = $value")
            }
        }
    }
}

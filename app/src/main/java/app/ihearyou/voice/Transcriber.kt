package app.ihearyou.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * On-device transcription of a finished recording through a recognition service that takes
 * caller-supplied audio (Android 13+ `EXTRA_AUDIO_SOURCE`), such as Offline Speech to Text.
 * Headless: no activity, works with the screen locked. The WAV's PCM (16 kHz mono PCM16, the
 * format OSTT documents) is streamed into the pipe and the writer closed; OSTT keeps it as one
 * utterance and answers once in onResults.
 */
class Transcriber(private val context: Context) {

    companion object {
        /** OSTT answers a request it can't take now (one dictation at a time) with ERROR_AUDIO. */
        private val BUSY = setOf(
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        )
        private val PREFERRED = listOf("app.offlinespeechtotext", "app.offlinespeechtotext.validation", "dev.notune.transcribe")
    }

    /** [busy]: the provider was in use (its keyboard dictating) or dropped us; worth retrying. */
    class Failed(message: String, val busy: Boolean = false, val noSpeech: Boolean = false) : Exception(message)

    /** Whether Offline Speech to Text (or a build of it) is installed; another recognizer, such as the system one, will not transcribe a recording. */
    fun osttInstalled(): Boolean = providers().any { it.packageName in PREFERRED }

    /** Recognition services on the phone, OSTT first. */
    fun providers(): List<ComponentName> = context.packageManager
        .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        .filter { it.serviceInfo.exported && it.serviceInfo.enabled }
        .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        .sortedBy { c -> PREFERRED.indexOf(c.packageName).let { if (it < 0) PREFERRED.size else it } }

    fun resolve(flattened: String?): ComponentName? {
        val all = providers()
        return flattened?.let { ComponentName.unflattenFromString(it) }?.takeIf { it in all } ?: all.firstOrNull()
    }

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    suspend fun transcribe(wav: File, component: ComponentName, durationMs: Long): String {
        if (!supported()) throw Failed("needs Android 13")
        // inference on a phone runs well above realtime; allow slow models and long rants
        val timeout = 120_000L + durationMs * 2
        return try {
            withTimeout(timeout) { run(wav, component) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // a newer recognition request (another app) can retire ours without an answer
            throw Failed("no answer in ${timeout / 1000} s", busy = true)
        }
    }

    private suspend fun run(wav: File, component: ComponentName): String = suspendCancellableCoroutine { cont ->
        val main = Handler(Looper.getMainLooper())
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        var recognizer: SpeechRecognizer? = null
        var writer: Thread? = null

        fun finish(result: Result<String>) {
            main.post {
                runCatching { recognizer?.destroy() }
                recognizer = null
            }
            runCatching { read.close() }
            writer?.interrupt()
            if (cont.isActive) cont.resumeWith(result)
        }

        main.post {
            try {
                val r = SpeechRecognizer.createSpeechRecognizer(context, component)
                recognizer = r
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                        if (text.isNullOrBlank()) finish(Result.failure(Failed("no speech recognised", noSpeech = true)))
                        else finish(Result.success(text))
                    }
                    override fun onError(error: Int) {
                        finish(Result.failure(Failed(errorName(error), busy = error in BUSY, noSpeech = error == SpeechRecognizer.ERROR_NO_MATCH)))
                    }
                })
                r.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, VoiceRecorder.SAMPLE_RATE),
                )
                writer = Thread({
                    // whole frames only, then EOF: OSTT finalises on the closed writer
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                            FileInputStream(wav).use { inp ->
                                inp.skip(44)
                                val buf = ByteArray(32 * 1024)
                                while (true) {
                                    val n = inp.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n - (n % 2))
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // the reader went away; the listener reports why
                    }
                }, "transcribe-feed").apply { start() }
            } catch (t: Throwable) {
                runCatching { write.close() }
                finish(Result.failure(Failed(t.message ?: t.javaClass.simpleName)))
            }
        }
        cont.invokeOnCancellation {
            main.post {
                runCatching { recognizer?.cancel() }
                runCatching { recognizer?.destroy() }
            }
            runCatching { read.close() }
            runCatching { write.close() }
        }
    }

    private fun errorName(error: Int) = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "no speech recognised"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
        SpeechRecognizer.ERROR_AUDIO -> "audio error"
        SpeechRecognizer.ERROR_CLIENT -> "client error (provider may not take supplied audio)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "insufficient permissions"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "provider disconnected"
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language unavailable"
        else -> "recognition error $error"
    }
}

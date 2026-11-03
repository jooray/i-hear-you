package app.ihearyou.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import app.ihearyou.MainActivity
import app.ihearyou.R
import app.ihearyou.button.Gesture
import app.ihearyou.control.ControlReceiver
import app.ihearyou.marmot.MarmotHost
import app.ihearyou.media.MediaBridgeService
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Voice notes driven from the aids' buttons: a mapped gesture starts recording, the next press on
 * either aid ends it and picks the destination (per aid and gesture). Whatever the destination,
 * the recording is saved first, and a transcript, when made, is saved next to it; delivery
 * failures leave the saved files and a Retry. Cues play into the aids at each step.
 */
class VoiceNotes(
    private val context: Context,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
) {

    /** What the panel shows while a note is being made. */
    data class Live(val id: String, val startedAt: Long, val mic: VoiceRecorder.Mic?, val startedBy: String, val device: String? = null)

    companion object {
        val live = MutableStateFlow<Live?>(null)
        private const val CHANNEL = "voice"
        private const val NOTIF_RECORDING = 41
        private const val NOTIF_RESULT = 42
        private const val NOTIF_SILENCE = 43
        /** What the pipeline is doing with a finished note: saving, transcribing, sending. */
        private const val NOTIF_PROGRESS = 44
        private val TRANSCRIBE_RETRY_S = listOf(5, 15, 30, 60, 120, 120, 120, 120)
        /** After the stop cue, before paused players resume: the aids leave call mode first. */
        private const val RESUME_DELAY_MS = 1_500L
    }

    private val settings = VoiceSettings(context)
    private val store = RecordingStore(context)
    private val recorder = VoiceRecorder(context)
    private val transcriber = Transcriber(context)
    val speaker = ReplySpeaker(context)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val pipeline = Mutex()
    /** Notes waiting for the pipeline behind the one in it, for the progress notification. */
    private val waiting = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var stage: Pair<String, Note>? = null
    private var limitJob: Job? = null
    @Volatile private var lastSentAt = 0L
    private val startedAt = System.currentTimeMillis()

    init {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.voice_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        store.reload()
        MarmotHost.log = log
        MarmotHost.onIncoming = ::onIncoming
    }

    val recording: Boolean get() = live.value != null

    // ------------------------------------------------------------------ start / finish

    fun start(address: String, micAllowed: Boolean, aidAddresses: Collection<String>) {
        if (recording) return
        speaker.skipCurrent()
        val id = UUID.randomUUID().toString().take(8) + "-" + System.currentTimeMillis()
        val note = Note(id = id, startedAt = System.currentTimeMillis())
        if (!micAllowed) {
            log("voice: microphone not available in the background; open I Hear You once")
            scope.launch(Dispatchers.IO) { Tones.play(Tones.Cue.FAILED) }
            notify(context.getString(R.string.voice_notif_mic_title), context.getString(R.string.voice_notif_mic_body), null)
            return
        }
        live.value = Live(id, note.startedAt, null, address)
        silenceWarned = false
        starting = true
        scope.launch(Dispatchers.IO) {
            try {
                store.put(note)
                // what plays is paused first, so the note isn't recorded over it, and resumed after
                // (together with players an earlier note has not resumed yet)
                paused = (paused + runCatching { MediaBridgeService.instance?.pausePlaying() }.getOrNull().orEmpty()).distinct()
                // waits for the Bluetooth route first (up to a few seconds), so the cue comes
                // after the aids' link has switched into call mode, which used to clip it, and
                // anything said after the cue is recorded from the right microphone
                val started = recorder.start(store.wav(id), settings.aidMic, aidAddresses) { onRecorderEvent(id, it) }
                // spoken while already recording: no need to wait for it before talking
                scope.launch(Dispatchers.IO) { Tones.playGoAhead(context) }
                live.value = Live(id, note.startedAt, started.mic, address, started.deviceName)
                store.put(note.copy(mic = started.mic.name))
                log("voice: recording $id on ${started.mic.name.lowercase()} mic (${started.deviceName ?: "?"}); route ${started.route}")
                val pending = synchronized(this@VoiceNotes) {
                    starting = false
                    pendingFinish.also { pendingFinish = null }
                }
                if (pending != null) {
                    finishWith(pending.first, pending.second)
                    return@launch
                }
                recordingNotification()
                scheduleLimit(address)
            } catch (t: Throwable) {
                log("voice: could not start recording: ${t.message ?: t.javaClass.simpleName}")
                starting = false
                pendingFinish = null
                live.value = null
                resumeMedia()
                store.put(note.copy(delivery = Delivery.FAILED, error = t.message))
                Tones.play(Tones.Cue.FAILED)
            }
        }
    }

    @Volatile private var silenceWarned = false
    /** Players paused for the current note, resumed once it ends. */
    @Volatile private var paused: List<String> = emptyList()

    /** Takes the paused players at the call, so a note started meanwhile keeps its own list. */
    private suspend fun resumeMedia(list: List<String> = paused.also { paused = emptyList() }) {
        if (list.isEmpty()) return
        delay(RESUME_DELAY_MS)
        // a new note started during the delay: its stop resumes these too
        if (recording) paused = (paused + list).distinct()
        else runCatching { MediaBridgeService.instance?.resume(list) }
    }
    /** True while the route comes up and the microphone opens; a press then ends the note right after. */
    @Volatile private var starting = false
    @Volatile private var pendingFinish: Pair<Destination, String>? = null

    private fun onRecorderEvent(id: String, e: VoiceRecorder.Event) {
        if (live.value?.id != id) return
        when (e) {
            is VoiceRecorder.Event.Routed -> {
                log("voice: input routed to ${e.mic.name.lowercase()} mic (${e.deviceName ?: "?"})")
                live.value = live.value?.copy(mic = e.mic, device = e.deviceName)
                scope.launch(Dispatchers.IO) { store.get(id)?.let { store.put(it.copy(mic = e.mic.name)) } }
            }
            is VoiceRecorder.Event.Silenced -> {
                log(if (e.silenced) "voice: Android is silencing the microphone (a call or another app has it)" else "voice: microphone no longer silenced")
                if (e.silenced) warnSilence() else clearSilence()
            }
            VoiceRecorder.Event.Silence -> {
                log("voice: 5 s of digital silence from the microphone")
                warnSilence()
            }
            is VoiceRecorder.Event.FellBack -> {
                log("voice: ${e.reason}, recording from the phone microphone now")
                clearSilence()
                scope.launch(Dispatchers.IO) { Tones.play(Tones.Cue.PHONE_MIC) }
            }
            VoiceRecorder.Event.SoundBack -> {
                log("voice: sound from the microphone again")
                clearSilence()
            }
            is VoiceRecorder.Event.Failed -> {
                log("voice: microphone failed: ${e.reason}")
                finishWith(Destination.SAVE, "microphone failed: ${e.reason}", failed = true)
            }
        }
    }

    /** Once per silent stretch: a warning cue in the aids and a notification; recording goes on. */
    private fun warnSilence() {
        if (silenceWarned) return
        silenceWarned = true
        scope.launch(Dispatchers.IO) { Tones.play(Tones.Cue.WARN) }
        notifySimple(NOTIF_SILENCE, context.getString(R.string.voice_notif_silent_title), context.getString(R.string.voice_notif_silent_body))
    }

    private fun clearSilence() {
        silenceWarned = false
        notifications.cancel(NOTIF_SILENCE)
    }

    /** The press that ends a recording: [gesture] on [address] picks the destination. */
    fun finish(address: String, gesture: Gesture) {
        finishWith(settings.finish(address, gesture), "$gesture on $address")
    }

    fun finishWith(destination: Destination, why: String, failed: Boolean = false) {
        val l = live.value ?: return
        synchronized(this) {
            if (starting) {
                pendingFinish = destination to why
                return
            }
        }
        live.value = null
        limitJob?.cancel()
        limitJob = null
        notifications.cancel(NOTIF_RECORDING)
        notifications.cancel(NOTIF_SILENCE)
        scope.launch(Dispatchers.IO) {
            val duration = recorder.stop()
            if (failed) {
                Tones.play(Tones.Cue.FAILED)
                notifySimple(NOTIF_RESULT, context.getString(R.string.voice_notif_micfail_title), context.getString(R.string.voice_notif_micfail_body))
            } else {
                Tones.play(Tones.Cue.STOP)
            }
            val toResume = paused.also { paused = emptyList() }
            scope.launch(Dispatchers.IO) {
                // says what the press chose, so a press read as another gesture is noticed
                val transcribing = settings.transcription != Transcription.AUDIO && transcriber.resolve(settings.transcriber) != null
                if (!failed) Tones.playDone(context, destination, transcribing)
                resumeMedia(toResume)
            }
            log("voice: stopped ${l.id} after ${duration / 1000} s ($why) -> ${destination.name}")
            val note = (store.get(l.id) ?: Note(l.id, l.startedAt)).copy(
                durationMs = duration, destination = destination, delivery = Delivery.PROCESSING,
            )
            store.put(note)
            process(note)
        }
    }

    private fun scheduleLimit(address: String) {
        val max = settings.maxMinutes * 60_000L
        limitJob = scope.launch {
            if (max > 120_000) {
                delay(max - 60_000)
                Tones.play(Tones.Cue.WARN)
                delay(60_000)
            } else {
                delay(max)
            }
            log("voice: length limit (${settings.maxMinutes} min) reached")
            finishWith(settings.finish(address, Gesture.SHORT), "length limit")
        }
    }

    // ------------------------------------------------------------------ pipeline

    private suspend fun process(start: Note) {
        waiting.incrementAndGet()
        stage?.let { (title, n) -> progress(title, n) }
        pipeline.withLock {
            waiting.decrementAndGet()
            try {
                processLocked(start)
            } finally {
                stage = null
                notifications.cancel(NOTIF_PROGRESS)
            }
        }
    }

    private suspend fun processLocked(start: Note) {
        var note = start
        progress(context.getString(R.string.voice_status_saving), note)
        val id = note.id
        val wav = store.wav(id)
        val m4a = store.m4a(id)
        val base = store.baseName(note.startedAt)
        try {
            if (!m4a.exists() || m4a.length() == 0L) AacEncoder.encode(wav, m4a)
            if (note.savedAs == null) {
                note = note.copy(savedAs = store.export(m4a, "$base.m4a", AacEncoder.MIME, settings.folderUri))
                store.put(note)
                log("voice: saved ${note.savedAs}")
            }
        } catch (t: Throwable) {
            // the working copy stays in app storage and is listed with its error
            log("voice: saving failed: ${t.message}")
            note = note.copy(error = "save: ${t.message}")
            store.put(note)
        }
        if (settings.transcription != Transcription.AUDIO && !note.transcript && wav.exists()) {
            note = transcribe(note, wav, base)
        }
        if (note.transcript || settings.transcription == Transcription.AUDIO) wav.delete()
        deliver(note)
    }

    private suspend fun transcribe(start: Note, wav: File, base: String): Note {
        var note = start
        val component = transcriber.resolve(settings.transcriber)
        if (component == null) {
            log("voice: no transcription app found (install Offline Speech to Text)")
            return note.copy(transcriptError = "no transcription app").also { store.put(it) }
        }
        return try {
            log("voice: transcribing with ${component.packageName}")
            progress(context.getString(R.string.voice_status_transcribing), note)
            var attempt = 0
            var text: String
            while (true) {
                try {
                    text = transcriber.transcribe(wav, component, note.durationMs)
                    break
                } catch (e: Transcriber.Failed) {
                    // the keyboard dictating holds OSTT; wait for it (about 10 min in all)
                    if (!e.busy || attempt >= TRANSCRIBE_RETRY_S.size) throw e
                    val wait = TRANSCRIBE_RETRY_S[attempt++]
                    log("voice: transcription busy (${e.message}), retry $attempt in $wait s")
                    progress(context.getString(R.string.voice_status_transcribe_wait), note)
                    delay(wait * 1000L)
                    progress(context.getString(R.string.voice_status_transcribing), note)
                }
            }
            store.transcriptFile(note.id).writeText(text)
            note = note.copy(transcript = true, transcriptError = null)
            store.put(note)
            runCatching { store.export(store.transcriptFile(note.id), "$base.txt", "text/plain", settings.folderUri) }
                .onFailure { log("voice: saving transcript failed: ${it.message}") }
            log("voice: transcript ${text.length} chars")
            note
        } catch (t: Throwable) {
            log("voice: transcription failed: ${t.message ?: t.javaClass.simpleName}")
            // the system recognizer (Google, Samsung) cannot transcribe a recording: "no speech"
            // then means "install OSTT", so say that
            val why = if ((t as? Transcriber.Failed)?.noSpeech == true && !transcriber.osttInstalled()) {
                context.getString(R.string.voice_no_speech_no_ostt)
            } else {
                t.message ?: t.javaClass.simpleName
            }
            note.copy(transcriptError = why).also { store.put(it) }
        }
    }

    private suspend fun deliver(start: Note) {
        var note = start
        val transcript = store.transcript(note.id)
        val textOnly = settings.transcription == Transcription.TEXT && transcript != null
        val withText = settings.transcription != Transcription.AUDIO && transcript != null
        val m4a = store.m4a(note.id)
        val name = store.baseName(note.startedAt) + ".m4a"
        if (note.destination != Destination.SAVE) {
            progress(context.getString(R.string.voice_status_sending, context.getString(note.destination.labelRes)), note)
        }
        // every transcript goes to the clipboard, whatever the destination, ready to paste
        if (withText) copyToClipboard(transcript!!)
        try {
            when (note.destination) {
                Destination.SAVE -> Unit
                Destination.WEBHOOK -> {
                    val url = settings.webhookUrl.takeIf { VoiceSettings.isHttps(it) } ?: error(context.getString(R.string.voice_webhook_https))
                    val meta = JSONObject()
                        .put("app", "i-hear-you")
                        .put("id", note.id)
                        .put("recorded_at", Instant.ofEpochMilli(note.startedAt).toString())
                        .put("duration_seconds", note.durationMs / 1000.0)
                        .put("microphone", note.mic?.lowercase())
                        .put("content", if (textOnly) "text" else if (withText) "audio+text" else "audio")
                    Webhook.post(url, settings.webhookAuth, meta, if (withText) transcript else null, if (textOnly) null else m4a, name)
                }
                Destination.WHITE_NOISE -> {
                    val group = settings.group ?: error("no White Noise group chosen")
                    if (MarmotHost.phase.value != MarmotHost.Phase.READY) MarmotHost.start(context)
                    // removed from the group (or it is gone): say so rather than MarmotKit's id dump
                    if (MarmotHost.groups.value.none { it.id == group && it.member && !it.pending }) error(context.getString(R.string.voice_wn_group_gone))
                    if (textOnly) MarmotHost.sendText(group, transcript!!)
                    else MarmotHost.sendVoice(group, m4a, name, AacEncoder.MIME, if (withText) transcript else null)
                    lastSentAt = System.currentTimeMillis()
                }
                Destination.SHARE -> share(m4a, if (withText) transcript else null, textOnly)
            }
            note = note.copy(delivery = if (note.destination == Destination.SAVE) Delivery.SAVED else Delivery.SENT, error = null)
            store.put(note)
            log("voice: ${note.destination.name.lowercase()} done for ${note.id}")
            if (note.destination != Destination.SAVE && note.destination != Destination.SHARE) Tones.play(Tones.Cue.SENT)
        } catch (t: Throwable) {
            val raw = MarmotHost.readable(t)
            val why = if (raw.contains("EPERM")) context.getString(R.string.voice_net_error) else raw
            note = note.copy(delivery = Delivery.FAILED, error = why)
            store.put(note)
            log("voice: ${note.destination.name.lowercase()} failed for ${note.id}: $why")
            Tones.play(Tones.Cue.FAILED)
            notify(
                context.getString(R.string.voice_notif_failed_title, context.getString(note.destination.labelRes)),
                context.getString(R.string.voice_notif_failed_body, note.savedAs ?: name),
                note.id,
            )
        }
    }

    /** Runs an existing 16 kHz mono WAV through the pipeline as if it had just been recorded (debug and tests). */
    fun import(wavSource: File, destination: Destination) {
        scope.launch(Dispatchers.IO) {
            val id = UUID.randomUUID().toString().take(8) + "-" + System.currentTimeMillis()
            wavSource.copyTo(store.wav(id), overwrite = true)
            val note = Note(
                id = id, startedAt = System.currentTimeMillis(), durationMs = (wavSource.length() - 44) * 1000 / (VoiceRecorder.SAMPLE_RATE * 2),
                destination = destination, mic = "IMPORT", delivery = Delivery.PROCESSING,
            )
            store.put(note)
            log("voice: imported ${wavSource.name} as $id -> ${destination.name}")
            process(note)
        }
    }

    /** Retry delivery of a saved note (from the list or the failure notification). */
    fun retry(id: String, destination: Destination? = null) {
        scope.launch(Dispatchers.IO) {
            val note = store.get(id) ?: return@launch
            notifications.cancel(NOTIF_RESULT)
            pipeline.withLock {
                try {
                    deliver(note.copy(destination = destination ?: note.destination, delivery = Delivery.PROCESSING))
                } finally {
                    stage = null
                    notifications.cancel(NOTIF_PROGRESS)
                }
            }
        }
    }

    fun delete(id: String) = store.delete(id)

    /** Notes the process died on: repair the WAV and finish them as saved recordings. */
    fun recover() {
        scope.launch(Dispatchers.IO) {
            for (n in store.unfinished()) {
                if (n.id == live.value?.id) continue
                val wav = store.wav(n.id)
                val note = if (n.delivery == Delivery.RECORDING) {
                    if (!wav.exists() || wav.length() <= 44) { store.delete(n.id); continue }
                    repairWav(wav)
                    n.copy(durationMs = (wav.length() - 44) * 1000 / (VoiceRecorder.SAMPLE_RATE * 2), destination = Destination.SAVE, delivery = Delivery.PROCESSING)
                } else n
                log("voice: recovering unfinished note ${n.id}")
                store.put(note)
                process(note)
            }
        }
    }

    /** Writing the clipboard is allowed from the background (only reading is not). */
    private fun copyToClipboard(text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                context.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.voice_clip_label), text))
                log("voice: transcript copied to the clipboard")
            }.onFailure { log("voice: copying the transcript failed: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ share

    private fun share(m4a: File, transcript: String?, textOnly: Boolean) {
        val send = Intent(Intent.ACTION_SEND).apply {
            if (textOnly) {
                type = "text/plain"
            } else {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".voice", m4a)
                type = AacEncoder.MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(m4a.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (transcript != null) putExtra(Intent.EXTRA_TEXT, transcript)
        }
        val chooser = Intent.createChooser(send, context.getString(R.string.voice_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (MainActivity.visible) {
            context.startActivity(chooser)
            return
        }
        // Android blocks activity starts from the background (and with the phone locked), so
        // the share sheet waits behind a notification
        val pi = PendingIntent.getActivity(context, 7, chooser, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notifications.notify(
            NOTIF_RESULT,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_meter)
                .setContentTitle(context.getString(R.string.voice_notif_share_title))
                .setContentText(context.getString(R.string.voice_notif_share_body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build(),
        )
    }

    // ------------------------------------------------------------------ replies

    private fun onIncoming(m: MarmotHost.Incoming) {
        if (m.groupId != settings.group) return
        // the runtime replays history on start; only new messages are read out
        if (m.at < startedAt - 60_000) return
        val play = when (settings.replies) {
            ReplyMode.OFF -> false
            ReplyMode.ALWAYS -> true
            ReplyMode.AFTER_NOTE -> System.currentTimeMillis() - lastSentAt < VoiceSettings.REPLY_WINDOW_MIN * 60_000L
        }
        val ignoredBy = settings.replyIgnore.firstOrNull { containsLoosely(m.text, it) }
        val audio = m.media.filter { it.mediaType.startsWith("audio/") }
        val readText = settings.replyReadText && m.text.isNotBlank()
        val why = when {
            !play -> ", not read"
            ignoredBy != null -> ", ignored (contains \"$ignoredBy\")"
            audio.isEmpty() && !readText -> ", text not read (voice replies only)"
            else -> ""
        }
        log("voice: reply in group (${m.text.length} chars, ${m.media.size} attachments)$why")
        if (why.isNotEmpty() || recording) return
        scope.launch(Dispatchers.IO) {
            try {
                for (ref in audio) {
                    val bytes = MarmotHost.download(m.groupId, ref)
                    val f = File(context.cacheDir, "reply-${m.messageId.take(12)}").apply { writeBytes(bytes) }
                    try { speaker.play(f) } finally { f.delete() }
                }
                if (readText) speaker.speak(m.text)
            } catch (t: Throwable) {
                log("voice: reply playback failed: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /** Case-insensitive, and blind to emoji variation selectors (🎙 and 🎙️ are the same). */
    private fun containsLoosely(text: String, needle: String): Boolean {
        fun norm(s: String) = s.replace("\uFE0F", "").replace("\uFE0E", "").lowercase()
        val n = norm(needle)
        return n.isNotEmpty() && norm(text).contains(n)
    }

    /** A short press while a reply plays stops it instead of doing its mapped action. */
    fun skipReply(): Boolean = speaker.skipCurrent()

    // ------------------------------------------------------------------ notifications

    private fun recordingNotification() {
        val stop = PendingIntent.getBroadcast(
            context, 40,
            Intent(context, ControlReceiver::class.java).setAction(ControlReceiver.ACTION_VOICE_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        notifications.notify(
            NOTIF_RECORDING,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_meter)
                .setContentTitle(context.getString(R.string.voice_notif_recording_title))
                .setContentText(context.getString(R.string.voice_notif_recording_body))
                .setOngoing(true)
                .setUsesChronometer(true)
                .setWhen(live.value?.startedAt ?: System.currentTimeMillis())
                .setSilent(true)
                .addAction(0, context.getString(R.string.voice_stop_save), stop)
                .build(),
        )
    }

    /** Ongoing, silent: [title] is the step; the body names the note and what waits behind it. */
    private fun progress(title: String, note: Note) {
        stage = title to note
        val length = note.durationMs / 1000
        var body = context.getString(R.string.voice_status_body, length / 60, length % 60)
        val queued = waiting.get()
        if (queued > 0) body += " · " + context.resources.getQuantityString(R.plurals.voice_status_queued, queued, queued)
        notifications.notify(
            NOTIF_PROGRESS,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_meter)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .build(),
        )
    }

    private fun notifySimple(id: Int, title: String, body: String) {
        notifications.notify(
            id,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_meter)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .build(),
        )
    }

    private fun notify(title: String, body: String, retryId: String?) {
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        if (retryId != null) {
            b.addAction(
                0, context.getString(R.string.voice_retry),
                PendingIntent.getBroadcast(
                    context, retryId.hashCode(),
                    Intent(context, ControlReceiver::class.java).setAction(ControlReceiver.ACTION_VOICE_RETRY).putExtra("id", retryId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        notifications.notify(NOTIF_RESULT, b.build())
    }

    fun shutdown() {
        if (recording) finishWith(Destination.SAVE, "service stopping")
        speaker.shutdown()
    }
}

package app.ihearyou.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Records 16 kHz mono PCM16 into a WAV file, from the hearing aids' own microphones when they
 * offer one (LE Audio input) and from the phone otherwise.
 *
 * The aids' input only opens in the call-like bidirectional LE Audio configuration, so the aids
 * are made the communication device for the length of the recording and released afterwards.
 * That switch is asynchronous: the recorder waits until the platform reports the new
 * communication device and lets it settle before opening the microphone, so the first words go
 * to the right input. 16 kHz mono PCM16 is also the format OSTT's caller-audio contract takes,
 * so the same file feeds the transcriber unchanged.
 */
class VoiceRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val WAV_HEADER = 44
        /** How long a press waits for the route before recording anyway. */
        private const val ROUTE_TIMEOUT_MS = 3_000L
        /** After the platform reports the route, before the microphone opens. */
        private const val ROUTE_SETTLE_MS = 500L
        /**
         * Silence played to the aids while their LE Audio input comes up. Pixel's audio HAL
         * skips its "BLE restart" when an LE input starts with no LE output active, and the
         * input then delivers only digital zeros for the whole recording; the spoken cue
         * usually covers that moment, this covers a late or failed cue.
         */
        private const val PRIME_MS = 3_000L
        /** Exact digital zeros this long mean a muted or taken microphone, not a quiet room. */
        private const val SILENCE_SAMPLES = SAMPLE_RATE * 5
        private const val MAX_EMPTY_READS = 10
        private const val TYPE_BLE_HEARING_AID = 33
    }

    /** Which microphone the recording ended up on, for the log and the result. */
    enum class Mic { HEARING_AID, BLUETOOTH, PHONE }

    class Started(val mic: Mic, val deviceName: String?, val route: String)

    /** Reported from the recording thread or the main thread while recording. */
    sealed interface Event {
        data class Routed(val mic: Mic, val deviceName: String?) : Event
        /** Android stopped delivering audio to us (a call or another app took the microphone). */
        data class Silenced(val silenced: Boolean) : Event
        /** Five seconds of exact zeros: privacy toggle, the aid's own mute, a stolen input. */
        data object Silence : Event
        data object SoundBack : Event
        /** The input broke; recording has stopped, what was captured is in the file. */
        data class Failed(val reason: String) : Event
        /**
         * The Bluetooth microphone never delivered sound or went away mid-recording (an aid
         * switched off); the recording continues on the phone's microphone in the same file.
         */
        data class FellBack(val reason: String) : Event
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var record: AudioRecord? = null
    @Volatile private var running = false
    /** Guards swapping [record] (fallback) against [stop]. */
    private val lock = Any()
    /** Recording from a Bluetooth microphone that may still fall back to the phone's. */
    @Volatile private var onBluetooth = false
    private var thread: Thread? = null
    private var commSet = false
    private var file: RandomAccessFile? = null
    private var events: (Event) -> Unit = {}
    private var routing: AudioRouting.OnRoutingChangedListener? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    @Volatile var bytes: Long = 0
        private set

    private var aids: Collection<String> = emptyList()

    private fun isAid(d: AudioDeviceInfo) = aids.any { it.equals(d.address, ignoreCase = true) }

    private fun micOf(d: AudioDeviceInfo?): Mic = when {
        d == null -> Mic.PHONE
        isLe(d.type) && isAid(d) -> Mic.HEARING_AID
        isLe(d.type) || d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> Mic.BLUETOOTH
        else -> Mic.PHONE
    }

    /** LE Audio; Android 17 (API 37) reports LE hearing aids with their own type, 33. */
    private fun isLe(type: Int) = type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == TYPE_BLE_HEARING_AID

    /** Where media plays now (Android 13+): with a car's hands-free and headphones both connected, the headphones. */
    private fun mediaAddresses(): Set<String> = if (android.os.Build.VERSION.SDK_INT >= 33) {
        runCatching {
            audio.getAudioDevicesForAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .mapNotNull { it.address?.takeIf { a -> a.isNotBlank() }?.uppercase() }.toSet()
        }.getOrDefault(emptySet())
    } else {
        emptySet()
    }

    private fun describe(d: AudioDeviceInfo?) = d?.let { "${it.productName} (type ${it.type}, ${it.address})" } ?: "none"

    /**
     * Communication devices to try, best first: the aids (by Bluetooth address), any other LE
     * Audio headset, a classic SCO headset. Taken from the communication-device list, which can
     * hold the aids even while other headphones are the active audio device; making one the
     * communication device then moves the call-mode route, and its microphone, there.
     */
    private fun candidates(): List<AudioDeviceInfo> {
        val comm = audio.availableCommunicationDevices
        val ble = comm.filter { isLe(it.type) }
        val sco = comm.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        // the aids first; then the headset media plays to (not a car's hands-free that is merely
        // connected, e.g. Android Auto with audio left on the phone); then LE Audio, then SCO
        val media = mediaAddresses()
        val playing = (ble + sco).filter { !isAid(it) && it.address.uppercase() in media }
        return (ble.filter(::isAid) + playing + ble.filterNot(::isAid) + sco).distinctBy { it.id }
    }

    /** The input side of a communication device, once it is routed. */
    private fun inputFor(comm: AudioDeviceInfo): AudioDeviceInfo? {
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
        // LE hearing aids may report output and input under different LE types and addresses
        fun family(d: AudioDeviceInfo) = if (isLe(comm.type)) isLe(d.type) else d.type == comm.type
        return inputs.firstOrNull { family(it) && it.address.equals(comm.address, ignoreCase = true) }
            ?: inputs.firstOrNull { family(it) }
    }

    private enum class Route { ALREADY, CONFIRMED, TIMEOUT, REFUSED }

    /** Makes [target] the communication device and waits until the platform says it is. */
    private fun route(target: AudioDeviceInfo): Route {
        if (audio.communicationDevice?.id == target.id) return Route.ALREADY
        val reached = CountDownLatch(1)
        val listener = AudioManager.OnCommunicationDeviceChangedListener { d ->
            if (d?.id == target.id) reached.countDown()
        }
        audio.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener)
        try {
            if (!runCatching { audio.setCommunicationDevice(target) }.getOrDefault(false)) return Route.REFUSED
            commSet = true
            if (audio.communicationDevice?.id != target.id && !reached.await(ROUTE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return Route.TIMEOUT
            }
            // the platform reports the device before the LE Audio link has reconfigured
            Thread.sleep(ROUTE_SETTLE_MS)
            return Route.CONFIRMED
        } finally {
            audio.removeOnCommunicationDeviceChangedListener(listener)
        }
    }

    /**
     * Routes and opens the microphone; blocks for up to a few seconds while a Bluetooth route
     * comes up, so call it off the main thread. [onEvent] hears about route changes, silence and
     * failures for the length of the recording.
     */
    @SuppressLint("MissingPermission")
    fun start(out: File, preferAid: Boolean, aidAddresses: Collection<String>, onEvent: (Event) -> Unit): Started {
        check(!running) { "already recording" }
        aids = aidAddresses
        events = onEvent
        var comm: AudioDeviceInfo? = null
        var routeNote = "phone microphone"
        if (preferAid) {
            val t0 = SystemClock.elapsedRealtime()
            for (c in candidates()) {
                val r = route(c)
                routeNote = "${describe(c)}: ${r.name.lowercase()} in ${SystemClock.elapsedRealtime() - t0} ms"
                if (r != Route.REFUSED) {
                    comm = c
                    break
                }
            }
            if (comm == null && routeNote == "phone microphone") routeNote = "no Bluetooth microphone offered"
        }
        val input = comm?.let(::inputFor)
        out.parentFile?.mkdirs()
        val f = RandomAccessFile(out, "rw").apply { setLength(0); write(ByteArray(WAV_HEADER)) }
        file = f
        bytes = 0
        val r = try {
            open(comm, input)
        } catch (t: Throwable) {
            f.close()
            release()
            throw t
        }
        watch(r)
        record = r
        onBluetooth = comm != null
        running = true
        thread = Thread({ pump(f) }, "voice-record").apply { start() }
        if (comm != null && isLe(comm.type)) Thread(::prime, "voice-prime").start()
        val routed = r.routedDevice ?: input
        return Started(micOf(routed), routed?.productName?.toString(), routeNote)
    }

    /** Builds and starts an AudioRecord: on [comm]'s microphone when given, else the phone's. */
    @SuppressLint("MissingPermission")
    private fun open(comm: AudioDeviceInfo?, input: AudioDeviceInfo?): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord.Builder()
            // VOICE_COMMUNICATION follows the communication device, which is what opens the aids' mics
            .setAudioSource(if (comm != null) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf * 4, SAMPLE_RATE))
            .build()
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            error("microphone unavailable")
        }
        if (input != null) r.preferredDevice = input
        try {
            r.startRecording()
        } catch (t: Throwable) {
            r.release()
            throw t
        }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            r.release()
            error("microphone refused (another app or no permission)")
        }
        return r
    }

    private fun unwatch(r: AudioRecord) {
        routing?.let { runCatching { r.removeOnRoutingChangedListener(it) } }
        recordingCallback?.let { runCatching { r.unregisterAudioRecordingCallback(it) } }
        routing = null
        recordingCallback = null
    }

    /**
     * Gives up on the Bluetooth microphone once: releases it and the aids' call-mode route and
     * continues on the phone's microphone, appending to the same file. False when that failed.
     */
    private fun fallBack(reason: MicFallback.Reason): Boolean {
        val next = synchronized(lock) {
            if (!running || !onBluetooth) return false
            onBluetooth = false
            record?.let { old ->
                unwatch(old)
                runCatching { old.stop() }
                runCatching { old.release() }
            }
            record = null
            release()
            val r = runCatching { open(null, null) }.getOrElse {
                events(Event.Failed("${reason.log}; phone microphone unavailable: ${it.message}"))
                return false
            }
            watch(r)
            record = r
            r
        }
        events(Event.FellBack(reason.log))
        events(Event.Routed(micOf(next.routedDevice), next.routedDevice?.productName?.toString()))
        return true
    }

    /** Where the input really went, and whether Android is silencing us. */
    private fun watch(r: AudioRecord) {
        var last: Int? = r.routedDevice?.id
        val l = AudioRouting.OnRoutingChangedListener { router ->
            val d = router.routedDevice
            if (d?.id != last) {
                last = d?.id
                events(Event.Routed(micOf(d), d?.productName?.toString()))
            }
        }
        r.addOnRoutingChangedListener(l, main)
        routing = l
        var silenced = false
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == r.audioSessionId } ?: return
                if (mine.isClientSilenced != silenced) {
                    silenced = mine.isClientSilenced
                    events(Event.Silenced(silenced))
                }
            }
        }
        r.registerAudioRecordingCallback(context.mainExecutor, cb)
        recordingCallback = cb
    }

    private fun pump(f: RandomAccessFile) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(SAMPLE_RATE / 5 * 2)
        val samplesPerMs = SAMPLE_RATE / 1000
        val t0 = SystemClock.elapsedRealtime()
        var lastData = t0
        var soundSeen = false
        var zeros = 0
        var silent = false
        var empty = 0
        try {
            while (running) {
                val r = record ?: break
                val n = r.read(buf, 0, buf.size)
                if (!running) break
                if (record !== r) continue // swapped by a fallback while this read was blocked
                val now = SystemClock.elapsedRealtime()
                if (n > 0) {
                    empty = 0
                    lastData = now
                    f.write(buf, 0, n)
                    bytes += n
                    var i = 0
                    while (i + 1 < n) {
                        if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0) zeros++ else { zeros = 0; soundSeen = true }
                        i += 2
                    }
                } else if (n == 0) {
                    // normal while an LE Audio input is still coming up; a failure on the phone mic
                    if (!onBluetooth && ++empty > MAX_EMPTY_READS) {
                        events(Event.Failed("no audio from the microphone"))
                        return
                    }
                    Thread.sleep(100)
                } else {
                    if (onBluetooth && fallBack(MicFallback.Reason.ERROR)) {
                        soundSeen = false; zeros = 0; lastData = SystemClock.elapsedRealtime()
                        continue
                    }
                    events(Event.Failed("microphone read error ${readError(n)}"))
                    return
                }
                if (onBluetooth) {
                    val reason = MicFallback.check(now - t0, soundSeen, zeros.toLong() / samplesPerMs, now - lastData)
                    if (reason != null) {
                        if (!fallBack(reason)) return
                        // judge the phone microphone afresh
                        soundSeen = false; zeros = 0; silent = false; lastData = SystemClock.elapsedRealtime()
                    }
                } else if (n > 0) {
                    if (!silent && zeros >= SILENCE_SAMPLES) {
                        silent = true
                        events(Event.Silence)
                    } else if (silent && zeros < n / 2) {
                        // the zero run is shorter than this block: sound came back in it
                        silent = false
                        events(Event.SoundBack)
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) events(Event.Failed(t.message ?: t.javaClass.simpleName))
        }
    }

    private fun readError(n: Int) = when (n) {
        AudioRecord.ERROR_INVALID_OPERATION -> "invalid operation"
        AudioRecord.ERROR_BAD_VALUE -> "bad value"
        AudioRecord.ERROR_DEAD_OBJECT -> "input lost"
        else -> n.toString()
    }

    /** Stops, closes the WAV (header filled in) and releases the aids' microphone. Returns duration ms. */
    private fun prime() {
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(SAMPLE_RATE / 5)
                .build()
        }.getOrNull() ?: return
        try {
            track.play()
            val silence = ShortArray(SAMPLE_RATE / 50)
            val until = SystemClock.elapsedRealtime() + PRIME_MS
            // blocking writes pace the loop at real time
            while (running && SystemClock.elapsedRealtime() < until) {
                if (track.write(silence, 0, silence.size) < 0) break
            }
        } catch (_: RuntimeException) {
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    fun stop(): Long {
        running = false
        synchronized(lock) {
            record?.let { r ->
                unwatch(r)
                runCatching { r.stop() }
            }
        }
        thread?.join(2_000)
        thread = null
        synchronized(lock) {
            runCatching { record?.release() }
            record = null
        }
        file?.let { f ->
            runCatching {
                f.seek(0)
                f.write(wavHeader(bytes))
            }
            runCatching { f.close() }
        }
        file = null
        release()
        events = {}
        return bytes * 1000 / (SAMPLE_RATE * 2)
    }

    private fun release() {
        if (commSet) runCatching { audio.clearCommunicationDevice() }
        commSet = false
    }

    private fun wavHeader(dataBytes: Long): ByteArray = ByteBuffer.allocate(WAV_HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt())
        put("WAVE".toByteArray()); put("fmt ".toByteArray())
        putInt(16); putShort(1); putShort(1)
        putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(dataBytes.toInt())
    }.array()
}

/** Fixes a WAV left without its header sizes (process killed mid-recording). */
fun repairWav(file: File) {
    val data = file.length() - 44
    if (data <= 0) return
    RandomAccessFile(file, "rw").use { f ->
        f.seek(4); f.write(le32(36 + data))
        f.seek(40); f.write(le32(data))
    }
}

private fun le32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()

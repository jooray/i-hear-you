package app.ihearyou.ble

import app.ihearyou.protocol.Crypto
import app.ihearyou.protocol.Encoder
import app.ihearyou.protocol.Frames
import app.ihearyou.protocol.Gn
import app.ihearyou.protocol.Platform
import app.ihearyou.protocol.authPayload
import app.ihearyou.protocol.deriveKeys
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** The aid answered a handle command with an error frame (type 8). */
class AidError(val handle: Int, val code: Int) : IOException("aid error 0x%02X on handle $handle".format(code))

data class PrepareInfo(
    val protocolVersion: Int,
    val handleListVersion: Int,
    val secVersion: Int,
    val appKeyIndex: Int,
    val bondMethod: Int,
    val mic: Boolean,
)

data class BondResult(val code: Int, val sharedAppKey: ByteArray?, val sharedIndex: Int)

/**
 * One trusted session with one aid: bond handshake + encrypted handle channel.
 * Call [prepare], then either [sessionBond] (stored key) or the boot pair
 * ([bootAuthFirst] -> power cycle -> [bootAuthFinal]).
 */
class GnSession(
    private val gatt: GattClient,
    private val scope: CoroutineScope,
) {
    private lateinit var encoder: Encoder

    var info: PrepareInfo? = null
        private set

    /** Null when the GN version is unknown; the session is then read-only. */
    val platform: Platform? get() = info?.let { Platform.of(it.protocolVersion, it.handleListVersion) }

    /** One command at a time: encrypt, write and wait for its reply as a single transaction. */
    private val txn = Mutex()

    private val frames = Channel<Pair<Int, ByteArray>>(Channel.UNLIMITED)
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<Pair<Int, ByteArray>>(
        replay = 0, extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    /** Every decoded frame, for UI/button consumers. */
    val events: kotlinx.coroutines.flow.SharedFlow<Pair<Int, ByteArray>> = _events
    private var collector: Job? = null

    private var micFailures = 0
    private val _desync = kotlinx.coroutines.flow.MutableStateFlow(false)
    /**
     * Turns true after several frames in a row fail the integrity check: another app has logged in
     * on the same link and the aid now encrypts for it, so this session can no longer talk.
     */
    val desync: kotlinx.coroutines.flow.StateFlow<Boolean> = _desync

    private fun decryptOrNull(type: Int, packet: ByteArray): ByteArray? = try {
        encoder.decrypt(packet).also { micFailures = 0 }
    } catch (e: Exception) {
        android.util.Log.w("IHearYou", "notify type $type dropped: ${e.message}")
        if (++micFailures >= 4) _desync.value = true
        null
    }

    private fun onNotify(raw: ByteArray) {
        if (raw.isEmpty()) return
        val type = raw[0].toInt() and 0xff
        val body = when {
            type in 2..7 -> decryptOrNull(type, raw.copyOfRange(1, raw.size)) ?: return
            type == 1 && raw.size > 2 -> byteArrayOf(raw[1]) + (decryptOrNull(type, raw.copyOfRange(2, raw.size)) ?: return)
            else -> raw.copyOfRange(1, raw.size)
        }
        // notifications go to the event stream only; the reply channel carries auth/read/error frames
        if (type != Frames.TYPE_NOTIFY) frames.trySend(type to body)
        _events.tryEmit(type to body)
    }

    suspend fun prepare() {
        gatt.subscribe(Gn.NOTIFY)
        collector = scope.launch {
            gatt.notifications.collect { (uuid, data) -> if (uuid == Gn.NOTIFY) onNotify(data) }
        }
        val v = readBusyRetry(Gn.VERSION)
        require(v.size >= 2) { "bad GN version frame" }
        gatt.write(Gn.SEC_CAP, byteArrayOf(4, 0, 0, 0, 0), attempts = 3)
        val cap = gatt.read(Gn.SEC_CAP)
        val secVersion = (cap[0].toInt() and 0xff) or ((cap[1].toInt() and 0xff) shl 8)
        val keyIndex = (cap[2].toInt() and 0xff) or ((cap[3].toInt() and 0xff) shl 8)
        val method = cap[4].toInt() and 3
        val mic = try {
            val f = gatt.read(Gn.FIT_PROTOCOL_CONFIG)
            f.isNotEmpty() && (f[0].toInt() and 3) != 0
        } catch (e: Exception) {
            false
        }
        info = PrepareInfo(v[0].toInt() and 0xff, v[1].toInt() and 0xff, secVersion, keyIndex, method, mic)
        encoder = Encoder(mic)
    }

    private suspend fun <T> waitFrame(
        timeoutMs: Long, pick: (Int, ByteArray) -> T?,
    ): T {
        return withTimeout(timeoutMs) {
            while (true) {
                val (type, body) = frames.receive()
                pick(type, body)?.let { return@withTimeout it }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException("unreachable")
        }
    }

    private fun codeName(code: Int): String = when (code) {
        0 -> "ok"
        1 -> "auth not started"
        2 -> "auth timeout"
        3 -> "auth blocked"
        11 -> "decrypt error"
        13 -> "credentials rejected"
        19 -> "auth not accepted"
        20 -> "shared index not supported"
        21 -> "waits for reboot"
        else -> "code $code"
    }

    /** The aid answers 0x82 (resource busy) when it is not ready; retry. */
    private suspend fun readBusyRetry(uuid: java.util.UUID, tries: Int = 8): ByteArray {
        var last: Exception? = null
        repeat(tries) {
            try {
                return gatt.read(uuid)
            } catch (e: Exception) {
                last = e
                delay(1500)
            }
        }
        throw last ?: IOException("read failed")
    }

    /** Run the auth exchange; returns the aid's code and (on success) the payload. */
    private suspend fun authenticate(
        connType: Int, sharedIndex: Int, hiPublic: ByteArray?, shared: ByteArray?,
    ): BondResult {
        val challenge = readBusyRetry(Gn.CHALLENGE)
        require(challenge.size >= 36) { "challenge too short (${challenge.size})" }
        val inf = info ?: throw IOException("prepare() first")
        val result = deriveKeys(challenge, inf.secVersion, hiPublic, shared)
        encoder.setKeys(result.derived.hiSession, result.derived.appSession)
        val packet = authPayload(encoder, connType, sharedIndex, result.ownPublicPoint)
        gatt.write(Gn.CHALLENGE, packet)
        val reply = waitFrame(25_000) { type, body ->
            if (type == Frames.TYPE_AUTH) body else null
        }
        val code = reply[0].toInt() and 0xff
        var index = 0
        if (code == 0 && reply.size > 1) {
            // onNotify already decrypted the type-1 payload
            val dec = reply.copyOfRange(1, reply.size)
            if (dec.size < 2 || !dec.copyOfRange(1, dec.size).contentEquals("HI says hi".toByteArray())) {
                throw IOException("unexpected auth payload")
            }
            index = dec[0].toInt() and 0xff
        }
        return BondResult(code, result.derived.sharedAppKey, index)
    }

    suspend fun sessionBond(shared: ByteArray, index: Int): BondResult {
        val mic = info?.mic ?: false
        return authenticate(if (mic) 12 else 4, index, null, shared)
    }

    suspend fun bootAuthFirst(): BondResult {
        val mic = info?.mic ?: false
        val pub = readBusyRetry(Gn.PUBLIC_KEY)
        return authenticate(if (mic) 9 else 1, 0, pub, null)
    }

    suspend fun bootAuthFinal(shared: ByteArray): BondResult {
        val mic = info?.mic ?: false
        return authenticate(if (mic) 10 else 2, 0, null, shared)
    }

    suspend fun setNotifyVector(handles: List<Int>) {
        val vec = ByteArray(16)
        for (h in handles) {
            val bit = h - 1
            vec[bit / 8] = (vec[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
        }
        txn.withLock { send(byteArrayOf(1) + vec) }
    }

    private suspend fun send(payload: ByteArray) {
        gatt.write(Gn.COMMAND, encoder.encrypt(payload), attempts = 4)
    }

    suspend fun readHandle(handle: Int, timeoutMs: Long = 7_000): ByteArray = txn.withLock {
        // drop replies left over from an earlier read that timed out
        while (frames.tryReceive().isSuccess) Unit
        send(byteArrayOf(4, handle.toByte()))
        waitFrame(timeoutMs) { type, body ->
            when (type) {
                Frames.TYPE_READ -> Frames.parseHandleValues(body).firstOrNull { it.handle == handle }?.data
                Frames.TYPE_ERROR -> Frames.parseErrors(body).firstOrNull { it.handle == handle }
                    ?.let { throw AidError(handle, it.code) }
                else -> null
            }
        }
    }

    /**
     * Writes one handle. Refused unless the platform is known and lists the handle as writable,
     * so firmware, DFU and fitting handles can never be reached from here.
     */
    suspend fun writeHandle(handle: Int, data: ByteArray) {
        val p = platform ?: throw IOException("unknown platform, read-only")
        if (handle !in p.writable) throw IOException("handle $handle is not writable on ${p.label}")
        txn.withLock { send(byteArrayOf(3, handle.toByte()) + data) }
    }

    /** Retry reads that return "resource busy" (0x82). */
    suspend fun readHandleRetry(handle: Int, tries: Int = 8): ByteArray {
        var last: Exception? = null
        repeat(tries) { i ->
            try {
                return readHandle(handle)
            } catch (e: Exception) {
                last = e
                delay(1500)
            }
        }
        throw last ?: IOException("read failed")
    }

    fun close() {
        collector?.cancel()
        frames.close()
    }
}

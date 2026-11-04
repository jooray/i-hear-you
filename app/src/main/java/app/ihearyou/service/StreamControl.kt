package app.ihearyou.service

import app.ihearyou.ble.GnSession
import app.ihearyou.protocol.Handles
import app.ihearyou.protocol.MixInButtons
import app.ihearyou.protocol.ReceiverCheck
import app.ihearyou.protocol.StreamVolume
import app.ihearyou.protocol.Tinnitus
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update

/**
 * Stream level (handle 6), the microphone quick keys while a stream is mixed in (handle 5), the
 * tinnitus generator level (handle 6 of a tinnitus program) and the receiver check (handle 37),
 * protocols 4 and 5 only.
 *
 * Reads on connect: attenuation defaults (16), per-program algorithm flags (19), the stream level
 * (6) and the receiver check (37). Writes only `[active program, level]` to 6 or 5, never while
 * charging, and reads every write back.
 */
class StreamControl(
    private val sessions: Map<String, GnSession>,
    private val log: (String) -> Unit,
    /** Linked aids on the shown program whose program has the tinnitus generator. */
    private val tinnitusMembers: () -> List<String>,
    /** A microphone level the app wrote and read back, so the service's own bookkeeping follows. */
    private val micWritten: (String, ByteArray) -> Unit,
) {
    /** Last audible stream level per aid, restored on unmute. */
    private val lastAudible = ConcurrentHashMap<String, Int>()
    /** Microphone level per aid serial from just before the first quick-key press. */
    private val micBefore = ConcurrentHashMap<String, Int>()

    private fun who(address: String): String = when (RemoteService.sides.value[address]) {
        "Left" -> "L"
        "Right" -> "R"
        else -> address
    }

    private fun keyOf(address: String) = RemoteService.info.value[address]?.serial?.takeIf { it.isNotBlank() } ?: address

    private fun charging(address: String): Boolean =
        (RemoteService.ui.value[address]?.hiState ?: 0) and 0x22 != 0

    private fun set(address: String, block: (AidUiState) -> AidUiState) {
        RemoteService.ui.update { m -> m[address]?.let { m + (address to block(it)) } ?: m }
    }

    // ------------------------------------------------------------------ reads

    suspend fun onConnect(address: String, session: GnSession, cros: Boolean) {
        val platform = session.platform ?: return
        if (!platform.streamVolume) return
        val defaults = runCatching { session.readHandle(Handles.ATTENUATION_DEFAULTS) }.getOrNull()
        val flags = runCatching { session.readHandle(Handles.CRITICAL_ALGO) }.getOrNull()
        val tinnitus = Tinnitus.programs(flags, platform)
        val level = runCatching { session.readHandle(Handles.STREAMER_ATTENUATION) }.getOrNull()
        val att = StreamVolume.attenuation(level)
        if (att != null && att > 0) lastAudible[address] = att
        log(
            "$address: stream att ${level?.let(SoundControl::hex) ?: "?"}, defaults ${defaults?.let(SoundControl::hex) ?: "?"}, " +
                "tsg ${flags?.let { "${it.size} B ${tinnitus?.map { p -> "P${p + 1}" } ?: "length not expected"}" } ?: "?"}",
        )
        set(address) {
            it.copy(
                streamControl = true,
                streamAttenuation = att,
                micDefault = StreamVolume.micDefault(defaults),
                streamDefault = StreamVolume.streamDefault(defaults),
                tinnitusPrograms = tinnitus,
            )
        }
        platform.receiverCheck?.let { h ->
            runCatching { session.readHandle(h) }
                .onSuccess { receiver(address, it, cros) }
                .onFailure { log("${who(address)}: rie read failed: ${it.message}") }
        }
    }

    /** A handle-6 value from a notification or a read. */
    fun level(address: String, data: ByteArray) {
        val att = StreamVolume.attenuation(data) ?: return
        if (att > 0) lastAudible[address] = att
        set(address) { it.copy(streamAttenuation = att, confirmedAt = System.currentTimeMillis()) }
    }

    /** The active program changed: its stream level is its own, read it again. */
    suspend fun reread(address: String) {
        val session = sessions[address] ?: return
        if (session.platform?.streamVolume != true) return
        // the fitted start levels belong to the program: read them again after every switch
        runCatching { session.readHandle(Handles.ATTENUATION_DEFAULTS) }.onSuccess { d ->
            val st = RemoteService.ui.value[address]
            log("${who(address)}: P${(st?.program ?: 0) + 1} start level ${SoundControl.hex(d)} (now ${st?.volume?.let { "%02X".format(it) } ?: "?"})")
            set(address) { it.copy(micDefault = StreamVolume.micDefault(d), streamDefault = StreamVolume.streamDefault(d)) }
        }
        runCatching { session.readHandle(Handles.STREAMER_ATTENUATION) }.onSuccess { level(address, it) }
    }

    /** A handle-37 value: decode, log and show. */
    fun receiver(address: String, data: ByteArray, cros: Boolean) {
        val status = ReceiverCheck.parse(data)
        val side = who(address)
        when {
            status == null -> log("$side: rie unreadable (${data.size} B ${SoundControl.hex(data)})")
            status.error -> log(
                "$side: rie err v${status.version} flags 0x%02X attached=${status.attached} valid=${status.dataValid} supported=${status.supported} side=${status.sideChanged} power=${status.powerChanged} type=${status.typeChanged}${if (cros) " (transmitter, not shown)" else ""}"
                    .format(status.flags),
            )
            else -> log("$side: rie ok v${status.version} flags 0x%02X".format(status.flags))
        }
        set(address) { it.copy(receiver = status) }
    }

    // ------------------------------------------------------------------ stream / tinnitus level

    /** Aids the stream level applies to now: each one's own program and stream state decide. */
    private fun streamTargets(): List<String> =
        RemoteService.ui.value.filter { (address, st) -> sessions.containsKey(address) && streamShownFor(st) }.keys.toList()

    private fun targets(tinnitus: Boolean): List<String> = if (tinnitus) tinnitusMembers() else streamTargets()

    /** [value] (attenuation 0..255) on every aid the level applies to; 0 mutes. */
    suspend fun setLevel(value: Int, tinnitus: Boolean) {
        val label = if (tinnitus) "tsg level" else "stream att"
        val targets = targets(tinnitus)
        if (targets.isEmpty()) return log("$label: no aid to write")
        write(label, targets, Handles.STREAMER_ATTENUATION) { _ -> value.coerceIn(0, 255) }
    }

    /** Mute, or unmute to the last audible level, the default from handle 16, or 170. */
    suspend fun mute(on: Boolean, tinnitus: Boolean) {
        val label = if (tinnitus) "tsg ${if (on) "mute" else "unmute"}" else "stream ${if (on) "mute" else "unmute"}"
        val targets = targets(tinnitus)
        if (targets.isEmpty()) return log("$label: no aid to write")
        write(label, targets, Handles.STREAMER_ATTENUATION) { address ->
            if (on) StreamVolume.MUTE
            else StreamVolume.unmuteTo(lastAudible[address], RemoteService.ui.value[address]?.streamDefault)
        }
    }

    // ------------------------------------------------------------------ microphone quick keys

    /**
     * A quick key while a phone or broadcast stream is mixed in. The first press keeps each aid's
     * microphone level; pressing the lit key again puts that level back, or, when it was lost,
     * the aid's default level. Only the microphone level changes.
     */
    suspend fun mixIn(kind: MixInButtons.Kind) {
        val ui = RemoteService.ui.value
        val aids = ui.filter { (address, st) -> sessions.containsKey(address) && st.micDefault != null && st.volume != null }
        if (aids.isEmpty()) return log("mic $kind: no aid with a default level")
        val active = MixInButtons.active(aids.values.map { it.micDefault!! to it.volume!! })
        val off = active == kind
        if (active == null) aids.forEach { (address, st) -> micBefore[keyOf(address)] = st.volume!! }
        val label = if (off) "mic $kind off" else "mic $kind"
        val ok = write(label, aids.keys.toList(), Handles.MIC_ATTENUATION) { address ->
            val st = RemoteService.ui.value[address]
            val d = st?.micDefault ?: return@write null
            if (off) micBefore[keyOf(address)] ?: d else MixInButtons.attenuation(kind, d)
        }
        if (ok && off) aids.keys.forEach { micBefore.remove(keyOf(it)) }
    }

    // ------------------------------------------------------------------ write

    /**
     * Writes `[active program, level]` to [handle] on each of [targets] (level from [levelFor],
     * null skips that aid), reads it back and compares. Nothing is written while any target is
     * charging; the first failure stops the rest.
     */
    private suspend fun write(label: String, targets: List<String>, handle: Int, levelFor: (String) -> Int?): Boolean {
        targets.firstOrNull { charging(it) }?.let {
            log("${who(it)}: $label: charging, nothing written")
            return fail(targets)
        }
        targets.forEach { a -> set(a) { it.copy(streamFailed = false) } }
        var written = 0
        for (address in targets) {
            val session = sessions[address] ?: continue
            val value = levelFor(address) ?: continue
            try {
                val p = session.readHandle(Handles.ACTIVE_PROGRAM)[0].toInt() and 0xff
                val current = session.readHandle(handle)
                if (StreamVolume.attenuation(current) == value && (current.getOrNull(0)?.toInt()?.and(0xff)) == p) {
                    log("${who(address)}: $label 0x%02X P${p + 1}: already set".format(value))
                    written++
                    continue
                }
                if (handle == Handles.STREAMER_ATTENUATION) StreamVolume.attenuation(current)?.takeIf { it > 0 }?.let { lastAudible[address] = it }
                val payload = StreamVolume.payload(p, value)
                session.writeHandle(handle, payload)
                delay(250)
                val back = session.readHandle(handle)
                if (!back.contentEquals(payload)) {
                    log("${who(address)}: $label 0x%02X P${p + 1} -> MISMATCH, reads ${SoundControl.hex(back)}".format(value))
                    return fail(targets)
                }
                log("${who(address)}: $label 0x%02X P${p + 1} -> verified".format(value))
                if (handle == Handles.MIC_ATTENUATION) micWritten(address, payload) else level(address, back)
                written++
            } catch (t: Throwable) {
                log("${who(address)}: $label failed: ${t.message ?: t.javaClass.simpleName}")
                return fail(targets)
            }
        }
        if (written == 0) return fail(targets)
        return true
    }

    private fun fail(targets: List<String>): Boolean {
        targets.forEach { a -> set(a) { it.copy(streamFailed = true) } }
        return false
    }

    companion object {
        /** Whether this aid shows a stream level now (its platform has one and its state calls for it). */
        fun streamShownFor(st: AidUiState): Boolean {
            if (!st.streamControl) return false
            val p = st.program ?: return false
            return app.ihearyou.protocol.StreamRules.streamVolumeShown(
                st.programCategories[p], st.environments.getOrNull(p), st.tinnitusPrograms?.contains(p) == true,
                st.streamType, st.streamStatus,
            )
        }

        /** Whether this aid's microphone level reads as "surroundings" now. */
        fun surroundingsFor(st: AidUiState): Boolean {
            if (!st.streamControl) return false
            val p = st.program ?: return false
            return app.ihearyou.protocol.StreamRules.surroundings(
                st.programCategories[p], st.environments.getOrNull(p), st.tinnitusPrograms?.contains(p) == true,
                st.streamType, st.streamStatus,
            )
        }
    }
}

package app.ihearyou.service

import android.content.Context
import app.ihearyou.ble.GnSession
import app.ihearyou.protocol.Eq
import app.ihearyou.protocol.FeatureSlice
import app.ihearyou.protocol.GainRecord
import app.ihearyou.protocol.Handles
import app.ihearyou.protocol.PriceTier
import app.ihearyou.protocol.ProgramNames
import app.ihearyou.protocol.SoundPresets
import app.ihearyou.protocol.SoundWriteGuard
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the panel shows for one aid's active program: its EQ and the pair's presets. */
data class SoundState(
    val program: Int,
    val eq: Eq,
    val presets: List<SoundPresets.Kind>,
    val active: SoundPresets.Kind? = null,
    /** Something differs from the fitted values (EQ not flat, or a feature moved). */
    val canReset: Boolean = false,
    val busy: Boolean = false,
    /** The last change was not confirmed by an aid; nothing more was written for it. */
    val failed: Boolean = false,
)

/**
 * Per-program bass/middle/treble and quick presets, protocol 5 only.
 *
 * Reads on connect: product information (13, price tier), input modes (18), environment ids (17),
 * last-fitted id (26), all gain records (28) and all feature slices (36). Writes only the active
 * program's gain record (27) and feature slice (35), never while charging, never for a program
 * other than the one the aid reports as active, and checks every write by reading 28/36 back.
 *
 * Kept on the phone, per aid serial: the records and slices as fitted (taken again whenever the
 * last-fitted id changes), and per program the values from just before the first preset press,
 * so pressing an active preset again restores them even after an app restart.
 */
class SoundControl(
    context: Context,
    private val scope: CoroutineScope,
    private val sessions: Map<String, GnSession>,
    private val log: (String) -> Unit,
) {
    private data class Aid(
        val serial: String,
        val cros: Boolean,
        val tier: PriceTier,
        val inputModes: List<Int>,
        val environments: List<Int>,
        val gain: List<ByteArray>,
        val features: List<ByteArray>,
    )

    private val prefs = context.getSharedPreferences("sound", Context.MODE_PRIVATE)
    private val aids = ConcurrentHashMap<String, Aid>()
    private val rereads = ConcurrentHashMap<String, Job>()
    @Volatile private var busy = false
    @Volatile private var failed = false

    // ------------------------------------------------------------------ connect

    suspend fun onConnect(address: String, session: GnSession, serial: String?, cros: Boolean) {
        if (session.platform?.programSound != true) return
        val key = serial?.takeIf { it.isNotBlank() } ?: address
        val product = runCatching { session.readHandle(Handles.PRODUCT_ID) }.getOrNull()
        val deviceId = runCatching { session.readHandle(Handles.DEVICE_ID) }.getOrNull()
        val tier = PriceTier.fromDeviceId(deviceId).takeIf { it != PriceTier.UNKNOWN } ?: PriceTier.parse(product)
        val modes = runCatching { session.readHandle(Handles.INPUT_MODE).map { it.toInt() and 0xff } }.getOrDefault(emptyList())
        val envs = runCatching { ProgramNames.parseEnvironmentIds(session.readHandle(Handles.ENVIRONMENT_ID)) }.getOrDefault(emptyList())
        val fittedId = runCatching { session.readHandle(Handles.LAST_FITTED_ID) }.getOrNull()
        val gainBlob = runCatching { session.readHandle(Handles.ALL_GAIN_DATA) }.getOrNull()
        val stdBlob = runCatching { session.readHandle(Handles.ALL_STANDARD_FEATURES) }.getOrNull()
        val gain = GainRecord.slices(gainBlob)
        val features = FeatureSlice.slices(stdBlob)
        val text = product?.let { String(it, Charsets.UTF_8).filter { c -> c.code in 0x20..0x7e } }
        val active = runCatching { session.readHandle(Handles.ACTIVE_PROGRAM)[0].toInt() and 0xff }.getOrNull()
        val eqLine = active?.let { p -> gain.getOrNull(p)?.let { " eq P${p + 1} ${GainRecord.read(it)}" } } ?: " no gain record"
        log("$address: tier=${tier.label} (device id ${deviceId?.let(::hex) ?: "?"}, product $text) inputModes=$modes fitted=${fittedId?.let(::hex) ?: "?"}$eqLine")
        log("$address: 28 ${gain.joinToString(" | ") { hex(it) }}")
        log("$address: 36 ${features.joinToString(" | ") { hex(it) }}")

        // fitted snapshot: first sight of this aid, or the audiologist fitted it again
        val id = fittedId?.let(::hex) ?: "?"
        val storedId = prefs.getString("fit.$key.id", null)
        if (gainBlob != null && gain.isNotEmpty() && (storedId == null || (fittedId != null && storedId != id))) {
            val e = prefs.edit()
                .putString("fit.$key.id", id)
                .putString("fit.$key.gain", hex(gainBlob))
                .putString("fit.$key.std", stdBlob?.let(::hex) ?: "")
            prefs.all.keys.filter { it.startsWith("before.$key.") }.forEach { e.remove(it) }
            e.apply()
            log("$address: ${if (storedId == null) "first seen" else "fitting changed"}, fitted values saved")
        }
        aids[address] = Aid(key, cros, tier, modes, envs, gain, features)
        publish()
    }

    fun forget(address: String) {
        rereads.remove(address)?.cancel()
        aids.remove(address)
        publish()
    }

    /** The active program changed: show its values now, then read them again from the aid. */
    fun programChanged(address: String) {
        publish()
        if (!aids.containsKey(address)) return
        rereads.remove(address)?.cancel()
        rereads[address] = scope.launch {
            delay(800)
            reread(address)
            publish()
        }
    }

    private suspend fun reread(address: String): Aid? {
        val session = sessions[address] ?: return null
        val aid = aids[address] ?: return null
        val gain = runCatching { GainRecord.slices(session.readHandle(Handles.ALL_GAIN_DATA)) }.getOrNull() ?: return null
        val features = runCatching { FeatureSlice.slices(session.readHandle(Handles.ALL_STANDARD_FEATURES)) }.getOrNull() ?: aid.features
        return aid.copy(gain = gain, features = features).also { aids[address] = it }
    }

    // ------------------------------------------------------------------ view

    private fun charging(address: String): Boolean =
        (RemoteService.ui.value[address]?.hiState ?: 0) and 0x22 != 0

    private data class View(
        val program: Int,
        val tier: PriceTier,
        val presets: List<SoundPresets.Preset>,
        /** Linked aids on [program] that have a gain record for it, receivers first. */
        val members: List<String>,
        val active: SoundPresets.Preset?,
    )

    private fun programOf(address: String): Int? = RemoteService.ui.value[address]?.program

    private fun view(): View? {
        val entries = aids.entries.sortedBy { if (it.value.cros) 1 else 0 }
        val lead = entries.firstOrNull { programOf(it.key) != null && it.value.gain.isNotEmpty() } ?: return null
        val program = programOf(lead.key) ?: return null
        // on a CROS or BiCROS system one top-tier aid unlocks the feature values for both
        val tier = if (entries.any { it.value.tier == PriceTier.TOP }) PriceTier.TOP else lead.value.tier
        val mode = lead.value.inputModes.getOrNull(program)
        val focus = SoundPresets.speechFocusAvailable(tier, lead.value.features.getOrNull(program), mode)
        val presets = SoundPresets.forProgram(lead.value.environments.getOrNull(program), mode, tier, focus)
        val members = entries.filter { (a, aid) -> programOf(a) == program && aid.gain.size > program }.map { it.key }
        val apply = SoundPresets.featuresApply(tier)
        val active = SoundPresets.active(
            presets,
            members.map { a -> aids.getValue(a).let { GainRecord.read(it.gain[program]) to it.features.getOrNull(program) } },
            apply,
        )
        return View(program, tier, presets, members, active)
    }

    private fun fittedSlice(aid: Aid, program: Int): ByteArray? =
        prefs.getString("fit.${aid.serial}.std", null)?.let(::unhex)?.let { FeatureSlice.slices(it).getOrNull(program) }

    /** Push the current sound state of every aid into [RemoteService.ui]. */
    fun publish() {
        // an aid can drop out while this runs; the next publish shows the settled state
        runCatching { publishNow() }.onFailure { log("sound: ${it.message ?: it.javaClass.simpleName}") }
    }

    private fun publishNow() {
        val v = view()
        val apply = v?.let { SoundPresets.featuresApply(it.tier) } == true
        val canReset = v != null && v.members.any { a ->
            val aid = aids.getValue(a)
            val slice = aid.features.getOrNull(v.program)
            val fitted = fittedSlice(aid, v.program)
            GainRecord.read(aid.gain[v.program]) != Eq.FLAT ||
                (apply && slice != null && fitted != null && !FeatureSlice.restore(slice, fitted).contentEquals(slice))
        }
        RemoteService.ui.update { m ->
            m.mapValues { (address, st) ->
                val aid = aids[address]
                val sound = if (v == null || aid == null || address !in v.members) null else SoundState(
                    program = v.program,
                    eq = GainRecord.read(aid.gain[v.program]),
                    presets = v.presets.map { it.kind },
                    active = v.active?.kind,
                    canReset = canReset,
                    busy = busy,
                    failed = failed,
                )
                if (st.sound == sound) st else st.copy(sound = sound)
            }
        }
    }

    // ------------------------------------------------------------------ commands

    private class Target(val record: ByteArray, val slice: ByteArray?)

    private fun beforeOf(aid: Aid, program: Int): Pair<ByteArray, ByteArray?>? {
        val raw = prefs.getString("before.${aid.serial}.$program", null) ?: return null
        val parts = raw.split('|')
        val record = unhex(parts[0]).takeIf { it.size == GainRecord.SIZE } ?: return null
        val slice = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let(::unhex)?.takeIf { it.size == FeatureSlice.SIZE }
        return record to slice
    }

    private fun saveBefore(aid: Aid, program: Int, record: ByteArray, slice: ByteArray?) {
        prefs.edit().putString("before.${aid.serial}.$program", hex(record) + "|" + (slice?.let(::hex) ?: "")).apply()
    }

    private fun clearBefore(aid: Aid, program: Int) {
        prefs.edit().remove("before.${aid.serial}.$program").apply()
    }

    /**
     * A preset key on [program]: turns [kind] on (computed from the values before any preset), or,
     * when it is the active one, off again (back to those values, or to the fitted ones).
     */
    suspend fun applyPreset(program: Int, kind: SoundPresets.Kind) {
        val v = view() ?: return log("preset $kind: no gain records")
        if (v.program != program) return log("preset $kind: program changed to P${v.program + 1}, nothing written")
        val def = v.presets.firstOrNull { it.kind == kind } ?: return log("preset $kind: not offered on P${program + 1}")
        val apply = SoundPresets.featuresApply(v.tier)
        val off = v.active?.kind == kind
        val label = if (off) "preset $kind off" else "preset $kind"
        val ok = write(label, program, v.members) { aid, record, slice ->
            val before = beforeOf(aid, program)
            if (off) {
                val sliceTarget = if (!apply || slice == null) null
                else (before?.second ?: fittedSlice(aid, program))?.let { FeatureSlice.restore(slice, it) }
                Target(GainRecord.withEq(record, before?.first?.let(GainRecord::read) ?: Eq.FLAT), sliceTarget)
            } else {
                // a fresh press starts from the live values; moving between presets keeps the first
                // snapshot, or, if that was lost, starts from the fitted values
                val base = when {
                    v.active == null -> (record to slice).also { saveBefore(aid, program, record, slice) }
                    before != null -> before
                    else -> (GainRecord.withEq(record, Eq.FLAT) to (fittedSlice(aid, program) ?: slice))
                        .also { (r, s) -> saveBefore(aid, program, r, s) }
                }
                val baseSlice = base.second
                val sliceTarget = if (!apply || !def.touchesFeatures || slice == null) null
                else FeatureSlice.apply(baseSlice?.let { FeatureSlice.restore(slice, it) } ?: slice, def.noiseReduction, def.wind, def.focus)
                Target(GainRecord.withEq(record, def.eq), sliceTarget)
            }
        }
        if (ok && off) v.members.mapNotNull { aids[it] }.forEach { clearBefore(it, program) }
    }

    /** The bass/middle/treble sliders: [eq] for [program] on every aid that has a record. */
    suspend fun setEq(program: Int, eq: Eq) {
        val v = view() ?: return log("eq: no gain records")
        if (v.program != program) return log("eq: program changed to P${v.program + 1}, nothing written")
        write("eq ${eq.clamped()}", program, v.members) { _, record, _ -> Target(GainRecord.withEq(record, eq), null) }
    }

    /** Back to the fitted values: flat EQ, and the fitted feature slice where features apply. */
    suspend fun reset(program: Int) {
        val v = view() ?: return log("reset: no gain records")
        if (v.program != program) return log("reset: program changed to P${v.program + 1}, nothing written")
        val apply = SoundPresets.featuresApply(v.tier)
        val ok = write("reset", program, v.members) { aid, record, slice ->
            val fitted = fittedSlice(aid, program)
            Target(GainRecord.withEq(record, Eq.FLAT), if (apply && slice != null && fitted != null) FeatureSlice.restore(slice, fitted) else null)
        }
        if (ok) v.members.mapNotNull { aids[it] }.forEach { clearBefore(it, program) }
    }

    /**
     * Writes one change to [members] in order. For each aid: confirm it is not charging and is on
     * [program], read its live record and slice, build the target, check it only touches what it
     * may, write 27 (and 35 if the slice changes), read 28/36 back and compare. The first failure
     * stops the rest.
     */
    private suspend fun write(
        label: String,
        program: Int,
        members: List<String>,
        build: (Aid, ByteArray, ByteArray?) -> Target,
    ): Boolean {
        if (members.isEmpty()) {
            log("$label: no aid to write")
            return false
        }
        members.firstOrNull { charging(it) }?.let {
            log("$it: $label: charging, nothing written")
            failed = true
            publish()
            return false
        }
        busy = true
        failed = false
        publish()
        var written = 0
        try {
            for (address in members) {
                val session = sessions[address] ?: continue
                if (session.platform?.programSound != true) continue
                if (charging(address)) {
                    log("$address: $label: charging, nothing written")
                    failed = true
                    return false
                }
                val p = runCatching { session.readHandle(Handles.ACTIVE_PROGRAM)[0].toInt() and 0xff }.getOrNull()
                if (p != program) {
                    log("$address: $label: aid is on ${p?.let { "P${it + 1}" } ?: "?"}, not P${program + 1}, skipped")
                    continue
                }
                val aid = reread(address) ?: run {
                    log("$address: $label: cannot read the gain records")
                    failed = true
                    return false
                }
                val record = aid.gain.getOrNull(p) ?: continue
                val slice = aid.features.getOrNull(p)
                val t = build(aid, record, slice)
                if (!SoundWriteGuard.gainOk(record, t.record) ||
                    (t.slice != null && (slice == null || !SoundWriteGuard.featuresOk(slice, t.slice)))
                ) {
                    log("$address: $label: refused, target touches more than it may (${hex(t.record)} / ${t.slice?.let(::hex)})")
                    failed = true
                    return false
                }
                val parts = ArrayList<String>()
                if (!t.record.contentEquals(record)) {
                    val payload = GainRecord.write(p, record, GainRecord.read(t.record)).also { check(it.copyOfRange(1, 14).contentEquals(t.record)) }
                    session.writeHandle(Handles.GAIN_DATA, payload)
                    parts += "27 ${hex(payload)}"
                }
                if (t.slice != null && slice != null && !t.slice.contentEquals(slice)) {
                    val payload = FeatureSlice.write(p, t.slice)
                    session.writeHandle(Handles.STANDARD_FEATURES, payload)
                    parts += "35 ${hex(payload)}"
                }
                if (parts.isEmpty()) {
                    log("$address: $label P${p + 1}: already set")
                    written++
                    continue
                }
                delay(250)
                val back = reread(address)
                val gainOk = back?.gain?.getOrNull(p)?.contentEquals(t.record) == true
                val sliceOk = t.slice == null || back?.features?.getOrNull(p)?.contentEquals(t.slice) == true
                if (!gainOk || !sliceOk) {
                    log("$address: $label P${p + 1} -> ${parts.joinToString(", ")}, MISMATCH: 28 reads ${back?.gain?.getOrNull(p)?.let(::hex)}, 36 reads ${back?.features?.getOrNull(p)?.let(::hex)}")
                    failed = true
                    return false
                }
                log("$address: $label P${p + 1} -> ${parts.joinToString(", ")}, verified")
                written++
            }
            if (written == 0) failed = true
            return written > 0
        } catch (t: Throwable) {
            log("$label: failed: ${t.message ?: t.javaClass.simpleName}")
            failed = true
            return false
        } finally {
            busy = false
            publish()
        }
    }

    companion object {
        fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it) }
        fun unhex(s: String): ByteArray =
            s.split(' ').filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
    }
}

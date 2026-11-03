package app.ihearyou.ui

import android.content.Context
import app.ihearyou.R
import app.ihearyou.service.AidInfo
import app.ihearyou.service.AidUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val preciseClock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

fun clockOf(millis: Long?): String = if (millis == null || millis <= 0L) "—" else clock.format(Instant.ofEpochMilli(millis))
fun preciseClockOf(millis: Long?): String = if (millis == null || millis <= 0L) "—" else preciseClock.format(Instant.ofEpochMilli(millis))

fun sideLetter(context: Context, side: String?): String = context.getString(
    when (side) {
        "Left" -> R.string.side_letter_left
        "Right" -> R.string.side_letter_right
        else -> R.string.side_letter_unknown
    },
)

fun sideWord(context: Context, side: String?): String = context.getString(
    when (side) {
        "Left" -> R.string.side_left
        "Right" -> R.string.side_right
        else -> R.string.side_unknown
    },
)

/** "Left", "Right" or "Side unknown": the side word in sentence case. */
fun sideTitle(context: Context, side: String?): String =
    sideWord(context, side).lowercase().replaceFirstChar { it.uppercase() }

fun roleWord(context: Context, info: AidInfo?): String = context.getString(
    when {
        info == null -> R.string.role_hearing_aid
        info.cros -> R.string.role_cros_tx
        else -> R.string.role_receiver
    },
)

fun roleLong(context: Context, info: AidInfo?): String = context.getString(
    when {
        info == null -> R.string.role_long_hearing_aid
        info.cros -> R.string.role_long_cros
        else -> R.string.role_long_receiver
    },
)

fun modelWord(context: Context, info: AidInfo?, fallback: String?): String =
    info?.model?.takeIf { it.isNotBlank() } ?: fallback ?: context.getString(R.string.role_hearing_aid)

/** Auth/attenuation byte to the 0-99 level the fader shows. A linear transform of the aid's own
 * attenuation byte, not a claim about loudness. */
fun levelOf(volume: Int?): Int? = volume?.let { ((it.coerceIn(0, 255) / 255f) * 100f).roundToInt() }

/**
 * The aid reports battery on a 1–10 scale (1 = lowest, 10 = full).
 * Rechargeable aids step through every value; disposable cells mostly report 1, 5 or 10.
 */
fun batteryWord(context: Context, level: Int?, hiState: Int? = null): String = when {
    hiState != null && hiState and 0x40 != 0 -> context.getString(R.string.battery_full)
    hiState != null && hiState and 0x02 != 0 -> context.getString(R.string.battery_charging)
    hiState != null && hiState and 0x20 != 0 -> context.getString(R.string.battery_in_charger)
    level == null -> "—"
    level <= 1 -> context.getString(R.string.battery_empty)
    level <= 2 -> context.getString(R.string.battery_low)
    level >= 10 -> context.getString(R.string.battery_full)
    else -> context.getString(R.string.battery_level, level)
}

fun streamWord(context: Context, status: Int?): String = when (status) {
    0 -> context.getString(R.string.stream_idle)
    1 -> context.getString(R.string.stream_searching)
    2 -> context.getString(R.string.stream_out_of_range)
    3 -> context.getString(R.string.stream_not_found)
    4 -> context.getString(R.string.stream_streaming)
    null -> "—"
    else -> context.getString(R.string.stream_state, status)
}

fun streamKindWord(context: Context, type: Int?): String? = when (type) {
    1 -> context.getString(R.string.stream_kind_le_audio)
    2 -> context.getString(R.string.stream_kind_btb)
    3 -> context.getString(R.string.stream_kind_sas)
    4 -> context.getString(R.string.stream_kind_spouse_mic)
    5 -> context.getString(R.string.stream_kind_le_broadcast)
    else -> null
}

fun tapWord(context: Context, tap: Int?): String = when {
    tap == null -> "—"
    tap and 0x02 != 0 && tap and 0x01 != 0 -> context.getString(R.string.tap_calls_program)
    tap and 0x02 != 0 -> context.getString(R.string.tap_calls)
    tap and 0x01 != 0 -> context.getString(R.string.tap_program)
    else -> context.getString(R.string.tap_off)
}

fun hexByte(value: Int?): String = value?.let { "0x%02X".format(it and 0xff) } ?: "—"

/** Last two bytes of an address: enough to tell two aids apart, quiet enough for a plate. */
fun shortAddress(address: String): String = "…" + address.takeLast(5)

fun hexBits(value: Int?): String = value?.let { "0b" + Integer.toBinaryString(it).padStart(8, '0') } ?: "—"

/** Both aids sorted left, right, then unknown. */
fun orderedAids(
    addresses: List<String>,
    sides: Map<String, String>,
    infos: Map<String, AidInfo>,
    states: Map<String, AidUiState>,
): List<Pair<String, AidUiState?>> = addresses
    .sortedWith(compareBy({ sideRank(sides[it]) }, { infos[it]?.cros != true }, { it }))
    .map { it to states[it] }

private fun sideRank(side: String?): Int = when (side) {
    "Left" -> 0
    "Right" -> 1
    else -> 2
}

fun linkWord(context: Context, phase: app.ihearyou.service.LinkPhase): String = context.getString(
    when (phase) {
        app.ihearyou.service.LinkPhase.CONNECTING -> R.string.link_connecting
        app.ihearyou.service.LinkPhase.LINKED -> R.string.link_linked
        app.ihearyou.service.LinkPhase.NEEDS_PAIRING -> R.string.link_needs_pairing
        app.ihearyou.service.LinkPhase.BLOCKED -> R.string.link_blocked
        app.ihearyou.service.LinkPhase.TAKEN_OVER -> R.string.link_other_app
        app.ihearyou.service.LinkPhase.UNSUPPORTED -> R.string.link_not_supported
        app.ihearyou.service.LinkPhase.BLUETOOTH_OFF -> R.string.link_bluetooth_off
    },
)

/** The aid's program name, or the app-language name when it is a standard program left unrenamed. */
fun programLabel(context: android.content.Context, state: app.ihearyou.service.AidUiState?, index: Int, aidName: String): String =
    when (state?.programKinds?.get(index)) {
        null -> aidName
        app.ihearyou.protocol.ProgramNames.Kind.ALL_AROUND -> context.getString(app.ihearyou.R.string.program_kind_all_around)
        app.ihearyou.protocol.ProgramNames.Kind.RESTAURANT -> context.getString(app.ihearyou.R.string.program_kind_restaurant)
        app.ihearyou.protocol.ProgramNames.Kind.MUSIC -> context.getString(app.ihearyou.R.string.program_kind_music)
        app.ihearyou.protocol.ProgramNames.Kind.OUTDOOR -> context.getString(app.ihearyou.R.string.program_kind_outdoor)
        app.ihearyou.protocol.ProgramNames.Kind.LECTURE -> context.getString(app.ihearyou.R.string.program_kind_lecture)
        app.ihearyou.protocol.ProgramNames.Kind.HEAR_IN_NOISE -> context.getString(app.ihearyou.R.string.program_kind_hear_in_noise)
        app.ihearyou.protocol.ProgramNames.Kind.TRAFFIC -> context.getString(app.ihearyou.R.string.program_kind_traffic)
    }

/** The aid reports charging or sitting in the charger: the sound controls stay hidden then. */
fun isCharging(hiState: Int?): Boolean = hiState != null && hiState and 0x22 != 0

fun soundPresetLabel(context: android.content.Context, kind: app.ihearyou.protocol.SoundPresets.Kind): String =
    context.getString(
        when (kind) {
            app.ihearyou.protocol.SoundPresets.Kind.NOISE_FILTER -> app.ihearyou.R.string.sound_noise_filter
            app.ihearyou.protocol.SoundPresets.Kind.SPEECH_CLARITY -> app.ihearyou.R.string.sound_speech_clarity
            app.ihearyou.protocol.SoundPresets.Kind.SPEECH_FOCUS -> app.ihearyou.R.string.sound_speech_focus
            app.ihearyou.protocol.SoundPresets.Kind.HEAR_EVERYONE -> app.ihearyou.R.string.sound_hear_everyone
            app.ihearyou.protocol.SoundPresets.Kind.BASS_BOOST -> app.ihearyou.R.string.sound_bass_boost
            app.ihearyou.protocol.SoundPresets.Kind.TREBLE_BOOST -> app.ihearyou.R.string.sound_treble_boost
        },
    )

/**
 * The pair's sound state for the Now screen and the detail plate: the first receiver's, and only
 * while no linked aid is charging.
 */
fun pairSound(
    aids: List<Pair<String, app.ihearyou.service.AidUiState?>>,
    infos: Map<String, app.ihearyou.service.AidInfo>,
): app.ihearyou.service.SoundState? {
    val linked = aids.mapNotNull { it.second }
    if (linked.any { isCharging(it.hiState) }) return null
    val lead = aids.filter { it.second?.sound != null }
        .sortedBy { if (infos[it.first]?.cros == true) 1 else 0 }
        .firstOrNull()?.second ?: return null
    return lead.sound
}

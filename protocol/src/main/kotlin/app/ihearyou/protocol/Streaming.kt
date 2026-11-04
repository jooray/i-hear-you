package app.ihearyou.protocol

/**
 * Confirmation tone (handle 22). The aid plays a short tone for the value written; the app only
 * ever writes the "setting changed" tone, after a sound change it has read back. Every other value
 * names an aid event (power down, pairing, flight mode, …) and is refused by [allowed].
 */
object Melody {
    const val CONFIRM = 5

    fun confirm(platform: Platform): ByteArray =
        if (platform.melodySize == 1) byteArrayOf(CONFIRM.toByte()) else byteArrayOf(CONFIRM.toByte(), 0)

    fun allowed(platform: Platform, data: ByteArray): Boolean = data.contentEquals(confirm(platform))
}

/**
 * Stream level (handle 6): the attenuation applied to the stream, telecoil or audio input of the
 * active program. Read and notify carry `[program, attenuation]`; a write is
 * `[active program, attenuation]`. Attenuation 0 mutes, 1 is the minimum, 255 the maximum.
 * Handle 16 holds the defaults: byte 0 for the microphone, byte 1 for the stream.
 */
object StreamVolume {
    const val MUTE = 0
    /** Unmute level when neither a last level nor a default is known. */
    const val FALLBACK = 170
    /** The slider has 13 positions, 1 (quietest) to 13. */
    const val STEPS = 13

    fun payload(program: Int, attenuation: Int): ByteArray {
        require(program in 0..0xff) { "program out of range" }
        require(attenuation in 0..255) { "attenuation out of range" }
        return byteArrayOf(program.toByte(), attenuation.toByte())
    }

    /** Attenuation from a handle-6 (or handle-5) value: byte 1, or byte 0 of a one-byte value. */
    fun attenuation(data: ByteArray?): Int? = when {
        data == null || data.isEmpty() -> null
        data.size >= 2 -> data[1].toInt() and 0xff
        else -> data[0].toInt() and 0xff
    }

    fun micDefault(defaults: ByteArray?): Int? = defaults?.getOrNull(0)?.toInt()?.and(0xff)
    fun streamDefault(defaults: ByteArray?): Int? = defaults?.getOrNull(1)?.toInt()?.and(0xff)

    /** Level to restore on unmute: the last audible level, else the default, else [FALLBACK]. */
    fun unmuteTo(lastAudible: Int?, default: Int?): Int =
        lastAudible?.takeIf { it in 1..255 } ?: default?.takeIf { it in 1..255 } ?: FALLBACK

    /** Slider position 1..13 for an attenuation (0, muted, reads as 1). */
    fun step(attenuation: Int): Int = ((attenuation.coerceIn(0, 255) / 255f) * 12f).toInt() + 1

    /** The lowest attenuation on slider position 1..13 (position 1 is 1, never mute), so [step] reads it back. */
    fun attenuationOf(step: Int): Int = ((step.coerceIn(1, STEPS) - 1) * 255 + 11).div(12).coerceIn(1, 255)

    /**
     * The level [steps] positions away from [attenuation] on the 1..13 scale, as the aid's own
     * button moves it. Landing on the position that holds the program's fitted [default] gives the
     * default itself, so stepping back always finds the audiologist's level again. Muted (0)
     * counts as position 1; the result is never mute.
     */
    fun stepped(attenuation: Int, steps: Int, default: Int? = null): Int {
        val target = (step(attenuation) + steps).coerceIn(1, STEPS)
        val d = default?.takeIf { it in 1..255 }
        return if (d != null && step(d) == target) d else attenuationOf(target)
    }
}

/**
 * When the app offers a stream level and calls the microphone level "surroundings", from the
 * active program's category (handle 11) or type (handle 17), whether it is a tinnitus program, and
 * the stream type (31) and status (21).
 *
 * Stream type: 0 none, 1 phone over LE audio, 2 Bluetooth bridge, 3 accessory streamer,
 * 4 partner microphone, 5 broadcast. Status: 1 searching and 4 streaming count as connected.
 * During a phone stream (1, 2) the phone sets the stream level, so the app offers none.
 */
object StreamRules {
    const val CATEGORY_TELECOIL = 200
    const val CATEGORY_STREAMER = 201
    const val CATEGORY_AUDIO_INPUT = 255
    private val TELECOIL_TYPES = setOf(6, 7)
    private const val AUDIO_INPUT_TYPE = 9

    /** Stream type 0..5; anything else reads as none. */
    fun mixIn(streamType: Int?): Int = streamType?.takeIf { it in 0..5 } ?: 0

    fun connected(streamStatus: Int?): Boolean = streamStatus == 1 || streamStatus == 4

    fun streamVolumeShown(category: Int?, environment: Int?, tinnitus: Boolean, streamType: Int?, streamStatus: Int?): Boolean {
        val mix = mixIn(streamType)
        if (mix == 1 || mix == 2) return false
        if (tinnitus) return false
        val telecoil = category == CATEGORY_TELECOIL || (category == null && environment in TELECOIL_TYPES)
        val audioInput = category == CATEGORY_AUDIO_INPUT || (category == null && environment == AUDIO_INPUT_TYPE)
        val streamerProgram = category == CATEGORY_STREAMER && connected(streamStatus)
        val mixedIn = mix in 3..5 && connected(streamStatus)
        return telecoil || audioInput || streamerProgram || mixedIn
    }

    /** The microphone level is labelled "surroundings" while a stream level exists or anything is mixed in. */
    fun surroundings(category: Int?, environment: Int?, tinnitus: Boolean, streamType: Int?, streamStatus: Int?): Boolean =
        !tinnitus && (streamVolumeShown(category, environment, false, streamType, streamStatus) || mixIn(streamType) != 0)
}

/**
 * Quick keys while something is mixed in. During a phone or broadcast stream (type 1, 2, 5) two
 * keys move the microphone level relative to the aid's default (handle 16 byte 0): "focus" to
 * the minimum (not mute), "hear all" five sixths of the way from the default to the maximum.
 * During an accessory or partner-microphone stream (3, 4) the program presets become bass and
 * treble boost ([SoundPresets.forStream]).
 */
object MixInButtons {
    enum class Kind(val steps: Int) { FOCUS(-12), HEAR_ALL(5) }

    fun offered(streamType: Int?): List<Kind> =
        if (StreamRules.mixIn(streamType) in setOf(1, 2, 5)) listOf(Kind.FOCUS, Kind.HEAR_ALL) else emptyList()

    /** Microphone attenuation for [kind] on an aid whose default level is [default]. */
    fun attenuation(kind: Kind, default: Int): Int {
        val d = default.coerceIn(0, 255)
        val n = kind.steps / 6f
        val v = if (n >= 0) ((255 - d) * n + d).toInt() else (1 + d * (12 + kind.steps) / 12f).toInt()
        return v.coerceIn(1, 255)
    }

    /** [kind] matches one aid when its level is on the same 1..13 step as the key's level and not muted. */
    fun matches(kind: Kind, default: Int, current: Int): Boolean =
        current != 0 && StreamVolume.step(attenuation(kind, default)) == StreamVolume.step(current)

    /** The key every aid matches ([default], [current] per aid), or null. */
    fun active(aids: List<Pair<Int, Int>>): Kind? {
        if (aids.isEmpty()) return null
        return Kind.entries.lastOrNull { k -> aids.all { (d, c) -> matches(k, d, c) } }
    }
}

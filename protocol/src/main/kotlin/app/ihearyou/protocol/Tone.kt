package app.ihearyou.protocol

/**
 * Price tier of an aid, from the product-information string (handle 13). The string is
 * '.'-separated: brand, platform, then a three-letter tier token. Only the tiers that change what
 * the app offers are told apart; anything else is [UNKNOWN] and treated like a non-top aid.
 */
enum class PriceTier(val label: String) {
    TOP("Top"), PLUS("Plus"), BASIC("Basic"), BUDGET("Budget"), UNKNOWN("Unknown");

    companion object {
        fun parse(product: String?): PriceTier {
            val token = product?.trim('\u0000', ' ')?.split('.')?.getOrNull(2)?.trim() ?: return UNKNOWN
            if (token.length != 3) return UNKNOWN
            return when {
                token == "Top" -> TOP
                token == "Pls" || token == "Prp" -> PLUS
                token == "Bas" || token == "Prm" || token.startsWith("Bs") -> BASIC
                token == "Bdg" || token.startsWith("Bd") -> BUDGET
                else -> UNKNOWN
            }
        }

        /** Product-information bytes as text (NUL padded). */
        fun parse(raw: ByteArray?): PriceTier = parse(raw?.let { String(it, Charsets.UTF_8) })
    }
}

/** Bass / middle / treble in whole dB. */
data class Eq(val bass: Int, val middle: Int, val treble: Int) {
    fun clamped() = Eq(bass.coerceIn(MIN, MAX), middle.coerceIn(MIN, MAX), treble.coerceIn(MIN, MAX))
    override fun toString() = "B$bass M$middle T$treble"

    companion object {
        const val MIN = -6
        const val MAX = 6
        val FLAT = Eq(0, 0, 0)
    }
}

/**
 * Per-program gain record (handle 28 read, handle 27 write).
 *
 * Handle 28 holds one 13-byte record per program: `[crossover][0|bass][middle|treble][10 band bytes]`.
 * The crossover byte packs the low selector (high nibble) and the high selector (low nibble);
 * 0x36 is the default pair. Bass, middle and treble are signed 4-bit values, 1 dB per step.
 * The band bytes are per-band offsets that the app never changes.
 *
 * Handle 27 sets the active program's record: `[program][13-byte record]`.
 */
object GainRecord {
    const val SIZE = 13
    const val DEFAULT_CROSSOVER = 0x36

    fun slices(blob: ByteArray?): List<ByteArray> {
        if (blob == null || blob.isEmpty() || blob.size % SIZE != 0) return emptyList()
        return (0 until blob.size / SIZE).map { blob.copyOfRange(it * SIZE, (it + 1) * SIZE) }
    }

    private fun signed(nibble: Int): Int = if (nibble >= 8) nibble - 16 else nibble

    fun read(record: ByteArray): Eq {
        require(record.size == SIZE) { "gain record must be $SIZE bytes" }
        val b1 = record[1].toInt() and 0xff
        val b2 = record[2].toInt() and 0xff
        return Eq(bass = signed(b1 and 0x0f), middle = signed(b2 shr 4), treble = signed(b2 and 0x0f))
    }

    /** [record] with only bass/middle/treble replaced (clamped), and a zero crossover set to the default. */
    fun withEq(record: ByteArray, eq: Eq): ByteArray {
        require(record.size == SIZE) { "gain record must be $SIZE bytes" }
        val e = eq.clamped()
        val out = record.copyOf()
        if (out[0].toInt() == 0) out[0] = DEFAULT_CROSSOVER.toByte()
        out[1] = (((record[1].toInt() and 0xf0)) or (e.bass and 0x0f)).toByte()
        out[2] = (((e.middle and 0x0f) shl 4) or (e.treble and 0x0f)).toByte()
        return out
    }

    /** The 14-byte handle-27 write for [program]. */
    fun write(program: Int, record: ByteArray, eq: Eq): ByteArray {
        require(program in 0..0xff)
        return byteArrayOf(program.toByte()) + withEq(record, eq)
    }
}

/** A speech-focus setting: a directionality id and a speech-focus band id written as a pair. */
enum class Focus(val beam: Int, val band: Int) { NARROW(4, 0), WIDE_LOW(6, 3) }

/**
 * Per-program feature slice (handle 36 read, handle 35 write: `[program][9-byte slice]`).
 * Each slot holds the id of one feature's current value, or 0xFF when unused. A change only ever
 * replaces an id in a slot that already holds the same feature; unused slots, tinnitus ids and
 * every other feature are copied through.
 */
object FeatureSlice {
    const val SIZE = 9
    const val UNUSED = 0xFF

    /** Noise reduction values a slot may be set to (off … very strong). */
    val NOISE_REDUCTION = 12..18
    /** A noise-reduction slot holding one of these belongs to a transmitter and is never changed. */
    val NOISE_REDUCTION_FIXED = 19..20
    val WIND = 51..54
    val DIRECTIONALITY = 4..7
    val SPEECH_BAND = 0..3

    fun slices(blob: ByteArray?): List<ByteArray> {
        if (blob == null || blob.isEmpty() || blob.size % SIZE != 0) return emptyList()
        return (0 until blob.size / SIZE).map { blob.copyOfRange(it * SIZE, (it + 1) * SIZE) }
    }

    private fun ids(slice: ByteArray) = slice.map { it.toInt() and 0xff }

    private fun groupOf(id: Int): IntRange? = listOf(NOISE_REDUCTION, WIND, DIRECTIONALITY, SPEECH_BAND).firstOrNull { id in it }

    fun hasSpeechFocus(slice: ByteArray?): Boolean =
        slice != null && ids(slice).any { it in DIRECTIONALITY } && ids(slice).any { it in SPEECH_BAND }

    /** Current noise-reduction / wind ids, or null when the slice has no such slot. */
    fun noiseReduction(slice: ByteArray): Int? = ids(slice).firstOrNull { it in NOISE_REDUCTION }
    fun wind(slice: ByteArray): Int? = ids(slice).firstOrNull { it in WIND }
    fun focus(slice: ByteArray): Pair<Int, Int>? {
        val beam = ids(slice).firstOrNull { it in DIRECTIONALITY } ?: return null
        val band = ids(slice).firstOrNull { it in SPEECH_BAND } ?: return null
        return beam to band
    }

    /** [slice] with the given values placed in the slots that already hold those features. */
    fun apply(slice: ByteArray, noiseReduction: Int?, wind: Int?, focus: Focus?): ByteArray {
        require(slice.size == SIZE) { "feature slice must be $SIZE bytes" }
        require(noiseReduction == null || noiseReduction in NOISE_REDUCTION)
        require(wind == null || wind in WIND)
        val out = slice.copyOf()
        val withFocus = focus != null && hasSpeechFocus(slice)
        for (i in out.indices) {
            val id = out[i].toInt() and 0xff
            val next = when {
                id == UNUSED -> null
                noiseReduction != null && id in NOISE_REDUCTION -> noiseReduction
                wind != null && id in WIND -> wind
                withFocus && id in DIRECTIONALITY -> focus!!.beam
                withFocus && id in SPEECH_BAND -> focus!!.band
                else -> null
            }
            if (next != null) out[i] = next.toByte()
        }
        return out
    }

    /**
     * [current] with each managed slot (noise reduction, wind, directionality, speech band) set
     * back to [saved]'s id, where [saved] holds the same feature in that slot. Everything else
     * stays as in [current].
     */
    fun restore(current: ByteArray, saved: ByteArray): ByteArray {
        require(current.size == SIZE && saved.size == SIZE)
        val out = current.copyOf()
        for (i in out.indices) {
            val now = current[i].toInt() and 0xff
            val was = saved[i].toInt() and 0xff
            val group = groupOf(now) ?: continue
            if (was in group) out[i] = was.toByte()
        }
        return out
    }

    /** The 10-byte handle-35 write for [program]. */
    fun write(program: Int, slice: ByteArray): ByteArray {
        require(program in 0..0xff && slice.size == SIZE)
        return byteArrayOf(program.toByte()) + slice
    }
}

/**
 * Last check before a write: a gain record may only differ from the aid's current one in the
 * bass/middle/treble bytes (and a zero crossover becoming the default); a feature slice only in
 * slots whose old and new ids belong to the same managed feature.
 */
object SoundWriteGuard {
    fun gainOk(current: ByteArray, target: ByteArray): Boolean {
        if (current.size != GainRecord.SIZE || target.size != GainRecord.SIZE) return false
        val c0 = current[0].toInt() and 0xff
        val t0 = target[0].toInt() and 0xff
        if (c0 != t0 && !(c0 == 0 && t0 == GainRecord.DEFAULT_CROSSOVER)) return false
        if ((current[1].toInt() and 0xf0) != (target[1].toInt() and 0xf0)) return false
        for (i in 3 until GainRecord.SIZE) if (current[i] != target[i]) return false
        val eq = GainRecord.read(target)
        return eq == eq.clamped()
    }

    fun featuresOk(current: ByteArray, target: ByteArray): Boolean {
        if (current.size != FeatureSlice.SIZE || target.size != FeatureSlice.SIZE) return false
        val groups = listOf(FeatureSlice.NOISE_REDUCTION, FeatureSlice.WIND, FeatureSlice.DIRECTIONALITY, FeatureSlice.SPEECH_BAND)
        for (i in current.indices) {
            val c = current[i].toInt() and 0xff
            val t = target[i].toInt() and 0xff
            if (c == t) continue
            if (groups.none { c in it && t in it }) return false
        }
        return true
    }
}

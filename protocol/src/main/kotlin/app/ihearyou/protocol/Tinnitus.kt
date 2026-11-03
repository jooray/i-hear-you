package app.ihearyou.protocol

/** Which ear an aid sits on (handle 2: 0 left, 1 right). */
enum class Side {
    LEFT, RIGHT;

    companion object {
        fun of(label: String?): Side? = when (label) {
            "Left" -> LEFT
            "Right" -> RIGHT
            else -> null
        }
    }
}

/**
 * The tinnitus sound generator. Whether a program has it is fitted, not chosen: bit 0 of byte
 * `2 · program` of handle 19 (two flag bytes per program, read-only). Its shaping lives in the
 * program's feature slice (handle 36 read, 35 write) as four features, each in its own slot:
 *
 * * noise variation 21..24 (none, slight, medium, strong),
 * * low edge (high-pass) 25..30 (off, 500, 750, 1000, 1500, 2000 Hz),
 * * high edge (low-pass) 31..37 (off, 2000, 3000, 4000, 5000, 6000, 8000 Hz),
 * * nature sound 38 off, 39..44 on the left aid, 45..50 on the right aid, same six sounds in the
 *   same order on both sides.
 *
 * Its level is the program's handle 6 ([StreamVolume]).
 *
 * An id is only ever replaced by another id of the same feature in a slot that already holds that
 * feature, and nature sounds only with ids of the aid's own side.
 */
object Tinnitus {
    val VARIATION = 21..24
    val HIGH_PASS = 25..30
    val LOW_PASS = 31..37
    val NATURE = 38..50
    const val NATURE_OFF = 38
    val NATURE_LEFT = 39..44
    val NATURE_RIGHT = 45..50
    const val NATURE_SOUNDS = 6
    const val HIGH_PASS_OFF = 25
    const val LOW_PASS_OFF = 31

    /** Programs with the generator, or null when the flags have an unexpected length for [platform]. */
    fun programs(flags: ByteArray?, platform: Platform): Set<Int>? {
        if (flags == null || flags.size != platform.criticalAlgoLength) return null
        return (0 until flags.size / 2).filter { (flags[2 * it].toInt() and 0x01) != 0 }.toSet()
    }

    private fun ids(slice: ByteArray) = slice.map { it.toInt() and 0xff }
    private fun slot(slice: ByteArray, range: IntRange): Int? = ids(slice).indexOfFirst { it in range }.takeIf { it >= 0 }
    private fun id(slice: ByteArray, range: IntRange): Int? = slot(slice, range)?.let { slice[it].toInt() and 0xff }

    fun hasVariation(slice: ByteArray?) = slice != null && slot(slice, VARIATION) != null
    fun hasNature(slice: ByteArray?) = slice != null && slot(slice, NATURE) != null
    /** The frequency edges are offered only with both edges, a variation and a nature-sound slot. */
    fun hasRange(slice: ByteArray?) = slice != null && slot(slice, HIGH_PASS) != null && slot(slice, LOW_PASS) != null &&
        hasVariation(slice) && hasNature(slice)
    fun any(slice: ByteArray?) = hasVariation(slice) || hasNature(slice) || hasRange(slice)

    /** 0..3, or null without the slot. */
    fun variation(slice: ByteArray): Int? = id(slice, VARIATION)?.minus(VARIATION.first)
    /** 0..5 for a nature sound, -1 for off, null without the slot. */
    fun nature(slice: ByteArray): Int? = id(slice, NATURE)?.let(::natureIndex)
    /** 0 off, 1..5 = 500..2000 Hz, or null. */
    fun highPass(slice: ByteArray): Int? = id(slice, HIGH_PASS)?.minus(HIGH_PASS.first)
    /** 0 off, 1..6 = 2000..8000 Hz, or null. */
    fun lowPass(slice: ByteArray): Int? = id(slice, LOW_PASS)?.minus(LOW_PASS.first)

    fun natureIndex(id: Int): Int = when (id) {
        in NATURE_LEFT -> id - NATURE_LEFT.first
        in NATURE_RIGHT -> id - NATURE_RIGHT.first
        else -> -1
    }

    fun natureId(index: Int, side: Side): Int {
        require(index in 0 until NATURE_SOUNDS)
        return (if (side == Side.LEFT) NATURE_LEFT.first else NATURE_RIGHT.first) + index
    }

    private fun put(slice: ByteArray, range: IntRange, id: Int): Boolean {
        val i = slot(slice, range) ?: return false
        slice[i] = id.toByte()
        return true
    }

    /** Noise variation [v] (0..3) with nature sounds off; null without a variation slot. */
    fun withVariation(slice: ByteArray, v: Int): ByteArray? {
        require(v in 0..3)
        val out = slice.copyOf()
        if (!put(out, VARIATION, VARIATION.first + v)) return null
        put(out, NATURE, NATURE_OFF)
        return out
    }

    /** Nature sound [index] (0..5, or -1 for off) with the id of [side]; null without the slot. */
    fun withNature(slice: ByteArray, index: Int, side: Side): ByteArray? {
        val out = slice.copyOf()
        val id = if (index < 0) NATURE_OFF else natureId(index, side)
        return out.takeIf { put(it, NATURE, id) }
    }

    /**
     * Low edge [h] (0 off .. 5 = 2000 Hz). The edges never both end open or meet at 2000 Hz:
     * opening the low edge while the high edge is open sets the high edge to 8000 Hz, and a 2000 Hz
     * low edge under a 2000 Hz high edge moves the high edge to 3000 Hz.
     */
    fun withHighPass(slice: ByteArray, h: Int): ByteArray? {
        require(h in 0..5)
        val out = slice.copyOf()
        val hp = HIGH_PASS.first + h
        if (!put(out, HIGH_PASS, hp)) return null
        val lp = id(out, LOW_PASS)
        if (hp == HIGH_PASS_OFF && lp == LOW_PASS_OFF) put(out, LOW_PASS, 37)
        if (hp == 30 && lp == 32) put(out, LOW_PASS, 33)
        return out
    }

    /**
     * High edge [l] (0 off, 1..6 = 2000..8000 Hz). Opening it while the low edge is open sets the
     * low edge to 500 Hz; a 2000 Hz high edge over a 2000 Hz low edge moves the low edge to 1500 Hz.
     */
    fun withLowPass(slice: ByteArray, l: Int): ByteArray? {
        require(l in 0..6)
        val out = slice.copyOf()
        val lp = LOW_PASS.first + l
        if (!put(out, LOW_PASS, lp)) return null
        val hp = id(out, HIGH_PASS)
        if (lp == LOW_PASS_OFF && hp == HIGH_PASS_OFF) put(out, HIGH_PASS, 26)
        if (lp == 32 && hp == 30) put(out, HIGH_PASS, 29)
        return out
    }

    /** [current] → [target] is a tinnitus change allowed on an aid of [side]. */
    fun sameFeature(current: Int, target: Int, side: Side): Boolean = when {
        current in VARIATION -> target in VARIATION
        current in HIGH_PASS -> target in HIGH_PASS
        current in LOW_PASS -> target in LOW_PASS
        current in NATURE -> target == NATURE_OFF || target in (if (side == Side.LEFT) NATURE_LEFT else NATURE_RIGHT)
        else -> false
    }

    /** [current] with each tinnitus slot set back to [fitted]'s id where that is a valid change for [side]. */
    fun restore(current: ByteArray, fitted: ByteArray, side: Side): ByteArray {
        require(current.size == FeatureSlice.SIZE && fitted.size == FeatureSlice.SIZE)
        val out = current.copyOf()
        for (i in out.indices) {
            val now = current[i].toInt() and 0xff
            val was = fitted[i].toInt() and 0xff
            if (now != was && sameFeature(now, was, side)) out[i] = was.toByte()
        }
        return out
    }

    /** Two aids' fitted tinnitus values agree (nature sounds compared by sound, not by side id). */
    fun fittedEqual(a: ByteArray?, b: ByteArray?): Boolean {
        if (a == null || b == null) return false
        return variation(a) == variation(b) && nature(a) == nature(b) && highPass(a) == highPass(b) && lowPass(a) == lowPass(b)
    }
}

package app.ihearyou.protocol

/**
 * The four adjustable feature controls of a top-tier aid: noise reduction, dynamic noise
 * reduction, speech focus and wind noise. Each control offers a fixed set of positions; a position
 * writes one id (two for speech focus: directionality and speech band) into the slot of the
 * program's feature slice that already holds that feature. Positions come from the slice as
 * fitted, so a control the audiologist did not fit is never offered.
 */
object SoundEnhancer {

    enum class Control { NOISE, DNR, FOCUS, WIND }

    /** [id] is written; [shows] lists the ids that select this position when read back. */
    enum class Position(val control: Control, val id: Int, val band: Int? = null, val shows: Set<Int> = setOf(id)) {
        NOISE_OFF(Control.NOISE, 12),
        NOISE_MILD(Control.NOISE, 14, shows = setOf(13, 14)),
        NOISE_MODERATE(Control.NOISE, 16, shows = setOf(15, 16)),
        NOISE_CONSIDERABLE(Control.NOISE, 17),
        NOISE_STRONG(Control.NOISE, 18),
        NOISE_AUTO(Control.NOISE, 20),
        DNR_MODERATE(Control.DNR, 56),
        DNR_CONSIDERABLE(Control.DNR, 57),
        DNR_STRONG(Control.DNR, 58),
        FOCUS_WIDE(Control.FOCUS, Focus.WIDE_MILD.beam, Focus.WIDE_MILD.band),
        FOCUS_MEDIUM(Control.FOCUS, Focus.MEDIUM.beam, Focus.MEDIUM.band),
        FOCUS_NARROW(Control.FOCUS, Focus.NARROW.beam, Focus.NARROW.band),
        FOCUS_AUTO(Control.FOCUS, Focus.AUTO.beam, Focus.AUTO.band),
        WIND_OFF(Control.WIND, 51),
        WIND_MILD(Control.WIND, 52),
        WIND_MEDIUM(Control.WIND, 53),
        WIND_STRONG(Control.WIND, 54),
    }

    private val NOISE_SLOT = 12..20

    private fun ids(slice: ByteArray) = slice.map { it.toInt() and 0xff }
    private fun first(slice: ByteArray, range: IntRange): Int? = ids(slice).firstOrNull { it in range }

    /** The control's current id (the directionality id for speech focus), or null without the slot. */
    fun idOf(control: Control, slice: ByteArray?): Int? {
        if (slice == null) return null
        return when (control) {
            Control.NOISE -> first(slice, NOISE_SLOT)
            Control.DNR -> first(slice, FeatureSlice.DNR)
            Control.FOCUS -> FeatureSlice.focus(slice)?.first
            Control.WIND -> first(slice, FeatureSlice.WIND)
        }
    }

    /**
     * Positions offered for [control], from the [fitted] slice. Noise reduction disabled at the
     * fitting offers nothing; "automatic" only where it was fitted. Speech focus needs both of its
     * slots and an input mode that supports it.
     */
    fun positions(control: Control, fitted: ByteArray?, inputMode: Int?): List<Position> {
        if (fitted == null) return emptyList()
        val of = Position.entries.filter { it.control == control }
        return when (control) {
            Control.NOISE -> when (val f = idOf(control, fitted)) {
                null, FeatureSlice.NOISE_REDUCTION_DISABLED -> emptyList()
                FeatureSlice.NOISE_REDUCTION_AUTO -> of
                else -> of - Position.NOISE_AUTO
            }
            Control.FOCUS -> if (FeatureSlice.hasSpeechFocus(fitted) && inputMode in SoundPresets.FOCUS_MODES) of else emptyList()
            else -> if (idOf(control, fitted) != null) of else emptyList()
        }
    }

    /** The position the [slice] is on, or null (for example dynamic noise reduction off). */
    fun selected(control: Control, slice: ByteArray?): Position? {
        val id = idOf(control, slice) ?: return null
        return Position.entries.firstOrNull { it.control == control && id in it.shows }
    }

    /**
     * [slice] with [position] written into the slot(s) holding that feature; unchanged when the
     * slot is missing, holds disabled noise reduction, or, on a CROS [transmitter], holds noise
     * reduction disabled or per environment.
     */
    fun apply(slice: ByteArray, position: Position, transmitter: Boolean = false): ByteArray {
        require(slice.size == FeatureSlice.SIZE)
        val out = slice.copyOf()
        val ids = ids(slice)
        fun replace(range: IntRange, id: Int) {
            val i = ids.indexOfFirst { it in range }
            if (i >= 0) out[i] = id.toByte()
        }
        when (position.control) {
            Control.NOISE -> {
                val now = first(slice, NOISE_SLOT) ?: return out
                if (now == FeatureSlice.NOISE_REDUCTION_DISABLED) return out
                if (transmitter && now in FeatureSlice.NOISE_REDUCTION_FIXED) return out
                replace(NOISE_SLOT, position.id)
            }
            Control.DNR -> replace(FeatureSlice.DNR, position.id)
            Control.FOCUS -> {
                if (!FeatureSlice.hasSpeechFocus(slice)) return out
                replace(FeatureSlice.DIRECTIONALITY, position.id)
                replace(FeatureSlice.SPEECH_BAND, position.band!!)
            }
            Control.WIND -> replace(FeatureSlice.WIND, position.id)
        }
        return out
    }

    /** Two aids' fitted values for [control] agree (speech focus compared as the id pair). */
    fun fittedEqual(control: Control, a: ByteArray?, b: ByteArray?): Boolean {
        if (a == null || b == null) return false
        if (control == Control.FOCUS) return FeatureSlice.focus(a) != null && FeatureSlice.focus(a) == FeatureSlice.focus(b)
        val x = idOf(control, a) ?: return false
        return x == idOf(control, b)
    }
}

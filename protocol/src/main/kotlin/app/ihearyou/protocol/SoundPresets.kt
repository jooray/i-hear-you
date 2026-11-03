package app.ihearyou.protocol

/**
 * Quick sound presets per program type. A preset is a fixed bass/middle/treble setting for the
 * active program, plus noise reduction, wind noise and speech focus values that only apply on
 * top-tier aids whose fitting has those features. The aid knows nothing about presets: it only
 * sees the program's gain record and feature slice change.
 */
object SoundPresets {

    enum class Kind { NOISE_FILTER, SPEECH_CLARITY, SPEECH_FOCUS, HEAR_EVERYONE, BASS_BOOST, TREBLE_BOOST }

    /** Which tiers may show a preset. */
    enum class Tiers { ALL, NOT_BUDGET, TOP_ONLY }

    /** Whether a preset depends on speech focus being available on the program. */
    enum class FocusRule { ANY, NEEDS_FOCUS, NEEDS_NO_FOCUS }

    data class Preset(
        val kind: Kind,
        val eq: Eq,
        val noiseReduction: Int? = null,
        val focus: Focus? = null,
        val wind: Int? = null,
        val tiers: Tiers = Tiers.ALL,
        val focusRule: FocusRule = FocusRule.ANY,
    ) {
        val touchesFeatures: Boolean get() = noiseReduction != null || focus != null || wind != null
    }

    // feature ids
    private const val NR_OFF = 12
    private const val NR_MILD = 14
    private const val NR_STRONG = 17
    private const val NR_VERY_STRONG = 18
    private const val WIND_MILD = 52
    private const val WIND_STRONG = 54

    private fun eq(bass: Int, middle: Int, treble: Int) = Eq(bass, middle, treble)

    private val noiseFilter = Preset(Kind.NOISE_FILTER, eq(-5, -5, -5), NR_VERY_STRONG, Focus.NARROW, WIND_STRONG)
    private val clarity = Preset(Kind.SPEECH_CLARITY, eq(-2, 4, 5), NR_VERY_STRONG, Focus.NARROW, WIND_STRONG)
    private val crowdFocus = Preset(
        Kind.SPEECH_FOCUS, eq(-2, 3, 5), NR_VERY_STRONG, Focus.NARROW, WIND_STRONG,
        tiers = Tiers.TOP_ONLY, focusRule = FocusRule.NEEDS_FOCUS,
    )
    private val crowdClarity = Preset(
        Kind.SPEECH_CLARITY, eq(-2, 3, 5), NR_VERY_STRONG, null, WIND_STRONG,
        focusRule = FocusRule.NEEDS_NO_FOCUS,
    )
    private val everyone = Preset(Kind.HEAR_EVERYONE, eq(5, 5, 5), NR_MILD, Focus.WIDE_LOW, WIND_MILD)
    private val bass = Preset(Kind.BASS_BOOST, eq(6, 0, -6))
    private val treble = Preset(Kind.TREBLE_BOOST, eq(-6, 0, 6))
    private val coilBass = Preset(Kind.BASS_BOOST, eq(6, 0, -6), NR_OFF, null, WIND_MILD)
    private val coilTreble = Preset(Kind.TREBLE_BOOST, eq(-6, 0, 6), NR_VERY_STRONG, null, WIND_STRONG)
    private val beamNoiseFilter = Preset(Kind.NOISE_FILTER, eq(0, -3, -3), NR_VERY_STRONG, tiers = Tiers.NOT_BUDGET)
    private val beamClarity = Preset(Kind.SPEECH_CLARITY, eq(3, 3, 3), NR_STRONG, tiers = Tiers.NOT_BUDGET)

    private val everyday = listOf(noiseFilter, clarity)
    private val crowd = listOf(noiseFilter, crowdFocus, crowdClarity, everyone)
    private val tone = listOf(bass, treble)
    private val coil = listOf(coilBass, coilTreble)
    private val beam = listOf(beamNoiseFilter, beamClarity)

    /** Input modes that use the two-aid beamformer; a hear-in-noise program then gets [beam]. */
    val BEAMFORMER_MODES = setOf(28, 37, 38)
    /** Input modes in which speech focus can work. */
    val FOCUS_MODES = setOf(10, 11)

    private fun candidates(environmentId: Int, inputMode: Int?): List<Preset> = when (environmentId) {
        1, 13 -> everyday // all-around
        2, 12, 52 -> crowd // restaurant, lecture, church
        15 -> if (inputMode in BEAMFORMER_MODES) beam else crowd // hear in noise
        3, 9, 50 -> tone // music, audio input, party
        4, 51 -> everyday // outdoor, traffic
        5, 100 -> everyday // phone held to the aid
        6, 7 -> coil // telecoil
        110, 111, 120, 121 -> tone // streaming
        else -> emptyList()
    }

    /**
     * While an accessory streamer or partner microphone is mixed in (stream type 3 or 4), the
     * active program offers bass and treble boost instead of its own presets.
     */
    fun forStream(streamType: Int?): List<Preset>? =
        if (StreamRules.mixIn(streamType) in 3..4) tone else null

    private fun tierAllows(tiers: Tiers, tier: PriceTier) = when (tiers) {
        Tiers.ALL -> true
        Tiers.NOT_BUDGET -> tier != PriceTier.BUDGET
        Tiers.TOP_ONLY -> tier == PriceTier.TOP
    }

    /**
     * Speech focus exists on a program only on a top-tier aid whose slice has both a directionality
     * and a speech-band slot, in an input mode that supports it.
     */
    fun speechFocusAvailable(tier: PriceTier, slice: ByteArray?, inputMode: Int?): Boolean =
        tier == PriceTier.TOP && FeatureSlice.hasSpeechFocus(slice) && inputMode in FOCUS_MODES

    /** The 0..3 presets for a program, in display order. */
    fun forProgram(environmentId: Int?, inputMode: Int?, tier: PriceTier, speechFocus: Boolean): List<Preset> {
        val env = environmentId ?: return emptyList()
        return candidates(env, inputMode).filter { p ->
            tierAllows(p.tiers, tier) && when (p.focusRule) {
                FocusRule.ANY -> true
                FocusRule.NEEDS_FOCUS -> speechFocus
                FocusRule.NEEDS_NO_FOCUS -> !speechFocus
            }
        }
    }

    /** Feature values apply only on top-tier aids. */
    fun featuresApply(tier: PriceTier): Boolean = tier == PriceTier.TOP

    /**
     * Whether [preset] matches one aid's live values: bass/middle/treble equal, and, when
     * [featuresApply], each feature value the preset sets equal to the slice's (a feature the
     * slice does not have is skipped).
     */
    fun matches(preset: Preset, eq: Eq, slice: ByteArray?, featuresApply: Boolean): Boolean {
        if (eq != preset.eq) return false
        if (!featuresApply || slice == null) return true
        preset.noiseReduction?.let { want -> FeatureSlice.noiseReduction(slice)?.let { if (it != want) return false } }
        preset.wind?.let { want -> FeatureSlice.wind(slice)?.let { if (it != want) return false } }
        preset.focus?.let { want -> FeatureSlice.focus(slice)?.let { if (it != want.beam to want.band) return false } }
        return true
    }

    /** The preset every aid matches, or null. With several candidates the last one wins. */
    fun active(presets: List<Preset>, aids: List<Pair<Eq, ByteArray?>>, featuresApply: Boolean): Preset? {
        if (aids.isEmpty()) return null
        return presets.lastOrNull { p -> aids.all { (eq, slice) -> matches(p, eq, slice, featuresApply) } }
    }
}

package app.ihearyou.domain

import kotlin.math.roundToInt

/**
 * The finder's reading: the phone's received signal strength from one aid, turned into a 0–100
 * bar and three words. It says "closer" or "farther", never a distance: signal strength depends
 * on the body, walls and the phone's own antenna as much as on metres.
 */
object Proximity {

    /** Weakest and strongest signal the bar shows; anything outside sits at an end. */
    const val MIN_DBM = -108
    const val MAX_DBM = -32

    /**
     * How often the finder asks for the signal while it is open. The Now screen asks every
     * [NORMAL_POLL_MS]; reading the signal is a link-layer request with no data to the aid.
     */
    const val FINDER_POLL_MS = 1_500L
    const val NORMAL_POLL_MS = 4_000L

    /** Below this the bar reads "far", from [CLOSE_PERCENT] "very close". */
    const val CLOSER_PERCENT = 35
    const val CLOSE_PERCENT = 70

    enum class Band { FAR, CLOSER, VERY_CLOSE }

    /** 0 is what Android reports when it has no reading; it counts as the weakest. */
    fun clamp(dbm: Int): Int = if (dbm == 0) MIN_DBM else dbm.coerceIn(MIN_DBM, MAX_DBM)

    fun percent(dbm: Int): Int = (100.0 * (clamp(dbm) - MIN_DBM) / (MAX_DBM - MIN_DBM)).roundToInt()

    fun band(percent: Int): Band = when {
        percent < CLOSER_PERCENT -> Band.FAR
        percent < CLOSE_PERCENT -> Band.CLOSER
        else -> Band.VERY_CLOSE
    }

    /** Gap between proximity ticks: slow when far, quick when close. */
    fun tickIntervalMs(percent: Int): Long {
        val p = percent.coerceIn(0, 100)
        return (1_400 - p * 12.5).roundToInt().toLong()
    }

    /** How long ago something was, in the unit the finder shows. */
    sealed class Ago {
        object JustNow : Ago()
        data class Minutes(val n: Int) : Ago()
        data class Hours(val n: Int) : Ago()
        data class Days(val n: Int) : Ago()
    }

    fun ago(now: Long, then: Long): Ago {
        val minutes = ((now - then).coerceAtLeast(0L) / 60_000L).toInt()
        return when {
            minutes < 1 -> Ago.JustNow
            minutes < 60 -> Ago.Minutes(minutes)
            minutes < 24 * 60 -> Ago.Hours(minutes / 60)
            else -> Ago.Days(minutes / (24 * 60))
        }
    }
}

package app.ihearyou.voice

/**
 * When a recording on a Bluetooth microphone should give up on it and move to the phone's own
 * microphone. Pure, so the timing rules are unit-tested.
 *
 * An LE Audio microphone needs 2 to 3.5 s after the recording starts before it delivers real
 * sound; until then reads are empty or exact zeros, and that is normal. Only after a generous
 * deadline without any real sample has the input failed. Once sound has arrived, a long run of
 * exact zeros or a stalled input means the link went away (the aid was switched off or taken out
 * of range): a quiet room never reads as exact zeros.
 */
object MicFallback {

    /** No real sample this long after the start: the Bluetooth input never came up. */
    const val FIRST_SOUND_MS = 8_000L

    /** After sound arrived: this much exact zero audio means the input died. */
    const val LOST_ZERO_MS = 3_000L

    /** After sound arrived: no data at all for this long means the input stalled. */
    const val LOST_STALL_MS = 2_000L

    enum class Reason(val log: String) {
        NO_SOUND("no sound from the Bluetooth microphone"),
        SILENCE("the Bluetooth microphone went silent"),
        STALLED("the Bluetooth microphone stopped delivering audio"),
        ERROR("the Bluetooth microphone failed"),
    }

    /**
     * @param sinceStartMs time since the recording started
     * @param soundSeen a non-zero sample has been read
     * @param zeroRunMs length of the current run of exact zero samples
     * @param stallMs time since the last read that returned data
     */
    fun check(sinceStartMs: Long, soundSeen: Boolean, zeroRunMs: Long, stallMs: Long): Reason? = when {
        !soundSeen -> if (sinceStartMs >= FIRST_SOUND_MS) Reason.NO_SOUND else null
        stallMs >= LOST_STALL_MS -> Reason.STALLED
        zeroRunMs >= LOST_ZERO_MS -> Reason.SILENCE
        else -> null
    }
}

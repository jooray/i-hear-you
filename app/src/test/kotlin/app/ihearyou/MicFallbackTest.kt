package app.ihearyou

import app.ihearyou.voice.MicFallback
import app.ihearyou.voice.MicFallback.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicFallbackTest {

    @Test
    fun linkSetupIsNotAFailure() {
        // LE Audio takes up to ~3.5 s: zeros and empty reads before the first sound are normal
        assertNull(MicFallback.check(sinceStartMs = 3_500, soundSeen = false, zeroRunMs = 3_500, stallMs = 3_500))
        assertNull(MicFallback.check(sinceStartMs = 7_900, soundSeen = false, zeroRunMs = 7_900, stallMs = 7_900))
    }

    @Test
    fun noSoundAtAllFallsBackAfterTheDeadline() {
        assertEquals(Reason.NO_SOUND, MicFallback.check(sinceStartMs = 8_000, soundSeen = false, zeroRunMs = 8_000, stallMs = 0))
    }

    @Test
    fun aidSwitchedOffMidRecording() {
        // sound arrived, then exact zeros: the link went away
        assertEquals(Reason.SILENCE, MicFallback.check(sinceStartMs = 20_000, soundSeen = true, zeroRunMs = 3_000, stallMs = 0))
        // or reads stopped returning data
        assertEquals(Reason.STALLED, MicFallback.check(sinceStartMs = 20_000, soundSeen = true, zeroRunMs = 0, stallMs = 2_000))
    }

    @Test
    fun shortGapsAfterSoundAreFine() {
        assertNull(MicFallback.check(sinceStartMs = 20_000, soundSeen = true, zeroRunMs = 2_900, stallMs = 1_900))
    }
}

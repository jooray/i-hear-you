package app.ihearyou

import app.ihearyou.domain.Proximity
import app.ihearyou.domain.Proximity.Band
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProximityTest {

    @Test
    fun percentSpansTheClampedRange() {
        assertEquals(0, Proximity.percent(-108))
        assertEquals(100, Proximity.percent(-32))
        assertEquals(50, Proximity.percent(-70))
        assertEquals(63, Proximity.percent(-60))
    }

    @Test
    fun outOfRangeValuesSitAtTheEnds() {
        assertEquals(0, Proximity.percent(-127))
        assertEquals(100, Proximity.percent(-10))
        assertEquals(100, Proximity.percent(5))
        // no reading counts as the weakest, not the strongest
        assertEquals(0, Proximity.percent(0))
    }

    @Test
    fun wordsFollowTheBar() {
        assertEquals(Band.FAR, Proximity.band(0))
        assertEquals(Band.FAR, Proximity.band(Proximity.CLOSER_PERCENT - 1))
        assertEquals(Band.CLOSER, Proximity.band(Proximity.CLOSER_PERCENT))
        assertEquals(Band.CLOSER, Proximity.band(Proximity.CLOSE_PERCENT - 1))
        assertEquals(Band.VERY_CLOSE, Proximity.band(Proximity.CLOSE_PERCENT))
        assertEquals(Band.VERY_CLOSE, Proximity.band(100))
        assertEquals(Band.FAR, Proximity.band(Proximity.percent(-90)))
        assertEquals(Band.CLOSER, Proximity.band(Proximity.percent(-65)))
        assertEquals(Band.VERY_CLOSE, Proximity.band(Proximity.percent(-45)))
    }

    @Test
    fun tickGetsQuickerAsTheSignalGrows() {
        assertEquals(1_400L, Proximity.tickIntervalMs(0))
        assertEquals(150L, Proximity.tickIntervalMs(100))
        var last = Long.MAX_VALUE
        for (p in 0..100 step 10) {
            val t = Proximity.tickIntervalMs(p)
            assertTrue(t < last)
            last = t
        }
        assertEquals(150L, Proximity.tickIntervalMs(140))
    }

    @Test
    fun findsFasterThanTheNowScreen() {
        assertTrue(Proximity.FINDER_POLL_MS < Proximity.NORMAL_POLL_MS)
        assertTrue(Proximity.FINDER_POLL_MS >= 1_000L)
    }

    @Test
    fun agoUnits() {
        val now = 10_000_000_000L
        assertEquals(Proximity.Ago.JustNow, Proximity.ago(now, now - 30_000))
        assertEquals(Proximity.Ago.JustNow, Proximity.ago(now, now + 5_000))
        assertEquals(Proximity.Ago.Minutes(12), Proximity.ago(now, now - 12 * 60_000L - 5_000))
        assertEquals(Proximity.Ago.Hours(3), Proximity.ago(now, now - 3 * 3_600_000L - 60_000))
        assertEquals(Proximity.Ago.Days(2), Proximity.ago(now, now - 50 * 3_600_000L))
    }
}

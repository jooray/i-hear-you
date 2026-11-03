package app.ihearyou

import app.ihearyou.button.ButtonEngine
import app.ihearyou.button.Gesture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ButtonEngineTest {

    // Times are passed explicitly; only the SHORT emission waits on the scheduler.

    @Test
    fun shortPressFiresAfterDoubleWindow() = runTest {
        val seen = mutableListOf<Gesture>()
        val engine = ButtonEngine(this, longMs = 400, doubleWindowMs = 350) { seen += it }
        engine.onPress(now = 0)
        engine.onRelease(now = 120)
        advanceTimeBy(349)
        assertEquals(emptyList(), seen)
        advanceTimeBy(2)
        assertEquals(listOf(Gesture.SHORT), seen)
    }

    @Test
    fun holdIsLong() = runTest {
        val seen = mutableListOf<Gesture>()
        val engine = ButtonEngine(this, longMs = 400, doubleWindowMs = 350) { seen += it }
        engine.onPress(now = 0)
        engine.onRelease(now = 650)
        runCurrent()
        assertEquals(listOf(Gesture.LONG), seen)
        advanceTimeBy(1_000)
        assertEquals(listOf(Gesture.LONG), seen)
    }

    @Test
    fun twoQuickPressesAreDoubleNotTwoShorts() = runTest {
        val seen = mutableListOf<Gesture>()
        val engine = ButtonEngine(this, longMs = 400, doubleWindowMs = 350) { seen += it }
        engine.onPress(now = 0)
        engine.onRelease(now = 100)
        advanceTimeBy(200)
        engine.onPress(now = 300)
        engine.onRelease(now = 400)
        advanceTimeBy(1_000)
        assertEquals(listOf(Gesture.DOUBLE), seen)
    }

    @Test
    fun slowSecondPressIsTwoShorts() = runTest {
        val seen = mutableListOf<Gesture>()
        val engine = ButtonEngine(this, longMs = 400, doubleWindowMs = 350) { seen += it }
        engine.onPress(now = 0)
        engine.onRelease(now = 100)
        advanceTimeBy(500)
        engine.onPress(now = 600)
        engine.onRelease(now = 700)
        advanceTimeBy(500)
        assertEquals(listOf(Gesture.SHORT, Gesture.SHORT), seen)
    }

    @Test
    fun withoutDoubleMappedShortFiresOnRelease() = runTest {
        val seen = mutableListOf<Gesture>()
        val engine = ButtonEngine(this, longMs = 400, doubleWindowMs = 1_300, doubleEnabled = { false }) { seen += it }
        engine.onPress(now = 0)
        engine.onRelease(now = 120)
        runCurrent()
        assertEquals(listOf(Gesture.SHORT), seen)
    }

    @Test
    fun aidPacedDoublePressIsDouble() = runTest {
        // measured on a Nexia: the second press arrives ~1.1 s after the first
        val seen = mutableListOf<Gesture>()
        val engine = ButtonEngine(this, longMs = 400, doubleWindowMs = 1_300) { seen += it }
        engine.onPress(now = 0)
        engine.onRelease(now = 150)
        advanceTimeBy(960)
        engine.onPress(now = 1_110)
        engine.onRelease(now = 1_250)
        advanceTimeBy(2_000)
        assertEquals(listOf(Gesture.DOUBLE), seen)
    }

    @Test
    fun releaseWithoutPressIsIgnored() = runTest {
        val seen = mutableListOf<Gesture>()
        val engine = ButtonEngine(this) { seen += it }
        engine.onRelease(now = 50)
        advanceTimeBy(1_000)
        assertEquals(emptyList(), seen)
    }
}

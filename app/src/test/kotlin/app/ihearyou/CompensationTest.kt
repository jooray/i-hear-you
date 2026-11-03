package app.ihearyou

import app.ihearyou.domain.ButtonAction
import app.ihearyou.domain.Compensation
import app.ihearyou.domain.Compensation.Snapshot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CompensationTest {

    private val before = Snapshot(program = 1, attenuation = byteArrayOf(1, 0xAA.toByte()))
    private val programs = listOf(0, 1, 2, 3)

    @Test
    fun mediaActionsRestoreThePrePressState() {
        for (a in listOf(ButtonAction.PLAY_PAUSE, ButtonAction.NEXT_TRACK, ButtonAction.PREVIOUS_TRACK, ButtonAction.NONE)) {
            val t = Compensation.target(before, a, programs)
            assertEquals(1, t.program, "$a")
            assertContentEquals(before.attenuation, t.attenuation, "$a")
        }
    }

    @Test
    fun programStepsFromPrePressProgramAndWraps() {
        assertEquals(2, Compensation.target(before, ButtonAction.PROGRAM_NEXT, programs).program)
        assertEquals(0, Compensation.target(before, ButtonAction.PROGRAM_PREVIOUS, programs).program)
        val last = before.copy(program = 3)
        assertEquals(0, Compensation.target(last, ButtonAction.PROGRAM_NEXT, programs).program)
    }

    @Test
    fun programStepSkipsGapsInTheFittedList() {
        // only programs 0 and 2 fitted
        val t = Compensation.target(before.copy(program = 0), ButtonAction.PROGRAM_NEXT, listOf(0, 2))
        assertEquals(2, t.program)
    }

    @Test
    fun volumeStepsFromPrePressLevelAndClamps() {
        val up = Compensation.target(before, ButtonAction.VOLUME_UP, programs)
        assertEquals(0xAA + Compensation.VOLUME_STEP, up.attenuation!![1].toInt() and 0xff)
        assertEquals(1, up.attenuation!![0].toInt(), "program byte kept")
        val loud = before.copy(attenuation = byteArrayOf(1, 0xF8.toByte()))
        assertEquals(255, Compensation.target(loud, ButtonAction.VOLUME_UP, programs).attenuation!![1].toInt() and 0xff)
        val quiet = before.copy(attenuation = byteArrayOf(1, 5))
        assertEquals(0, Compensation.target(quiet, ButtonAction.VOLUME_DOWN, programs).attenuation!![1].toInt() and 0xff)
    }

    @Test
    fun unknownStateStaysUnknown() {
        val empty = Snapshot(null, null)
        assertEquals(empty, Compensation.target(empty, ButtonAction.PROGRAM_NEXT, programs))
        assertEquals(empty, Compensation.target(empty, ButtonAction.VOLUME_UP, programs))
    }
}

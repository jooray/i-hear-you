package app.ihearyou.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgramNamesTest {

    @Test
    fun parsesLittleEndianIdsAndTreatsZeroAsAllAround() {
        val blob = byteArrayOf(0, 0, 0, 0, 15, 0, 0, 0, 3, 0, 0, 0, 4, 0, 0, 0)
        assertEquals(listOf(1, 15, 3, 4), ProgramNames.parseEnvironmentIds(blob))
        assertEquals(emptyList(), ProgramNames.parseEnvironmentIds(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun stockNamesAreTranslatedCustomNamesKept() {
        // the tested pair: a Slovak/Czech mix written by the fitting software
        assertEquals(ProgramNames.Kind.ALL_AROUND, ProgramNames.stockKind(1, "Všeobecný"))
        assertEquals(ProgramNames.Kind.HEAR_IN_NOISE, ProgramNames.stockKind(15, "V hluku"))
        assertEquals(ProgramNames.Kind.OUTDOOR, ProgramNames.stockKind(4, "Venku"))
        assertNull(ProgramNames.stockKind(2, "Kostol"))
        assertNull(ProgramNames.stockKind(99, "Music"))
    }
}

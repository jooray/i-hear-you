package app.ihearyou

import app.ihearyou.domain.Favourite
import app.ihearyou.domain.Favourites
import app.ihearyou.domain.PairSnapshot
import app.ihearyou.protocol.Eq
import app.ihearyou.protocol.SoundEnhancer.Position
import app.ihearyou.protocol.SoundPresets.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FavouritesTest {

    private val now = PairSnapshot(
        program = 1,
        volumes = mapOf("L-SERIAL" to 0xAA, "R-SERIAL" to 0xB4),
        eq = Eq(2, 0, -1),
        preset = null,
        enhancer = listOf(Position.NOISE_MILD, Position.WIND_OFF),
    )

    private val full = Favourite(
        id = "a", name = "Restaurant · 14:32", program = 1,
        volumes = mapOf("L-SERIAL" to 0xAA, "R-SERIAL" to 0xB4),
        eq = Eq(2, 0, -1), preset = null,
        enhancer = listOf(Position.NOISE_MILD, Position.WIND_OFF), createdAt = 1_700_000_000_000L,
    )

    @Test
    fun jsonRoundTrip() {
        val list = listOf(
            full,
            Favourite("b", "Quiet „večer“ \"x\"", program = null, volumes = mapOf("AA:BB" to 0), eq = null, preset = Kind.SPEECH_CLARITY),
            Favourite("c", "Nothing", program = 0, volumes = emptyMap(), eq = Eq.FLAT, preset = null),
        )
        assertEquals(list, Favourites.fromJson(Favourites.toJson(list)))
    }

    @Test
    fun badJsonGivesWhatCanBeRead() {
        assertEquals(emptyList(), Favourites.fromJson(null))
        assertEquals(emptyList(), Favourites.fromJson("not json"))
        val text = """[{"id":"x","name":"ok","program":2,"preset":"GONE","enhancer":["NOISE_OFF","GONE"]},{"name":"no id"}]"""
        val read = Favourites.fromJson(text)
        assertEquals(1, read.size)
        assertEquals(2, read[0].program)
        assertEquals(null, read[0].preset)
        assertEquals(listOf(Position.NOISE_OFF), read[0].enhancer)
    }

    @Test
    fun captureMatchesItself() {
        val f = Favourites.capture("id", "  Name  ", now, 5L)
        assertEquals("Name", f.name)
        assertTrue(Favourites.matches(f, now))
        assertTrue(Favourites.matches(full, now))
    }

    @Test
    fun anyDifferenceIsNoMatch() {
        assertFalse(Favourites.matches(full, now.copy(program = 2)))
        assertFalse(Favourites.matches(full, now.copy(volumes = now.volumes + ("R-SERIAL" to 0xB5))))
        assertFalse(Favourites.matches(full, now.copy(eq = Eq(2, 0, 0))))
        assertFalse(Favourites.matches(full, now.copy(enhancer = listOf(Position.NOISE_MILD))))
    }

    @Test
    fun unknownCurrentValuesNeverMatch() {
        // an aid out of range, or sound unknown while charging
        assertFalse(Favourites.matches(full, now.copy(volumes = mapOf("L-SERIAL" to 0xAA))))
        assertFalse(Favourites.matches(full, now.copy(eq = null)))
        assertFalse(Favourites.matches(full, now.copy(program = null)))
    }

    @Test
    fun presetIsComparedInsteadOfEq() {
        val f = full.copy(preset = Kind.NOISE_FILTER, eq = Eq(-5, -5, -5), enhancer = emptyList())
        assertFalse(Favourites.matches(f, now))
        assertTrue(Favourites.matches(f, now.copy(preset = Kind.NOISE_FILTER, eq = Eq(-5, -5, -5))))
    }

    @Test
    fun partsNotSavedAreNotCompared() {
        val levelsOnly = Favourite("v", "Levels", program = null, volumes = now.volumes, eq = null, preset = null)
        assertTrue(Favourites.matches(levelsOnly, now.copy(program = 3, eq = null)))
        val empty = Favourite("e", "Empty", program = null, volumes = emptyMap(), eq = null, preset = null)
        assertFalse(Favourites.matches(empty, now))
    }

    @Test
    fun aidKeyPrefersTheSerial() {
        assertEquals("1234", Favourites.aidKey("1234", "aa:bb"))
        assertEquals("AA:BB", Favourites.aidKey(" ", "aa:bb"))
        assertEquals("AA:BB", Favourites.aidKey(null, "aa:bb"))
    }
}

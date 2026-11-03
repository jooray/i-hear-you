package app.ihearyou.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToneTest {

    private fun hex(s: String): ByteArray = s.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
    private fun hexOf(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    private val flat = hex("36 00 00 00 00 00 00 00 00 00 00 00 00")

    // ---------------------------------------------------------------- tier

    @Test
    fun tierFromProductString() {
        assertEquals(PriceTier.TOP, PriceTier.parse("RS.D3.Top.X1"))
        assertEquals(PriceTier.PLUS, PriceTier.parse("RS.D3.Pls.X1"))
        assertEquals(PriceTier.BASIC, PriceTier.parse("RS.D3.Bas.X1"))
        assertEquals(PriceTier.BASIC, PriceTier.parse("BT.D3.Bsp"))
        assertEquals(PriceTier.BUDGET, PriceTier.parse("RS.D3.Bdg.X1"))
        assertEquals(PriceTier.UNKNOWN, PriceTier.parse("RS.D3.Zzz.X1"))
        assertEquals(PriceTier.UNKNOWN, PriceTier.parse("RS.D3"))
        assertEquals(PriceTier.UNKNOWN, PriceTier.parse("RS.D3.Toppp"))
        assertEquals(PriceTier.UNKNOWN, PriceTier.parse(null as String?))
        assertEquals(PriceTier.TOP, PriceTier.parse("RS.D3.Top.X1\u0000\u0000".toByteArray()))
    }

    // ---------------------------------------------------------------- gain record

    @Test
    fun readsSignedNibbles() {
        assertEquals(Eq.FLAT, GainRecord.read(flat))
        assertEquals(Eq(-5, -5, -5), GainRecord.read(hex("36 0B BB 00 00 00 00 00 00 00 00 00 00")))
        assertEquals(Eq(-2, 4, 5), GainRecord.read(hex("36 0E 45 00 00 00 00 00 00 00 00 00 00")))
        assertEquals(Eq(6, 0, -6), GainRecord.read(hex("36 06 0A 00 00 00 00 00 00 00 00 00 00")))
    }

    @Test
    fun writeChangesOnlyEqBytes() {
        assertEquals("00 36 0B BB 00 00 00 00 00 00 00 00 00 00", hexOf(GainRecord.write(0, flat, Eq(-5, -5, -5))))
        assertEquals("01 36 0E 45 00 00 00 00 00 00 00 00 00 00", hexOf(GainRecord.write(1, flat, Eq(-2, 4, 5))))
        assertEquals("02 36 0E 35", hexOf(GainRecord.write(2, flat, Eq(-2, 3, 5)).copyOf(4)))
        assertEquals("36 05 55", hexOf(GainRecord.withEq(flat, Eq(5, 5, 5)).copyOf(3)))
        assertEquals("36 00 DD", hexOf(GainRecord.withEq(flat, Eq(0, -3, -3)).copyOf(3)))
        assertEquals("36 0A 06", hexOf(GainRecord.withEq(flat, Eq(-6, 0, 6)).copyOf(3)))
        // band bytes and a non-default crossover are copied exactly
        val fitted = hex("25 03 21 F1 12 E2 00 7F 80 01 FE 10 0F")
        val out = GainRecord.withEq(fitted, Eq(-1, 2, -3))
        assertEquals("25 0F 2D F1 12 E2 00 7F 80 01 FE 10 0F", hexOf(out))
        assertEquals(Eq(-1, 2, -3), GainRecord.read(out))
    }

    @Test
    fun clampsAndFixesZeroCrossover() {
        val zero = ByteArray(13)
        val out = GainRecord.withEq(zero, Eq(-9, 9, 7))
        assertEquals(0x36, out[0].toInt() and 0xff)
        assertEquals(Eq(-6, 6, 6), GainRecord.read(out))
        assertEquals(14, GainRecord.write(3, zero, Eq.FLAT).size)
    }

    @Test
    fun slicesBlob() {
        val blob = ByteArray(52) { if (it % 13 == 0) 0x36 else 0 }
        assertEquals(4, GainRecord.slices(blob).size)
        assertTrue(GainRecord.slices(ByteArray(50)).isEmpty())
        assertTrue(GainRecord.slices(null).isEmpty())
    }

    // ---------------------------------------------------------------- feature slice

    @Test
    fun appliesOnlyToSlotsOfThatFeature() {
        // speech band, directionality, noise reduction, wind, tinnitus, unused…
        val slice = hex("02 07 10 35 15 FF FF FF FF")
        val out = FeatureSlice.apply(slice, noiseReduction = 18, wind = 54, focus = Focus.NARROW)
        assertEquals("00 04 12 36 15 FF FF FF FF", hexOf(out))
        assertEquals("03 00 04 12 36 15 FF FF FF FF", hexOf(FeatureSlice.write(3, out)))
    }

    @Test
    fun neverFillsUnusedOrTinnitusSlots() {
        val slice = hex("FF FF 15 1A 26 32 FF FF FF")
        assertContentEquals(slice, FeatureSlice.apply(slice, noiseReduction = 18, wind = 54, focus = Focus.WIDE_LOW))
    }

    @Test
    fun keepsTransmitterNoiseReduction() {
        val slice = hex("13 33 FF FF FF FF FF FF FF")
        assertEquals("13 36 FF FF FF FF FF FF FF", hexOf(FeatureSlice.apply(slice, 18, 54, null)))
        val other = hex("14 FF FF FF FF FF FF FF FF")
        assertContentEquals(other, FeatureSlice.apply(other, 12, null, null))
    }

    @Test
    fun speechFocusNeedsBothSlots() {
        val onlyBeam = hex("07 10 FF FF FF FF FF FF FF")
        assertEquals("07 12 FF FF FF FF FF FF FF", hexOf(FeatureSlice.apply(onlyBeam, 18, null, Focus.NARROW)))
        assertFalse(FeatureSlice.hasSpeechFocus(onlyBeam))
        val both = hex("07 01 FF FF FF FF FF FF FF")
        assertEquals("06 03 FF FF FF FF FF FF FF", hexOf(FeatureSlice.apply(both, null, null, Focus.WIDE_LOW)))
    }

    @Test
    fun restorePutsBackManagedSlotsOnly() {
        val saved = hex("02 07 10 35 15 FF FF FF FF")
        val now = hex("00 04 12 36 16 FF FF FF FF")
        assertEquals("02 07 10 35 16 FF FF FF FF", hexOf(FeatureSlice.restore(now, saved)))
        // a slot whose feature differs from the saved one is left alone
        val moved = hex("12 FF FF FF FF FF FF FF FF")
        val savedOther = hex("35 FF FF FF FF FF FF FF FF")
        assertContentEquals(moved, FeatureSlice.restore(moved, savedOther))
    }

    // ---------------------------------------------------------------- presets

    private fun kinds(list: List<SoundPresets.Preset>) = list.map { it.kind }

    @Test
    fun presetsPerProgramType() {
        val k = SoundPresets.Kind.entries
        assertEquals(listOf(k[0], k[1]), kinds(SoundPresets.forProgram(1, 10, PriceTier.PLUS, false)))
        assertEquals(
            listOf(SoundPresets.Kind.BASS_BOOST, SoundPresets.Kind.TREBLE_BOOST),
            kinds(SoundPresets.forProgram(3, null, PriceTier.UNKNOWN, false)),
        )
        assertEquals(
            listOf(SoundPresets.Kind.NOISE_FILTER, SoundPresets.Kind.SPEECH_CLARITY),
            kinds(SoundPresets.forProgram(4, null, PriceTier.BASIC, false)),
        )
        assertTrue(SoundPresets.forProgram(99, null, PriceTier.TOP, true).isEmpty())
        assertTrue(SoundPresets.forProgram(null, null, PriceTier.TOP, true).isEmpty())
    }

    @Test
    fun hearInNoiseByInputMode() {
        val crowd = SoundPresets.forProgram(15, 10, PriceTier.PLUS, false)
        assertEquals(
            listOf(SoundPresets.Kind.NOISE_FILTER, SoundPresets.Kind.SPEECH_CLARITY, SoundPresets.Kind.HEAR_EVERYONE),
            kinds(crowd),
        )
        assertEquals(Eq(-2, 3, 5), crowd[1].eq)
        assertNull(crowd[1].focus)
        val beam = SoundPresets.forProgram(15, 28, PriceTier.PLUS, false)
        assertEquals(listOf(Eq(0, -3, -3), Eq(3, 3, 3)), beam.map { it.eq })
        assertTrue(SoundPresets.forProgram(15, 37, PriceTier.BUDGET, false).isEmpty())
    }

    @Test
    fun speechFocusOnlyOnTopWithSlots() {
        val slice = hex("01 07 10 35 FF FF FF FF FF")
        assertTrue(SoundPresets.speechFocusAvailable(PriceTier.TOP, slice, 10))
        assertFalse(SoundPresets.speechFocusAvailable(PriceTier.PLUS, slice, 10))
        assertFalse(SoundPresets.speechFocusAvailable(PriceTier.TOP, slice, 28))
        assertFalse(SoundPresets.speechFocusAvailable(PriceTier.TOP, hex("07 10 FF FF FF FF FF FF FF"), 10))
        val top = SoundPresets.forProgram(2, 10, PriceTier.TOP, true)
        assertEquals(
            listOf(SoundPresets.Kind.NOISE_FILTER, SoundPresets.Kind.SPEECH_FOCUS, SoundPresets.Kind.HEAR_EVERYONE),
            kinds(top),
        )
        // focus is never offered off the top tier, even if asked for
        assertEquals(
            listOf(SoundPresets.Kind.NOISE_FILTER, SoundPresets.Kind.HEAR_EVERYONE),
            kinds(SoundPresets.forProgram(2, 10, PriceTier.PLUS, true)),
        )
    }

    @Test
    fun activeFromLiveValues() {
        val presets = SoundPresets.forProgram(1, 10, PriceTier.PLUS, false)
        assertEquals(
            SoundPresets.Kind.NOISE_FILTER,
            SoundPresets.active(presets, listOf(Eq(-5, -5, -5) to null, Eq(-5, -5, -5) to null), false)?.kind,
        )
        assertNull(SoundPresets.active(presets, listOf(Eq(-5, -5, -5) to null, Eq.FLAT to null), false))
        assertNull(SoundPresets.active(presets, emptyList(), false))
        // with features applying, the slice must agree too
        val slice = hex("02 07 10 35 FF FF FF FF FF")
        val nf = presets[0]
        assertFalse(SoundPresets.matches(nf, nf.eq, slice, featuresApply = true))
        assertTrue(SoundPresets.matches(nf, nf.eq, slice, featuresApply = false))
        val applied = FeatureSlice.apply(slice, nf.noiseReduction, nf.wind, nf.focus)
        assertTrue(SoundPresets.matches(nf, nf.eq, applied, featuresApply = true))
        // a slice without those features only needs the EQ
        assertTrue(SoundPresets.matches(nf, nf.eq, hex("15 FF FF FF FF FF FF FF FF"), featuresApply = true))
    }

    @Test
    fun soundHandlesWritableOnlyOnProtocol5() {
        for (p in Platform.entries) {
            val on = p == Platform.V5_0 || p == Platform.V5_1
            assertEquals(on, Handles.GAIN_DATA in p.writable, p.label)
            assertEquals(on, Handles.STANDARD_FEATURES in p.writable, p.label)
            assertFalse(Handles.ALL_GAIN_DATA in p.writable, p.label)
            assertFalse(Handles.ALL_STANDARD_FEATURES in p.writable, p.label)
            assertFalse(Handles.LAST_FITTED_ID in p.writable, p.label)
            assertFalse(37 in p.writable, p.label)
        }
    }
}

class SoundWriteGuardTest {
    private fun hex(s: String): ByteArray = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun gainGuard() {
        val cur = hex("36 00 00 01 02 03 04 05 06 07 08 09 0A")
        assertTrue(SoundWriteGuard.gainOk(cur, GainRecord.withEq(cur, Eq(-5, -5, -5))))
        assertFalse(SoundWriteGuard.gainOk(cur, hex("36 00 00 01 02 03 04 05 06 07 08 09 0B")))
        assertFalse(SoundWriteGuard.gainOk(cur, hex("25 00 00 01 02 03 04 05 06 07 08 09 0A")))
        assertFalse(SoundWriteGuard.gainOk(cur, hex("36 07 00 01 02 03 04 05 06 07 08 09 0A"))) // +7
        assertFalse(SoundWriteGuard.gainOk(cur, hex("36 10 00 01 02 03 04 05 06 07 08 09 0A")))
        val zero = ByteArray(13)
        assertTrue(SoundWriteGuard.gainOk(zero, GainRecord.withEq(zero, Eq.FLAT)))
    }

    @Test
    fun featureGuard() {
        val cur = hex("02 07 10 35 15 FF FF FF 13")
        assertTrue(SoundWriteGuard.featuresOk(cur, FeatureSlice.apply(cur, 18, 54, Focus.NARROW)))
        assertFalse(SoundWriteGuard.featuresOk(cur, hex("02 07 10 35 16 FF FF FF 13"))) // tinnitus
        assertFalse(SoundWriteGuard.featuresOk(cur, hex("02 07 10 35 15 12 FF FF 13"))) // unused slot
        assertFalse(SoundWriteGuard.featuresOk(cur, hex("02 07 10 35 15 FF FF FF 12"))) // transmitter
        assertFalse(SoundWriteGuard.featuresOk(cur, hex("02 07 36 35 15 FF FF FF 13"))) // other feature
    }
}

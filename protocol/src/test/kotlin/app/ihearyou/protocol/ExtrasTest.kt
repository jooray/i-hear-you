package app.ihearyou.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hex(s: String): ByteArray = s.split(' ').filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
private fun hexOf(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

class StreamVolumeTest {

    @Test
    fun stepsLikeTheAidButton() {
        // 170 is position 9; one step up is position 10, one down position 8
        assertEquals(9, StreamVolume.step(170))
        assertEquals(10, StreamVolume.step(194))
        assertEquals(10, StreamVolume.step(StreamVolume.stepped(170, 1)))
        assertEquals(8, StreamVolume.step(StreamVolume.stepped(170, -1)))
        // stepping back lands on the fitted default, not on the floor of its position
        assertEquals(170, StreamVolume.stepped(194, -1, default = 170))
        assertEquals(194, StreamVolume.stepped(170, 1, default = 194))
        // ends: never mute, never past the top
        assertEquals(1, StreamVolume.stepped(10, -5))
        assertEquals(1, StreamVolume.stepped(0, 0))
        assertEquals(255, StreamVolume.stepped(250, 3))
    }

    @Test
    fun payloadAndParse() {
        assertEquals("02 80", hexOf(StreamVolume.payload(2, 0x80)))
        assertEquals("00 00", hexOf(StreamVolume.payload(0, StreamVolume.MUTE)))
        assertEquals(0xAA, StreamVolume.attenuation(hex("01 AA")))
        assertEquals(0x40, StreamVolume.attenuation(hex("40")))
        assertNull(StreamVolume.attenuation(ByteArray(0)))
        assertNull(StreamVolume.attenuation(null))
        assertEquals(0x80, StreamVolume.micDefault(hex("80 AA")))
        assertEquals(0xAA, StreamVolume.streamDefault(hex("80 AA")))
        assertNull(StreamVolume.streamDefault(hex("80")))
    }

    @Test
    fun unmute() {
        assertEquals(0x90, StreamVolume.unmuteTo(0x90, 0xAA))
        assertEquals(0xAA, StreamVolume.unmuteTo(null, 0xAA))
        assertEquals(0xAA, StreamVolume.unmuteTo(0, 0xAA))
        assertEquals(170, StreamVolume.unmuteTo(null, null))
        assertEquals(170, StreamVolume.unmuteTo(null, 0))
    }

    @Test
    fun steps() {
        assertEquals(1, StreamVolume.attenuationOf(1))
        assertEquals(255, StreamVolume.attenuationOf(13))
        assertEquals(128, StreamVolume.attenuationOf(7))
        for (s in 1..13) assertEquals(s, StreamVolume.step(StreamVolume.attenuationOf(s)))
        assertEquals(1, StreamVolume.step(0))
        assertEquals(13, StreamVolume.step(255))
    }

    @Test
    fun whenShown() {
        // telecoil and audio input programs, by category or by program type
        assertTrue(StreamRules.streamVolumeShown(200, null, false, 0, 0))
        assertTrue(StreamRules.streamVolumeShown(null, 7, false, 0, 0))
        assertTrue(StreamRules.streamVolumeShown(255, null, false, 0, 0))
        assertFalse(StreamRules.streamVolumeShown(255, null, true, 0, 0)) // tinnitus program
        // an older streamer program, only while connected
        assertTrue(StreamRules.streamVolumeShown(201, null, false, 0, 4))
        assertFalse(StreamRules.streamVolumeShown(201, null, false, 0, 0))
        // accessory, partner mic, broadcast mixed in while searching or streaming
        for (t in 3..5) {
            assertTrue(StreamRules.streamVolumeShown(1, 1, false, t, 4))
            assertTrue(StreamRules.streamVolumeShown(1, 1, false, t, 1))
            assertFalse(StreamRules.streamVolumeShown(1, 1, false, t, 2))
            assertFalse(StreamRules.streamVolumeShown(1, 1, true, t, 4))
        }
        // phone over LE audio or the bridge: the phone sets the level, even in a telecoil program
        assertFalse(StreamRules.streamVolumeShown(1, 1, false, 1, 4))
        assertFalse(StreamRules.streamVolumeShown(200, 6, false, 2, 4))
        assertFalse(StreamRules.streamVolumeShown(1, 1, false, 0, 4))
        assertFalse(StreamRules.streamVolumeShown(1, 1, false, 9, 4))
        // surroundings label
        assertTrue(StreamRules.surroundings(1, 1, false, 1, 4))
        assertTrue(StreamRules.surroundings(200, 6, false, 0, 0))
        assertFalse(StreamRules.surroundings(1, 1, true, 1, 4))
        assertFalse(StreamRules.surroundings(1, 1, false, 0, 0))
    }
}

class MixInButtonsTest {

    @Test
    fun attenuationMath() {
        for (d in listOf(0, 64, 128, 170, 255)) assertEquals(1, MixInButtons.attenuation(MixInButtons.Kind.FOCUS, d))
        assertEquals(240, MixInButtons.attenuation(MixInButtons.Kind.HEAR_ALL, 170))
        assertEquals(233, MixInButtons.attenuation(MixInButtons.Kind.HEAR_ALL, 128))
        for (d in 0..255) assertEquals((d + (255 - d) * 5f / 6f).toInt(), MixInButtons.attenuation(MixInButtons.Kind.HEAR_ALL, d))
    }

    @Test
    fun offeredAndActive() {
        for (t in listOf(1, 2, 5)) assertEquals(MixInButtons.Kind.entries, MixInButtons.offered(t))
        for (t in listOf(null, 0, 3, 4, 7)) assertTrue(MixInButtons.offered(t).isEmpty())
        assertEquals(MixInButtons.Kind.FOCUS, MixInButtons.active(listOf(170 to 1, 128 to 15)))
        assertEquals(MixInButtons.Kind.HEAR_ALL, MixInButtons.active(listOf(170 to 240, 128 to 233)))
        assertNull(MixInButtons.active(listOf(170 to 1, 128 to 233)))
        assertNull(MixInButtons.active(listOf(170 to 0))) // muted is not "focus"
        assertNull(MixInButtons.active(emptyList()))
        assertNotNull(SoundPresets.forStream(3))
        assertEquals(listOf(SoundPresets.Kind.BASS_BOOST, SoundPresets.Kind.TREBLE_BOOST), SoundPresets.forStream(4)!!.map { it.kind })
        assertEquals(Eq(6, 0, -6), SoundPresets.forStream(4)!![0].eq)
        assertNull(SoundPresets.forStream(1))
        assertNull(SoundPresets.forStream(0))
    }
}

class MelodyAndPlatformTest {

    @Test
    fun beepWhitelist() {
        assertContentEquals(hex("05 00"), Melody.confirm(Platform.V5_1))
        assertContentEquals(hex("05 00"), Melody.confirm(Platform.V4))
        assertContentEquals(hex("05"), Melody.confirm(Platform.V1))
        for (p in listOf(Platform.V4, Platform.V5_0, Platform.V5_1)) {
            assertTrue(p.writeAllowed(Handles.MELODY, hex("05 00")))
            for (v in 0..255) if (v != 5) assertFalse(p.writeAllowed(Handles.MELODY, byteArrayOf(v.toByte(), 0)), "$v")
            assertFalse(p.writeAllowed(Handles.MELODY, hex("05")))
            assertFalse(p.writeAllowed(Handles.MELODY, hex("05 01")))
            assertFalse(p.writeAllowed(Handles.MELODY, hex("05 00 00")))
            assertTrue(p.writeAllowed(Handles.STREAMER_ATTENUATION, hex("00 80")))
            assertFalse(p.writeAllowed(Handles.STREAMER_ATTENUATION, hex("80")))
        }
    }

    @Test
    fun writableSets() {
        for (p in Platform.entries) {
            val on = p == Platform.V4 || p == Platform.V5_0 || p == Platform.V5_1
            assertEquals(on, Handles.STREAMER_ATTENUATION in p.writable, p.label)
            assertEquals(on, Handles.MELODY in p.writable, p.label)
            assertFalse(p.writeAllowed(Handles.MELODY, Melody.confirm(p)) && !on, p.label)
            // the safety list stays read-only everywhere
            for (h in listOf(16, 19, 21, 31, 67, 72, 37, 38, 28, 36, 26, 74, 49, 53, 51, 23, 14)) {
                assertFalse(h in p.writable, "${p.label}: $h")
            }
            val expected = mutableSetOf(5, 8, 12)
            p.tapConfig?.let { expected += it }
            if (on) expected += setOf(6, 22, 27, 35)
            assertEquals(expected, p.writable, p.label)
        }
        assertNull(Platform.V1.streamType)
        assertEquals(31, Platform.V2.streamType)
        assertEquals(31, Platform.V5_1.streamType)
        assertEquals(37, Platform.V5_1.receiverCheck)
        assertEquals(37, Platform.V4.receiverCheck)
        assertNull(Platform.V3.receiverCheck)
        assertEquals(8, Platform.V5_1.criticalAlgoLength)
        assertEquals(22, Platform.V4.criticalAlgoLength)
    }
}

class SoundEnhancerTest {

    // focus band 2, directionality 7, noise reduction 16, wind 53, DNR 57, rest unused
    private val fitted = hex("02 07 10 35 39 FF FF FF FF")

    @Test
    fun positions() {
        val noise = SoundEnhancer.positions(SoundEnhancer.Control.NOISE, fitted, 10)
        assertEquals(listOf(12, 14, 16, 17, 18), noise.map { it.id })
        assertEquals(
            listOf(12, 14, 16, 17, 18, 20),
            SoundEnhancer.positions(SoundEnhancer.Control.NOISE, hex("14 FF FF FF FF FF FF FF FF"), null).map { it.id },
        )
        assertTrue(SoundEnhancer.positions(SoundEnhancer.Control.NOISE, hex("13 FF FF FF FF FF FF FF FF"), null).isEmpty())
        assertEquals(listOf(56, 57, 58), SoundEnhancer.positions(SoundEnhancer.Control.DNR, fitted, 10).map { it.id })
        assertEquals(listOf(51, 52, 53, 54), SoundEnhancer.positions(SoundEnhancer.Control.WIND, fitted, 10).map { it.id })
        val focus = SoundEnhancer.positions(SoundEnhancer.Control.FOCUS, fitted, 11)
        assertEquals(listOf(6 to 2, 5 to 1, 4 to 0, 7 to 1), focus.map { it.id to it.band })
        assertTrue(SoundEnhancer.positions(SoundEnhancer.Control.FOCUS, fitted, 28).isEmpty())
        assertTrue(SoundEnhancer.positions(SoundEnhancer.Control.WIND, hex("10 FF FF FF FF FF FF FF FF"), 10).isEmpty())
        assertTrue(SoundEnhancer.positions(SoundEnhancer.Control.WIND, null, 10).isEmpty())
    }

    @Test
    fun selectedValues() {
        assertEquals(SoundEnhancer.Position.NOISE_MODERATE, SoundEnhancer.selected(SoundEnhancer.Control.NOISE, hex("0F FF FF FF FF FF FF FF FF")))
        assertEquals(SoundEnhancer.Position.NOISE_MILD, SoundEnhancer.selected(SoundEnhancer.Control.NOISE, hex("0D FF FF FF FF FF FF FF FF")))
        assertNull(SoundEnhancer.selected(SoundEnhancer.Control.DNR, hex("37 FF FF FF FF FF FF FF FF"))) // 55 off
        assertEquals(SoundEnhancer.Position.FOCUS_AUTO, SoundEnhancer.selected(SoundEnhancer.Control.FOCUS, fitted))
        assertEquals(SoundEnhancer.Position.FOCUS_WIDE, SoundEnhancer.selected(SoundEnhancer.Control.FOCUS, hex("03 06 FF FF FF FF FF FF FF")))
        assertEquals(SoundEnhancer.Position.WIND_MEDIUM, SoundEnhancer.selected(SoundEnhancer.Control.WIND, fitted))
    }

    @Test
    fun applyWritesOnlyTheSlot() {
        assertEquals("02 07 11 35 39 FF FF FF FF", hexOf(SoundEnhancer.apply(fitted, SoundEnhancer.Position.NOISE_CONSIDERABLE)))
        assertEquals("02 07 10 35 3A FF FF FF FF", hexOf(SoundEnhancer.apply(fitted, SoundEnhancer.Position.DNR_STRONG)))
        assertEquals("02 07 10 33 39 FF FF FF FF", hexOf(SoundEnhancer.apply(fitted, SoundEnhancer.Position.WIND_OFF)))
        // automatic focus writes speech band "medium" (1)
        assertEquals("01 07 10 35 39 FF FF FF FF", hexOf(SoundEnhancer.apply(fitted, SoundEnhancer.Position.FOCUS_AUTO)))
        assertEquals("00 04 10 35 39 FF FF FF FF", hexOf(SoundEnhancer.apply(fitted, SoundEnhancer.Position.FOCUS_NARROW)))
        // missing slot, disabled noise reduction, transmitter rule
        val bare = hex("FF FF FF FF FF FF FF FF FF")
        assertContentEquals(bare, SoundEnhancer.apply(bare, SoundEnhancer.Position.WIND_STRONG))
        val disabled = hex("13 FF FF FF FF FF FF FF FF")
        assertContentEquals(disabled, SoundEnhancer.apply(disabled, SoundEnhancer.Position.NOISE_OFF))
        val auto = hex("14 FF FF FF FF FF FF FF FF")
        assertContentEquals(auto, SoundEnhancer.apply(auto, SoundEnhancer.Position.NOISE_OFF, transmitter = true))
        assertEquals("0C FF FF FF FF FF FF FF FF", hexOf(SoundEnhancer.apply(auto, SoundEnhancer.Position.NOISE_OFF)))
        for (pos in SoundEnhancer.Position.entries) {
            if (pos !in SoundEnhancer.positions(pos.control, fitted, 10)) continue
            assertTrue(SoundWriteGuard.featuresOk(fitted, SoundEnhancer.apply(fitted, pos), fitted), pos.name)
        }
    }

    @Test
    fun binauralEquality() {
        val other = hex("02 07 11 35 39 FF FF FF FF")
        assertFalse(SoundEnhancer.fittedEqual(SoundEnhancer.Control.NOISE, fitted, other))
        assertTrue(SoundEnhancer.fittedEqual(SoundEnhancer.Control.WIND, fitted, other))
        assertTrue(SoundEnhancer.fittedEqual(SoundEnhancer.Control.FOCUS, fitted, other))
        assertFalse(SoundEnhancer.fittedEqual(SoundEnhancer.Control.DNR, fitted, hex("02 07 11 35 FF FF FF FF FF")))
    }

    @Test
    fun guardExtensions() {
        val cur = hex("37 10 FF FF FF FF FF FF FF")
        assertTrue(SoundWriteGuard.featuresOk(cur, hex("38 10 FF FF FF FF FF FF FF"))) // DNR off -> mild
        assertTrue(SoundWriteGuard.featuresOk(hex("3A 10 FF FF FF FF FF FF FF"), hex("38 10 FF FF FF FF FF FF FF")))
        assertFalse(SoundWriteGuard.featuresOk(hex("38 10 FF FF FF FF FF FF FF"), cur)) // never back to DNR off
        // never write 19; a slot holding 19 never changes
        assertFalse(SoundWriteGuard.featuresOk(cur, hex("37 13 FF FF FF FF FF FF FF")))
        assertFalse(SoundWriteGuard.featuresOk(hex("37 13 FF FF FF FF FF FF FF"), cur))
        // 20 only where the slot holds it or was fitted with it
        assertFalse(SoundWriteGuard.featuresOk(cur, hex("37 14 FF FF FF FF FF FF FF")))
        assertFalse(SoundWriteGuard.featuresOk(cur, hex("37 14 FF FF FF FF FF FF FF"), cur))
        assertTrue(SoundWriteGuard.featuresOk(cur, hex("37 14 FF FF FF FF FF FF FF"), hex("37 14 FF FF FF FF FF FF FF")))
        assertTrue(SoundWriteGuard.featuresOk(hex("37 14 FF FF FF FF FF FF FF"), cur))
        // DNR id into a noise-reduction slot and the reverse
        assertFalse(SoundWriteGuard.featuresOk(cur, hex("10 10 FF FF FF FF FF FF FF")))
        assertFalse(SoundWriteGuard.featuresOk(cur, hex("37 38 FF FF FF FF FF FF FF")))
    }

    @Test
    fun restoreCoversNewFeatures() {
        val now = hex("00 04 11 36 3A 15 FF FF FF")
        val fit = hex("02 07 14 35 38 16 FF FF FF")
        assertEquals("02 07 14 35 38 15 FF FF FF", hexOf(FeatureSlice.restore(now, fit)))
        // DNR off is not restored, disabled noise reduction stays
        assertEquals("39 13 FF FF FF FF FF FF FF", hexOf(FeatureSlice.restore(hex("39 13 FF FF FF FF FF FF FF"), hex("37 10 FF FF FF FF FF FF FF"))))
        assertTrue(SoundWriteGuard.featuresOk(now, FeatureSlice.restore(now, fit), fit))
    }
}

class TinnitusTest {

    // variation 22, low edge 26 (500 Hz), high edge 37 (8000 Hz), nature off, wind 52
    private val slice = hex("16 1A 25 26 34 FF FF FF FF")

    @Test
    fun flagsLength() {
        val v5 = hex("01 00 00 00 01 00 00 00")
        assertEquals(setOf(0, 2), Tinnitus.programs(v5, Platform.V5_1))
        assertNull(Tinnitus.programs(v5, Platform.V4))
        val v4 = ByteArray(22).also { it[2] = 0x03; it[20] = 0x01 }
        assertEquals(setOf(1, 10), Tinnitus.programs(v4, Platform.V4))
        assertNull(Tinnitus.programs(v4, Platform.V5_0))
        assertNull(Tinnitus.programs(ByteArray(10), Platform.V5_1))
        assertNull(Tinnitus.programs(null, Platform.V5_1))
        // other bits of the byte and the second byte are not the generator
        assertEquals(emptySet(), Tinnitus.programs(hex("FE 01 00 FF 00 00 00 00"), Platform.V5_1))
    }

    @Test
    fun readsAndOffers() {
        assertEquals(1, Tinnitus.variation(slice))
        assertEquals(-1, Tinnitus.nature(slice))
        assertEquals(1, Tinnitus.highPass(slice))
        assertEquals(6, Tinnitus.lowPass(slice))
        assertTrue(Tinnitus.hasRange(slice))
        assertFalse(Tinnitus.hasRange(hex("1A 25 FF FF FF FF FF FF FF")))
        assertFalse(Tinnitus.any(hex("10 35 FF FF FF FF FF FF FF")))
        assertEquals(2, Tinnitus.nature(hex("29 FF FF FF FF FF FF FF FF")))
        assertEquals(2, Tinnitus.nature(hex("2F FF FF FF FF FF FF FF FF")))
    }

    @Test
    fun sideCorrectNature() {
        assertEquals(39, Tinnitus.natureId(0, Side.LEFT))
        assertEquals(44, Tinnitus.natureId(5, Side.LEFT))
        assertEquals(45, Tinnitus.natureId(0, Side.RIGHT))
        assertEquals(50, Tinnitus.natureId(5, Side.RIGHT))
        assertEquals("16 1A 25 2A 34 FF FF FF FF", hexOf(Tinnitus.withNature(slice, 3, Side.LEFT)!!))
        assertEquals("16 1A 25 30 34 FF FF FF FF", hexOf(Tinnitus.withNature(slice, 3, Side.RIGHT)!!))
        assertTrue(Tinnitus.sameFeature(38, 42, Side.LEFT))
        assertFalse(Tinnitus.sameFeature(38, 48, Side.LEFT))
        assertFalse(Tinnitus.sameFeature(38, 42, Side.RIGHT))
        assertTrue(Tinnitus.sameFeature(45, 38, Side.RIGHT))
        assertFalse(Tinnitus.sameFeature(22, 26, Side.RIGHT))
        assertFalse(Tinnitus.sameFeature(37, 38, Side.RIGHT))
    }

    @Test
    fun variationTurnsNatureOff() {
        val nature = hex("16 1A 25 2A 34 FF FF FF FF")
        assertEquals("18 1A 25 26 34 FF FF FF FF", hexOf(Tinnitus.withVariation(nature, 3)!!))
        assertNull(Tinnitus.withVariation(hex("26 FF FF FF FF FF FF FF FF"), 0))
    }

    @Test
    fun edgeFixUps() {
        // high edge open while the low edge is open -> low edge 500 Hz
        val open = hex("16 19 25 26 FF FF FF FF FF")
        assertEquals("16 1A 1F 26 FF FF FF FF FF", hexOf(Tinnitus.withLowPass(open, 0)!!))
        // high edge 2000 over a 2000 low edge -> low edge 1500
        val low2k = hex("16 1E 25 26 FF FF FF FF FF")
        assertEquals("16 1D 20 26 FF FF FF FF FF", hexOf(Tinnitus.withLowPass(low2k, 1)!!))
        // low edge open while the high edge is open -> high edge 8000
        val highOpen = hex("16 1A 1F 26 FF FF FF FF FF")
        assertEquals("16 19 25 26 FF FF FF FF FF", hexOf(Tinnitus.withHighPass(highOpen, 0)!!))
        // low edge 2000 under a 2000 high edge -> high edge 3000
        val high2k = hex("16 1A 20 26 FF FF FF FF FF")
        assertEquals("16 1E 21 26 FF FF FF FF FF", hexOf(Tinnitus.withHighPass(high2k, 5)!!))
        // plain moves
        assertEquals("16 1C 25 26 FF FF FF FF FF", hexOf(Tinnitus.withHighPass(hex("16 1A 25 26 FF FF FF FF FF"), 3)!!))
        assertNull(Tinnitus.withHighPass(hex("16 26 FF FF FF FF FF FF FF"), 1))
    }

    @Test
    fun guardAndRestore() {
        val changed = Tinnitus.withNature(Tinnitus.withHighPass(slice, 4)!!, 1, Side.RIGHT)!!
        assertTrue(SoundWriteGuard.featuresOk(slice, changed, tinnitusSide = Side.RIGHT))
        assertFalse(SoundWriteGuard.featuresOk(slice, changed, tinnitusSide = Side.LEFT)) // wrong side id
        assertFalse(SoundWriteGuard.featuresOk(slice, changed)) // no generator on this aid
        // never a tinnitus id into a non-tinnitus or unused slot
        assertFalse(SoundWriteGuard.featuresOk(slice, hex("16 1A 25 26 34 16 FF FF FF"), tinnitusSide = Side.LEFT))
        assertFalse(SoundWriteGuard.featuresOk(slice, hex("16 1A 25 26 16 FF FF FF FF"), tinnitusSide = Side.LEFT))
        assertContentEquals(slice, Tinnitus.restore(changed, slice, Side.RIGHT))
        // a left id in a right aid's fitting is never written back
        assertEquals("16 1A 25 2E 34 FF FF FF FF", hexOf(Tinnitus.restore(changed, hex("16 1A 25 2A 34 FF FF FF FF"), Side.RIGHT)))
        assertTrue(Tinnitus.fittedEqual(hex("16 1A 25 2A 34 FF FF FF FF"), hex("16 1A 25 30 34 FF FF FF FF")))
        assertFalse(Tinnitus.fittedEqual(hex("16 1A 25 2A 34 FF FF FF FF"), hex("16 1A 25 31 34 FF FF FF FF")))
    }
}

class ReceiverCheckTest {

    private fun record2(rom: Int, side: Int, power: Int, tube: Int, type: Int, serial: Long): ByteArray {
        val r = ByteArray(14)
        r[1] = rom.toByte(); r[2] = side.toByte(); r[3] = power.toByte(); r[4] = tube.toByte(); r[5] = type.toByte()
        for (i in 0 until 8) r[6 + i] = (serial shr (8 * i)).toByte()
        r[0] = ReceiverCheck.crc8(r, 1, 14).toByte()
        return r
    }

    private fun record1(rom: Int, side: Int, power: Int, tube: Int, type: Int, serial: Long): ByteArray {
        val r = ByteArray(14)
        r[0] = rom.toByte(); r[1] = side.toByte(); r[2] = power.toByte(); r[3] = tube.toByte(); r[4] = type.toByte()
        for (i in 0 until 8) r[6 + i] = (serial shr (8 * i)).toByte()
        return r
    }

    @Test
    fun crc() {
        // CRC-8, polynomial 0xD5, initial 0x67, MSB first: a zero byte runs the seed through 8 shifts
        assertEquals(0, ReceiverCheck.crc8(ByteArray(0)))
        var c = 0x67
        repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0xD5) and 0xff else (c shl 1) and 0xff }
        assertEquals(c, ReceiverCheck.crc8(byteArrayOf(0)))
        assertEquals(ReceiverCheck.crc8(hex("12 34")), ReceiverCheck.crc8(hex("00 12 34"), 1, 3))
    }

    @Test
    fun okVersion2() {
        val rec = record2(0x21, 1, 1, 3, 0, 0x0102030405060708L)
        val data = byteArrayOf(2, 0x07) + rec + rec
        val s = ReceiverCheck.parse(data)!!
        assertFalse(s.error)
        assertEquals(ReceiverCheck.Record(0x21, 1, 1, 3, 0, 0x0102030405060708L), s.current)
        assertEquals(s.current, s.fitted)
        // tube length changed and safe level are not errors
        assertFalse(ReceiverCheck.parse(byteArrayOf(2, 0xA7.toByte()) + rec + rec)!!.error)
    }

    @Test
    fun errors() {
        val rec = record2(0x21, 0, 2, 3, 0, 42L)
        assertTrue(ReceiverCheck.parse(byteArrayOf(2, 0x00) + rec + rec)!!.error) // not attached
        assertTrue(ReceiverCheck.parse(byteArrayOf(2, 0x05) + rec + rec)!!.error) // data invalid
        assertTrue(ReceiverCheck.parse(byteArrayOf(2, 0x03) + rec + rec)!!.error) // unsupported
        assertTrue(ReceiverCheck.parse(byteArrayOf(2, 0x0F) + rec + rec)!!.error) // side changed
        assertTrue(ReceiverCheck.parse(byteArrayOf(2, 0x17) + rec + rec)!!.error) // power changed
        assertTrue(ReceiverCheck.parse(byteArrayOf(2, 0x47) + rec + rec)!!.error) // type changed
        // flags without the attached bit mean nothing beyond "not attached"
        val s = ReceiverCheck.parse(byteArrayOf(2, 0x7E) + rec + rec)!!
        assertFalse(s.sideChanged)
        assertNull(s.current)
        assertTrue(s.error)
    }

    @Test
    fun invalidInput() {
        val rec = record2(0x21, 0, 2, 3, 0, 42L)
        assertNull(ReceiverCheck.parse(byteArrayOf(2, 0x07) + rec)) // 16 bytes
        assertNull(ReceiverCheck.parse(byteArrayOf(0, 0x07) + rec + rec)) // version 0
        assertNull(ReceiverCheck.parse(byteArrayOf(3, 0x07) + rec + rec))
        assertNull(ReceiverCheck.parse(null))
        // a bad CRC in the fitted record drops the whole reading
        val bad = rec.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertNull(ReceiverCheck.parse(byteArrayOf(2, 0x07) + rec + bad))
        // a bad CRC in the detected record drops only that record
        assertNull(ReceiverCheck.parse(byteArrayOf(2, 0x07) + bad + rec)!!.current)
    }

    @Test
    fun version1() {
        val rec = record1(0x10, 0, 1, 2, 5, 7L)
        val s = ReceiverCheck.parse(byteArrayOf(1, 0x07) + rec + rec)!!
        assertEquals(ReceiverCheck.Record(0x10, 0, 1, 2, 5, 7L), s.fitted)
        assertFalse(s.error)
    }
}

package app.ihearyou.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CryptoTest {

    private val key = Crypto.sha256("resound-remote-test".toByteArray())

    @Test
    fun micRoundTrip() {
        val enc = Encoder(mic = true)
        enc.setKeys(key, key)
        val plain = "APP says hi ".toByteArray()
        val packet = enc.encrypt(plain)
        assertEquals(plain.size + 4, packet.size)
        val dec = Encoder(mic = true)
        dec.setKeys(key, key)
        assertContentEquals(plain, dec.decrypt(packet))
    }

    @Test
    fun tamperedPacketRejected() {
        val enc = Encoder(mic = true)
        enc.setKeys(key, key)
        val packet = enc.encrypt("hello world".toByteArray())
        packet[0] = (packet[0].toInt() xor 0xFF).toByte()
        val dec = Encoder(mic = true)
        dec.setKeys(key, key)
        assertFailsWith<IllegalArgumentException> { dec.decrypt(packet) }
    }

    @Test
    fun countersAdvancePerPacket() {
        val a = Encoder(mic = false)
        val b = Encoder(mic = false)
        a.setKeys(key, key)
        b.setKeys(key, key)
        val p1 = "0123456789abcdef".toByteArray()
        val c1 = a.encrypt(p1)
        val c1bis = b.encrypt(p1)
        assertContentEquals(c1, c1bis)
        val c2 = a.encrypt(p1)
        b.decrypt(c1)
        val c2bis = b.encrypt(p1)
        assertContentEquals(c2, c2bis)
    }

    @Test
    fun deriveIsDeterministic() {
        val challenge = ByteArray(42) { (it + 1).toByte() }
        val one = deriveKeys(challenge, 2)
        val two = deriveKeys(challenge, 2)
        assertContentEquals(one.derived.sharedAppKey, two.derived.sharedAppKey)
        assertEquals(32, one.derived.sharedAppKey.size)
    }

    @Test
    fun authPayloadLayout() {
        val enc = Encoder(mic = true)
        enc.setKeys(key, key)
        val payload = authPayload(enc, 9, 0, ByteArray(65) { 4 })
        assertEquals(6 + 12 + 4 + 64, payload.size)
        assertContentEquals(byteArrayOf(0, 0, 4, 0, 9, 0), payload.copyOfRange(0, 6))
    }
}

class FramesTest {

    @Test
    fun parsesHandleValues() {
        val values = Frames.parseHandleValues(byteArrayOf(53, 2, 1, 0, 8, 1, 3))
        assertEquals(2, values.size)
        assertEquals(53, values[0].handle)
        assertContentEquals(byteArrayOf(1, 0), values[0].data)
        assertEquals(8, values[1].handle)
        assertEquals(3, values[1].data[0].toInt())
    }

    @Test
    fun programMask() {
        assertEquals(listOf(0, 1, 2, 3), Frames.programIndices(byteArrayOf(0x0F, 0, 0, 0)))
        assertEquals(listOf(0, 2), Frames.programIndices(byteArrayOf(0x05, 0, 0, 0)))
    }

    @Test
    fun tapBits() {
        assertEquals(true, Frames.tapCallsEnabled(0x0202))
        assertEquals(false, Frames.tapProgramEnabled(0x0202))
        assertEquals(true, Frames.tapAllowCalls(0x0202))
        assertEquals(false, Frames.tapAllowProgram(0x0202))
    }

    @Test
    fun buttonFrames() {
        assertTrue(Frames.buttonPressed(byteArrayOf(1, 0)))
        assertTrue(!Frames.buttonPressed(byteArrayOf(0, 0)))
    }
}

class StandardFeaturesTest {

    @Test
    fun decodesFeatureIds() {
        assertEquals("Noise reduction", StandardFeatures.decode(12)?.name)
        assertEquals("Off", StandardFeatures.decode(12)?.value)
        assertEquals("Strong", StandardFeatures.decode(17)?.value)
        assertEquals("Wind noise", StandardFeatures.decode(53)?.name)
    }

    @Test
    fun splitsNexiaBlob() {
        val blob = ByteArray(36) { 0xFF.toByte() }
        blob[0] = 12 // program 1: PNR off
        blob[9] = 17 // program 2: PNR strong
        val programs = StandardFeatures.decodePrograms(blob)
        assertEquals(4, programs.size)
        assertEquals(1, programs[0].size)
        assertEquals("Strong", programs[1][0].value)
        assertTrue(programs[2].isEmpty())
    }
}

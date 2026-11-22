package app.ihearyou

import app.ihearyou.voice.VoiceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The per-group mention is off unless chosen, and only a pubkey hex is ever stored. */
class MentionTest {
    private val hex = "ab".repeat(32)

    @Test
    fun aPubkeyHexIsKept() {
        assertEquals(hex, VoiceSettings.pubkeyHex(hex))
    }

    @Test
    fun anythingElseIsDropped() {
        assertNull(VoiceSettings.pubkeyHex(null))
        assertNull(VoiceSettings.pubkeyHex("npub1xyz"))
        assertNull(VoiceSettings.pubkeyHex(hex.uppercase()))
        assertNull(VoiceSettings.pubkeyHex(hex.dropLast(1)))
        assertNull(VoiceSettings.pubkeyHex(hex + "a"))
    }
}

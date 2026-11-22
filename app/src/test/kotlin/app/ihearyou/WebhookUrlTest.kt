package app.ihearyou

import app.ihearyou.voice.VoiceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Webhooks: https by default, plain http only to an address the user confirmed. */
class WebhookUrlTest {

    @Test
    fun anAddressWithoutASchemeIsHttps() {
        assertEquals("https://example.org/hook", VoiceSettings.webhookUrl(" example.org/hook "))
        assertNull(VoiceSettings.webhookUrl("  "))
        assertEquals("https://example.org/hook", VoiceSettings.webhookTarget("example.org/hook", cleartextConfirmed = false))
    }

    @Test
    fun plainHttpNeedsTheUsersConfirmation() {
        val http = "http://100.64.0.2:8080/x"
        assertEquals(http, VoiceSettings.webhookUrl(http))
        assertTrue(VoiceSettings.isHttp(http))
        assertNull(VoiceSettings.webhookTarget(http, cleartextConfirmed = false))
        assertEquals(http, VoiceSettings.webhookTarget(http, cleartextConfirmed = true))
    }

    @Test
    fun nothingElseIsATarget() {
        assertNull(VoiceSettings.webhookTarget("ftp://example.org", cleartextConfirmed = true))
        assertNull(VoiceSettings.webhookTarget("https://", cleartextConfirmed = true))
        assertNull(VoiceSettings.webhookTarget(null, cleartextConfirmed = true))
        assertFalse(VoiceSettings.isHttps("http://example.org"))
        assertTrue(VoiceSettings.isHttps("HTTPS://example.org"))
    }
}

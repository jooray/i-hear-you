package app.ihearyou

import app.ihearyou.marmot.Mentions
import kotlin.test.assertEquals
import org.junit.Test

/** The key pair is the example from NIP-19. */
class MentionsReadableTest {
    private val npub = "npub10elfcs4fr0l0r8af98jlmgdh9c8tcxjvz9qkw038js35mp4dma8qzvjptg"
    private val hex = "7e7e9c42a91bfef19fa929e5fda1b72e0ebc1a4c1141673e2794234d86addf4e"

    @Test
    fun `an npub decodes to its pubkey hex`() {
        assertEquals(hex, Mentions.hexOf(npub))
    }

    @Test
    fun `mentions in every form read as a name`() {
        val names = mapOf(hex to "Alice")
        assertEquals("@Alice toto je mention", Mentions.readable("@$npub toto je mention", names::get))
        assertEquals("ahoj @Alice.", Mentions.readable("ahoj nostr:$npub.", names::get))
        assertEquals("@Alice", Mentions.readable(npub, names::get))
    }

    @Test
    fun `an unknown member is shortened, plain text is untouched`() {
        assertEquals("@npub10elfcs4… hi", Mentions.readable("@$npub hi", { null }))
        assertEquals("no mention here", Mentions.readable("no mention here", { "x" }))
    }
}

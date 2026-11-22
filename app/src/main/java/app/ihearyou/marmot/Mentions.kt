package app.ihearyou.marmot

/**
 * Mentions as White Noise writes them into a message's text (`@npub1…`, `nostr:npub1…`, a bare
 * `npub1…`) shown and read aloud as `@Name`: a 63-character npub means nothing on screen and is
 * worse spoken.
 */
object Mentions {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private const val NPUB_DATA_LENGTH = 58
    private const val CHECKSUM_LENGTH = 6
    private const val BITS_IN = 5
    private const val BITS_OUT = 8
    private const val PUBKEY_BYTES = 32
    private const val BYTE_MASK = 0xff
    private const val SHORT_HEAD = 12
    private val NPUB = Regex("(?:nostr:|@)?\\b(npub1[$CHARSET]{$NPUB_DATA_LENGTH})\\b")

    /** The pubkey hex an npub encodes, or null when it is not one (the checksum is not checked). */
    fun hexOf(npub: String): String? {
        if (!npub.startsWith("npub1")) return null
        val data = npub.substring("npub1".length).dropLast(CHECKSUM_LENGTH).map { CHARSET.indexOf(it) }
        if (data.any { it < 0 }) return null
        var acc = 0
        var bits = 0
        val out = StringBuilder()
        for (v in data) {
            acc = (acc shl BITS_IN) or v
            bits += BITS_IN
            while (bits >= BITS_OUT) {
                bits -= BITS_OUT
                out.append("%02x".format((acc shr bits) and BYTE_MASK))
            }
        }
        return out.toString().takeIf { it.length == PUBKEY_BYTES * 2 }
    }

    /** [text] with every npub mention replaced by `@name` ([name] null: a shortened npub). */
    fun readable(text: String, name: (hex: String) -> String?): String =
        if (!text.contains("npub1")) {
            text
        } else {
            NPUB.replace(text) { m ->
                val npub = m.groupValues[1]
                val label = hexOf(npub)?.let(name)?.takeIf { it.isNotBlank() } ?: (npub.take(SHORT_HEAD) + "…")
                "@$label"
            }
        }
}

package app.ihearyou.protocol

/** Parsed GN Notify frames: the first byte of a Notify frame is its type. */
sealed class GnFrame {
    data class Auth(val code: Int, val payload: ByteArray?) : GnFrame()
    data class HandleValues(val values: List<HandleValue>) : GnFrame()
    data class NotificationVector(val bits: ByteArray) : GnFrame()
    data class BlobChunk(val handle: Int, val data: ByteArray) : GnFrame()
    data class Discovery(val entries: List<DiscoveredHandle>, val last: Boolean) : GnFrame()
    data class ErrorCodes(val errors: List<GnError>) : GnFrame()
}

data class HandleValue(val handle: Int, val data: ByteArray)
data class DiscoveredHandle(val handle: Int, val size: Int, val flags: Int, val uuid: ByteArray)
data class GnError(val source: Int, val handle: Int, val code: Int)

object Frames {
    const val TYPE_AUTH = 1
    const val TYPE_VECTOR = 2
    const val TYPE_READ = 3
    const val TYPE_NOTIFY = 4
    const val TYPE_BLOB = 5
    const val TYPE_DISCOVER = 6
    const val TYPE_DISCOVER_LAST = 7
    const val TYPE_ERROR = 8
    const val TYPE_MIC_ERROR = 9

    /** Body must already be decrypted for types 2..7. Type 1 keeps [code][cipher]. */
    fun parse(type: Int, body: ByteArray, encoder: Encoder?): GnFrame? = when (type) {
        TYPE_AUTH -> {
            val code = body[0].toInt() and 0xff
            val payload = if (code == 0 && body.size > 1 && encoder != null)
                encoder.decrypt(body.copyOfRange(1, body.size)) else null
            GnFrame.Auth(code, payload)
        }
        TYPE_VECTOR -> GnFrame.NotificationVector(body)
        TYPE_READ, TYPE_NOTIFY -> GnFrame.HandleValues(parseHandleValues(body))
        TYPE_BLOB -> if (body.size >= 3) {
            GnFrame.BlobChunk(body[0].toInt() and 0xff, body.copyOfRange(2, body.size))
        } else null
        TYPE_DISCOVER, TYPE_DISCOVER_LAST -> GnFrame.Discovery(
            parseDiscovery(body), type == TYPE_DISCOVER_LAST,
        )
        TYPE_ERROR -> GnFrame.ErrorCodes(parseErrors(body))
        else -> null
    }

    /** type 3/4 body: repeated [handle][len][data...]. */
    fun parseHandleValues(body: ByteArray): List<HandleValue> {
        val out = ArrayList<HandleValue>()
        var i = 0
        while (i + 1 < body.size) {
            val handle = body[i].toInt() and 0xff
            val len = body[i + 1].toInt() and 0xff
            i += 2
            if (len > body.size - i) break
            out += HandleValue(handle, body.copyOfRange(i, i + len))
            i += len
        }
        return out
    }

    /** type 6/7 body: repeated [handle][size][flags][uuid16]. */
    fun parseDiscovery(body: ByteArray): List<DiscoveredHandle> {
        val out = ArrayList<DiscoveredHandle>()
        var i = 0
        while (i + 19 <= body.size) {
            out += DiscoveredHandle(
                handle = body[i].toInt() and 0xff,
                size = body[i + 1].toInt() and 0xff,
                flags = body[i + 2].toInt() and 0xff,
                uuid = body.copyOfRange(i + 3, i + 19),
            )
            i += 19
        }
        return out
    }

    /** type 8 body: repeated [source][handle][code]. */
    fun parseErrors(body: ByteArray): List<GnError> {
        val out = ArrayList<GnError>()
        var i = 0
        while (i + 2 < body.size) {
            out += GnError(
                source = body[i].toInt() and 0xff,
                handle = body[i + 1].toInt() and 0xff,
                code = body[i + 2].toInt() and 0xff,
            )
            i += 3
        }
        return out
    }

    fun handleNames(handles: Map<Int, String>) = handles

    // ---- program masks -------------------------------------------------------

    /**
     * Mask encoding: when the first two bytes are zero the mask is bit-reversed per byte and
     * byte-reversed.
     */
    fun programIndices(mask: ByteArray): List<Int> {
        require(mask.size >= 4) { "program mask must be 4 bytes" }
        val bytes = if (mask[0].toInt() == 0 && mask[1].toInt() == 0) {
            ByteArray(4) { i -> reverseBits(mask[3 - i]) }
        } else {
            mask.copyOfRange(0, 4)
        }
        val out = ArrayList<Int>()
        for (b in 0 until 4) for (bit in 0 until 8) {
            if ((bytes[b].toInt() shr bit) and 1 == 1) out += b * 8 + bit
        }
        return out
    }

    private fun reverseBits(b: Byte): Byte {
        var x = b.toInt() and 0xff
        var r = 0
        repeat(8) { r = (r shl 1) or (x and 1); x = x shr 1 }
        return r.toByte()
    }

    // ---- tap config ----------------------------------------------------------

    fun tapProgramEnabled(v: Int) = (v and 0x0001) != 0
    fun tapCallsEnabled(v: Int) = (v and 0x0002) != 0
    fun tapAllowProgram(v: Int) = (v and 0x0100) != 0
    fun tapAllowCalls(v: Int) = (v and 0x0200) != 0

    // ---- push button ---------------------------------------------------------

    fun buttonPressed(data: ByteArray) = data.isNotEmpty() && data[0].toInt() == 1
}

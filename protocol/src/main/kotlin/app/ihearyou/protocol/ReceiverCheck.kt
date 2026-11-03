package app.ihearyou.protocol

/**
 * Receiver check (handle 37 on protocols 4 and 5, read-only, 30 bytes): the aid compares the
 * receiver (the speaker in the ear canal) it detects with the one it was fitted with.
 *
 * ```
 * byte 0       data version, 1 or 2
 * byte 1       flags; only meaningful while bit 0 is set
 *                0x01 receiver attached   0x02 receiver data valid   0x04 receiver supported
 *                0x08 side changed        0x10 power changed         0x20 tube length changed
 *                0x40 receiver type changed                          0x80 safe level applied
 * bytes 2..15  detected receiver, 14 bytes (only when attached, valid and supported)
 * bytes 16..29 fitted receiver, 14 bytes
 * ```
 *
 * A 14-byte record is `[rom][side][power][tube][type][0][serial, u64 LE]` in version 1 and
 * `[crc][rom][side][power][tube][type][serial, u64 LE]` in version 2, where the CRC-8
 * (polynomial 0xD5, initial value 0x67, most significant bit first) covers bytes 1..13.
 * `rom` is major << 4 | minor; side 0 right, 1 left, 2 unassigned; power 0 low .. 3 ultra.
 *
 * The receiver is in error when it is missing, its data is invalid or unsupported, or its side,
 * power or type differ from the fitting. A different tube length or the safe level are not errors.
 */
object ReceiverCheck {
    const val SIZE = 30
    private const val RECORD = 14
    private const val POLY = 0xD5
    private const val SEED = 0x67

    data class Record(val rom: Int, val side: Int, val power: Int, val tube: Int, val type: Int, val serial: Long)

    data class Status(
        val version: Int,
        val flags: Int,
        val current: Record?,
        val fitted: Record,
    ) {
        val attached get() = flags and 0x01 != 0
        private fun bit(mask: Int) = attached && flags and mask != 0
        val dataValid get() = bit(0x02)
        val supported get() = bit(0x04)
        val sideChanged get() = bit(0x08)
        val powerChanged get() = bit(0x10)
        val tubeChanged get() = bit(0x20)
        val typeChanged get() = bit(0x40)
        val safeLevel get() = bit(0x80)
        val error get() = !attached || !dataValid || !supported || sideChanged || powerChanged || typeChanged
    }

    fun crc8(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        if (from >= to) return 0
        var c = SEED
        for (i in from until to) {
            c = c xor (data[i].toInt() and 0xff)
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor POLY) and 0xff else (c shl 1) and 0xff }
        }
        return c
    }

    /** One record, or null when a version-2 CRC does not match. */
    fun record(bytes: ByteArray, version: Int): Record? {
        require(bytes.size == RECORD)
        val o = if (version == 1) 0 else 1
        if (version == 2 && crc8(bytes, 1, RECORD) != (bytes[0].toInt() and 0xff)) return null
        var serial = 0L
        for (i in 0 until 8) serial = serial or ((bytes[6 + i].toLong() and 0xff) shl (8 * i))
        fun u(i: Int) = bytes[i + o].toInt() and 0xff
        return Record(rom = u(0), side = u(1), power = u(2), tube = u(3), type = u(4), serial = serial)
    }

    /**
     * The decoded check, or null when it is not 30 bytes, has an unknown version, or the fitted
     * record fails its CRC (then nothing can be said about the receiver).
     */
    fun parse(data: ByteArray?): Status? {
        if (data == null || data.size != SIZE) return null
        val version = data[0].toInt() and 0xff
        if (version != 1 && version != 2) return null
        val flags = data[1].toInt() and 0xff
        val fitted = record(data.copyOfRange(16, 30), version) ?: return null
        val readable = flags and 0x07 == 0x07
        val current = if (readable) record(data.copyOfRange(2, 16), version) else null
        return Status(version, flags, current, fitted)
    }
}

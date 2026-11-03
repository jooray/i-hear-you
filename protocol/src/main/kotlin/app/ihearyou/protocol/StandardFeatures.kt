package app.ihearyou.protocol

/**
 * Standard-features blob (handle 36 read, handle 35 write).
 *
 * The blob is NOT a bitfield: 9 bytes per program slot (protocol 2 and later), each byte a globally unique
 * command id for the *current value* of whatever feature the fitter assigned to that slot.
 * 0xFF = slot unused.
 */
object StandardFeatures {

    data class Feature(val name: String, val value: String)

    private val IDS: Map<Int, Feature> = buildMap {
        fun put(vararg ids: Int, name: String, value: String) {
            ids.forEach { put(it, Feature(name, value)) }
        }
        put(0, name = "Speech focus", value = "High")
        put(1, name = "Speech focus", value = "Medium")
        put(2, name = "Speech focus", value = "Mild")
        put(3, name = "Speech focus", value = "Low")
        put(4, name = "Directionality", value = "Narrow")
        put(5, name = "Directionality", value = "Medium")
        put(6, name = "Directionality", value = "Wide")
        put(7, name = "Directionality", value = "Dynamic")
        put(8, name = "Impulse noise", value = "Off")
        put(9, name = "Impulse noise", value = "Mild")
        put(10, name = "Impulse noise", value = "Moderate")
        put(11, name = "Impulse noise", value = "Strong")
        put(12, name = "Noise reduction", value = "Off")
        put(13, name = "Noise reduction", value = "Very mild")
        put(14, name = "Noise reduction", value = "Mild")
        put(15, name = "Noise reduction", value = "Moderate")
        put(16, name = "Noise reduction", value = "Medium")
        put(17, name = "Noise reduction", value = "Strong")
        put(18, name = "Noise reduction", value = "Very strong")
        put(19, name = "Noise reduction", value = "Disabled")
        put(20, name = "Noise reduction", value = "Per environment")
        put(21, name = "Tinnitus modulation", value = "Off")
        put(22, name = "Tinnitus modulation", value = "Mild")
        put(23, name = "Tinnitus modulation", value = "Moderate")
        put(24, name = "Tinnitus modulation", value = "Strong")
        put(25, name = "Tinnitus high-pass", value = "Off")
        put(26, name = "Tinnitus high-pass", value = "500 Hz")
        put(27, name = "Tinnitus high-pass", value = "750 Hz")
        put(28, name = "Tinnitus high-pass", value = "1000 Hz")
        put(29, name = "Tinnitus high-pass", value = "1500 Hz")
        put(30, name = "Tinnitus high-pass", value = "2000 Hz")
        put(31, name = "Tinnitus low-pass", value = "Off")
        put(32, name = "Tinnitus low-pass", value = "2000 Hz")
        put(33, name = "Tinnitus low-pass", value = "3000 Hz")
        put(34, name = "Tinnitus low-pass", value = "4000 Hz")
        put(35, name = "Tinnitus low-pass", value = "5000 Hz")
        put(36, name = "Tinnitus low-pass", value = "6000 Hz")
        put(37, name = "Tinnitus low-pass", value = "8000 Hz")
        put(38, name = "Tinnitus sound", value = "Off")
        put(39, 40, 41, 42, 43, 44, name = "Tinnitus sound", value = "Nature (left)")
        put(45, 46, 47, 48, 49, 50, name = "Tinnitus sound", value = "Nature (right)")
        put(51, name = "Wind noise", value = "Off")
        put(52, name = "Wind noise", value = "Mild")
        put(53, name = "Wind noise", value = "Medium")
        put(54, name = "Wind noise", value = "Strong")
        put(55, name = "DNR", value = "Off")
        put(56, name = "DNR", value = "Mild")
        put(57, name = "DNR", value = "Moderate")
        put(58, name = "DNR", value = "Strong")
    }

    fun decode(id: Int): Feature? = IDS[id]

    /**
     * Decode the blob into per-program feature lists.
     * Lengths seen in the wild: 36 (4 × 9, Nexia), 81 (9 × 9, protocols 2 and 3), 32 (4 × 8, protocol 1).
     */
    fun decodePrograms(blob: ByteArray): List<List<Feature>> {
        val slice = when {
            blob.size % 9 == 0 && blob.size / 9 in 1..9 -> 9
            blob.size % 8 == 0 -> 8
            else -> return emptyList()
        }
        val out = ArrayList<List<Feature>>()
        var i = 0
        while (i + slice <= blob.size) {
            val features = ArrayList<Feature>()
            for (j in 0 until slice) {
                val id = blob[i + j].toInt() and 0xff
                if (id == 0xFF) continue
                decode(id)?.let { features += it }
            }
            out += features
            i += slice
        }
        return out
    }
}

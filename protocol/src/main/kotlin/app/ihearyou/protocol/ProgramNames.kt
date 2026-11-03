package app.ihearyou.protocol

/**
 * Standard program types (unified environment IDs, handle 17: one little-endian uint32 per preset,
 * 4 on Nexia, 7 elsewhere; 0 means All-Around) and the stock names the fitting software writes
 * for them in English, Slovak, Czech, German and Spanish.
 *
 * An aid name is only replaced by the app-language name when it is one of these stock names, so
 * a name the audiologist typed by hand is always kept.
 */
object ProgramNames {

    enum class Kind(val environmentId: Int) {
        ALL_AROUND(1), RESTAURANT(2), MUSIC(3), OUTDOOR(4), LECTURE(12), HEAR_IN_NOISE(15), TRAFFIC(51),
    }

    private val stock: Map<Kind, Set<String>> = mapOf(
        Kind.ALL_AROUND to setOf("All-Around", "All around", "Všeobecný", "Univerzální", "Alltag", "General"),
        Kind.RESTAURANT to setOf("Restaurant", "Reštaurácia", "Restaurace", "Restaurante"),
        Kind.MUSIC to setOf("Music", "Hudba", "Musik", "Música"),
        Kind.OUTDOOR to setOf("Outdoor", "Vonku", "Venku", "Exterior"),
        Kind.LECTURE to setOf("Lecture", "Prednáška", "Přednáška", "Vortrag", "Lectura"),
        Kind.HEAR_IN_NOISE to setOf("Hear in noise", "Počuteľnosť v hluku", "V hluku", "Sprachfokus", "Oír en Ruido"),
        Kind.TRAFFIC to setOf("Traffic", "Doprava", "Ulice", "Verkehr", "Tráfico"),
    ).mapValues { (_, names) -> names.map { it.lowercase() }.toSet() }

    /** Environment ID per preset index, or empty when the blob has an unexpected size. */
    fun parseEnvironmentIds(blob: ByteArray): List<Int> {
        if (blob.isEmpty() || blob.size % 4 != 0) return emptyList()
        return (0 until blob.size / 4).map { i ->
            val o = i * 4
            val v = (blob[o].toInt() and 0xff) or ((blob[o + 1].toInt() and 0xff) shl 8) or
                ((blob[o + 2].toInt() and 0xff) shl 16) or ((blob[o + 3].toInt() and 0xff) shl 24)
            if (v == 0) 1 else v
        }
    }

    /** The standard kind to show instead of [aidName], or null to keep the aid's own text. */
    fun stockKind(environmentId: Int?, aidName: String?): Kind? {
        val kind = Kind.entries.firstOrNull { it.environmentId == environmentId } ?: return null
        val name = aidName?.trim()?.lowercase() ?: return kind
        return kind.takeIf { name in stock.getValue(kind) }
    }
}

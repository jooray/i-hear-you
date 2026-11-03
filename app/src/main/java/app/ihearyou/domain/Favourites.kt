package app.ihearyou.domain

import android.content.Context
import app.ihearyou.protocol.Eq
import app.ihearyou.protocol.SoundEnhancer
import app.ihearyou.protocol.SoundPresets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * A named setting of the pair the wearer can return to with one tap: the program, each aid's
 * microphone level, and the program's bass/middle/treble, preset and (top-tier aids) feature
 * positions. Parts left null were not known when it was saved and are not applied.
 *
 * [volumes] is keyed by [Favourites.aidKey], so a favourite keeps working when an address is
 * shown in another order.
 */
data class Favourite(
    val id: String,
    val name: String,
    val program: Int?,
    val volumes: Map<String, Int>,
    val eq: Eq?,
    val preset: SoundPresets.Kind?,
    val enhancer: List<SoundEnhancer.Position> = emptyList(),
    val createdAt: Long = 0L,
)

/** The pair's current values, in the same terms as a [Favourite]; null where unknown. */
data class PairSnapshot(
    val program: Int?,
    val volumes: Map<String, Int>,
    val eq: Eq?,
    val preset: SoundPresets.Kind?,
    val enhancer: List<SoundEnhancer.Position>,
)

/** The parts of a favourite, in the order they are applied. */
enum class FavouritePart { PROGRAM, VOLUME, SOUND, ENHANCER }

/** DONE: set or already set; SKIPPED: these aids cannot do it now; FAILED: the aid did not confirm. */
enum class PartResult { DONE, SKIPPED, FAILED }

/** Progress and result of applying one favourite. */
data class FavouriteRun(
    val id: String,
    val name: String,
    val running: Boolean,
    val current: FavouritePart?,
    val results: Map<FavouritePart, PartResult> = emptyMap(),
    val finishedAt: Long = 0L,
) {
    val failed: List<FavouritePart> get() = results.filterValues { it == PartResult.FAILED }.keys.sorted()
    val skipped: List<FavouritePart> get() = results.filterValues { it == PartResult.SKIPPED }.keys.sorted()
}

object Favourites {

    fun aidKey(serial: String?, address: String): String = serial?.takeIf { it.isNotBlank() } ?: address.uppercase()

    fun capture(id: String, name: String, s: PairSnapshot, now: Long): Favourite = Favourite(
        id = id,
        name = name.trim(),
        program = s.program,
        volumes = s.volumes,
        eq = s.eq,
        preset = s.preset,
        enhancer = s.enhancer,
        createdAt = now,
    )

    /** Every part the favourite holds equals the current value; an unknown current value never matches. */
    fun matches(f: Favourite, s: PairSnapshot): Boolean {
        if (f.program == null && f.volumes.isEmpty() && f.eq == null && f.preset == null) return false
        if (f.program != null && f.program != s.program) return false
        for ((key, level) in f.volumes) if (s.volumes[key] != level) return false
        if (f.preset != null) {
            if (s.preset != f.preset) return false
        } else if (f.eq != null && s.eq != f.eq) {
            return false
        }
        for (position in f.enhancer) if (position !in s.enhancer) return false
        return true
    }

    fun toJson(list: List<Favourite>): String = JSONArray().apply {
        list.forEach { f ->
            put(
                JSONObject().apply {
                    put("id", f.id)
                    put("name", f.name)
                    f.program?.let { put("program", it) }
                    put("volumes", JSONObject().apply { f.volumes.forEach { (k, v) -> put(k, v) } })
                    f.eq?.let { put("eq", JSONObject().put("bass", it.bass).put("middle", it.middle).put("treble", it.treble)) }
                    f.preset?.let { put("preset", it.name) }
                    put("enhancer", JSONArray().apply { f.enhancer.forEach { put(it.name) } })
                    put("created", f.createdAt)
                },
            )
        }
    }.toString()

    /** Entries that cannot be read are dropped; unknown preset or position names are ignored. */
    fun fromJson(text: String?): List<Favourite> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                val volumes = o.optJSONObject("volumes")?.let { v -> v.keys().asSequence().associateWith { v.getInt(it) } }.orEmpty()
                val eq = o.optJSONObject("eq")?.let { Eq(it.getInt("bass"), it.getInt("middle"), it.getInt("treble")) }
                val preset = o.optString("preset", "").takeIf { it.isNotEmpty() }
                    ?.let { n -> SoundPresets.Kind.entries.firstOrNull { it.name == n } }
                val enhancer = o.optJSONArray("enhancer")?.let { a ->
                    (0 until a.length()).mapNotNull { j -> SoundEnhancer.Position.entries.firstOrNull { it.name == a.getString(j) } }
                }.orEmpty()
                Favourite(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    program = if (o.has("program")) o.getInt("program") else null,
                    volumes = volumes,
                    eq = eq,
                    preset = preset,
                    enhancer = enhancer,
                    createdAt = o.optLong("created", 0L),
                )
            }.getOrNull()
        }
    }
}

/**
 * Favourites on the phone, one list per hearing system (the pair's aid keys, sorted). Each aid's
 * serial is remembered by address, so the list is found again while an aid is out of range.
 */
class FavouriteStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("favourites", Context.MODE_PRIVATE)

    fun aidKey(address: String, serial: String?): String {
        val known = serial?.takeIf { it.isNotBlank() }
        val slot = "serial.${address.uppercase()}"
        if (known != null) {
            if (prefs.getString(slot, null) != known) prefs.edit().putString(slot, known).apply()
            return known
        }
        return Favourites.aidKey(prefs.getString(slot, null), address)
    }

    fun systemKey(keys: List<String>): String = keys.sorted().joinToString("+")

    fun load(system: String): List<Favourite> = Favourites.fromJson(prefs.getString("system.$system", null))

    fun save(system: String, list: List<Favourite>) {
        prefs.edit().putString("system.$system", Favourites.toJson(list)).apply()
        changed.update { it + 1 }
    }

    companion object {
        val changed = MutableStateFlow(0)
    }
}

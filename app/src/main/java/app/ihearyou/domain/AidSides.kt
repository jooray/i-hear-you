package app.ihearyou.domain

import android.content.Context
import app.ihearyou.service.RemoteService

/**
 * Button mappings and voice-note endings belong to an ear, not to a Bluetooth address: the
 * address changes whenever an aid is re-paired or replaced, the side it reports does not.
 *
 * The side is read from the aid on every connect. The last value per address is remembered so
 * an aid that is out of range, or pressed before the read finishes, still resolves to its ear.
 */
object AidSides {

    const val LEFT = "LEFT"
    const val RIGHT = "RIGHT"

    /** [raw] as published in [RemoteService.sides]: "Left", "Right", or "?n" for an unknown value. */
    fun normalize(raw: String?): String? = when (raw?.lowercase()) {
        "left" -> LEFT
        "right" -> RIGHT
        else -> null
    }

    /** LEFT, RIGHT, or null while this aid's side has never been read. */
    fun of(context: Context, address: String): String? =
        normalize(RemoteService.sides.value[address])
            ?: prefs(context).getString(address.uppercase(), null)

    fun remember(context: Context, address: String, raw: String) {
        val side = normalize(raw) ?: return
        prefs(context).edit().putString(address.uppercase(), side).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("aid_sides", Context.MODE_PRIVATE)
}

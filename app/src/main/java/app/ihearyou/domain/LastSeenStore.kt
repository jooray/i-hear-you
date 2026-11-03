package app.ihearyou.domain

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * When each aid was last linked, kept on the phone only: a time per aid address, written when
 * the link drops or the service stops. No place is stored.
 */
class LastSeenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("last_seen", Context.MODE_PRIVATE)

    fun record(address: String, at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(address.uppercase(), at).apply()
        changed.update { it + 1 }
    }

    fun get(address: String): Long? = prefs.getLong(address.uppercase(), 0L).takeIf { it > 0L }

    companion object {
        /** Bumped on every write, so a visible finder reads the new time. */
        val changed = MutableStateFlow(0)
    }
}

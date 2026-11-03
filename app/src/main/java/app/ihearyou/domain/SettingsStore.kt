package app.ihearyou.domain

import android.content.Context
import app.ihearyou.ui.theme.ThemeMode

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun theme(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString("theme", ThemeMode.SYSTEM.name)!!) }
            .getOrDefault(ThemeMode.SYSTEM)

    fun setTheme(mode: ThemeMode) {
        prefs.edit().putString("theme", mode.name).apply()
    }

    /** Shows a synthetic pair so a stranger can try the instrument without bonding an aid. */
    fun previewPair(): Boolean = prefs.getBoolean("preview_pair", false)

    fun setPreviewPair(on: Boolean) {
        prefs.edit().putBoolean("preview_pair", on).apply()
    }
}

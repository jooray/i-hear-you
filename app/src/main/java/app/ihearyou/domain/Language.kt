package app.ihearyou.domain

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * In-app language. "system" follows the phone and falls back to English for any language the
 * app has no strings for. Android 13+ stores the choice itself (and lists it under the app's
 * language settings); Android 12 gets it by wrapping the context.
 */
object Language {

    /** Tag to native name, in the order shown in Setup. */
    val choices = listOf(
        "system" to null,
        "en" to "English",
        "sk" to "Slovenčina",
        "cs" to "Čeština",
        "es" to "Español",
        "de" to "Deutsch",
    )

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // the choice may also be made in Android's own per-app language settings
            val tag = context.getSystemService(LocaleManager::class.java).applicationLocales[0]?.language
            return choices.firstOrNull { it.first == tag }?.first ?: "system"
        }
        return prefs(context).getString("language", "system") ?: "system"
    }

    fun set(context: Context, tag: String) {
        prefs(context).edit().putString("language", tag).commit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag == "system") LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
    }

    /** For Android 12: apply the stored choice to a base context. A no-op on 13+. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = current(base)
        if (tag == "system") return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }
}

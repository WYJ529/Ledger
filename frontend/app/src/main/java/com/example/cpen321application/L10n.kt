package com.example.cpen321application

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import java.util.Locale

/** Interface languages. [System] follows the device language when it is one of the others. */
internal enum class AppLanguage(val tag: String, val label: String) {
    System("system", "System default"),
    English("en", "English"),
    SimplifiedChinese("zh-Hans", "简体中文"),
    TraditionalChinese("zh-Hant", "繁體中文"),
    French("fr", "Français"),
}

/**
 * Gettext-style translation: UI code keeps its English text and wraps it in [t] or [tf]; the English
 * text is the lookup key and the fallback. The selection is Compose state, so changing the language
 * recomposes every screen immediately.
 */
internal object L10n {
    private const val PREFERENCES = "app_language"
    private const val KEY = "language"

    var selected by mutableStateOf(AppLanguage.System)
        private set

    /** The language actually shown, after resolving [AppLanguage.System]. */
    val effective: AppLanguage get() = if (selected == AppLanguage.System) fromLocale(Locale.getDefault()) else selected

    /** Locale for dates and month names in the shown language. */
    val locale: Locale get() = when (effective) {
        AppLanguage.SimplifiedChinese -> Locale.SIMPLIFIED_CHINESE
        AppLanguage.TraditionalChinese -> Locale.TRADITIONAL_CHINESE
        AppLanguage.French -> Locale.FRENCH
        AppLanguage.System, AppLanguage.English -> Locale.ENGLISH
    }

    fun load(context: Context) {
        val tag = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null)
        selected = AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.System
    }

    fun select(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putString(KEY, language.tag) }
        selected = language
    }

    fun fromLocale(locale: Locale): AppLanguage = when (locale.language) {
        "fr" -> AppLanguage.French
        "zh" -> if (locale.script == "Hant" || (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO")))
            AppLanguage.TraditionalChinese else AppLanguage.SimplifiedChinese
        else -> AppLanguage.English
    }

    fun translate(text: String, language: AppLanguage = effective): String = when (language) {
        AppLanguage.SimplifiedChinese -> ZH_HANS[text]
        AppLanguage.TraditionalChinese -> ZH_HANT[text]
        AppLanguage.French -> FR[text]
        AppLanguage.System, AppLanguage.English -> null
    } ?: text
}

/** Translates fixed interface text into the selected language. */
internal fun t(text: String): String = L10n.translate(text)

/** Translates a format pattern such as "Paid by %1\$s" and fills in its arguments. */
internal fun tf(pattern: String, vararg args: Any?): String = String.format(Locale.ROOT, L10n.translate(pattern), *args)

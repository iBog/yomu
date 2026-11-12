package com.yomu.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import com.yomu.core.Constants
import java.util.Locale

/**
 * The language the app's own screens, overlay and notification are written in. Not the translation's
 * source or target language. Until the reader chooses one, it follows the device: Russian on a Russian
 * device, English otherwise.
 */
enum class AppLanguage(val code: String, val nativeName: String) {
    ENGLISH("en", "English"),
    RUSSIAN("ru", "Русский");

    companion object {
        const val PREFS_NAME = "yomu_prefs"

        fun fromCode(code: String?): AppLanguage? = entries.firstOrNull { it.code == code }

        fun load(prefs: SharedPreferences): AppLanguage =
            fromCode(runCatching { prefs.getString(Constants.PREF_APP_LANGUAGE, null) }.getOrNull())
                ?: if (Locale.getDefault().language == RUSSIAN.code) RUSSIAN else ENGLISH

        fun save(prefs: SharedPreferences, language: AppLanguage) =
            prefs.edit().putString(Constants.PREF_APP_LANGUAGE, language.code).apply()
    }
}

/**
 * This context with the chosen [AppLanguage] applied, for `attachBaseContext` of every component that
 * shows text. Per-app system locales need API 33 and the app supports 26, so the language is applied here.
 */
fun Context.withAppLanguage(): Context {
    val language = AppLanguage.load(getSharedPreferences(AppLanguage.PREFS_NAME, Context.MODE_PRIVATE))
    val configuration = Configuration(resources.configuration).apply { setLocale(Locale(language.code)) }
    return createConfigurationContext(configuration)
}

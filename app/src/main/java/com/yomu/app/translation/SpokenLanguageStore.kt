package com.yomu.app.translation

import android.content.SharedPreferences
import com.yomu.core.Constants
import com.yomu.core.SourceLanguage
import com.yomu.core.SpokenLanguage
import javax.inject.Inject

/** The source language the reader named (#444); null is Auto, which is also what an unknown or wrongly typed stored value means. */
class SpokenLanguageStore @Inject constructor(private val prefs: SharedPreferences) {

    fun load(): SpokenLanguage? =
        SpokenLanguage.fromCode(runCatching { prefs.getString(Constants.PREF_SPOKEN_LANGUAGE, null) }.getOrNull())

    fun save(language: SpokenLanguage?) =
        prefs.edit().putString(Constants.PREF_SPOKEN_LANGUAGE, language?.code).apply()

    /** The language the prompt may name: the choice, unless the OCR model in use reads another script (picked in Settings), which makes it Auto. */
    fun effective(script: SourceLanguage): SpokenLanguage? = load()?.takeIf { it.script == script }
}

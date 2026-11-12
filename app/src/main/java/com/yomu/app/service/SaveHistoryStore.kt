package com.yomu.app.service

import android.content.SharedPreferences

/** Whether a translated page is saved to Recent. On unless the reader turns it off; what is already saved stays. */
class SaveHistoryStore(private val prefs: SharedPreferences) {

    fun load(): Boolean = runCatching { prefs.getBoolean(KEY, DEFAULT) }.getOrDefault(DEFAULT)

    fun save(enabled: Boolean) = prefs.edit().putBoolean(KEY, enabled).apply()

    companion object {
        const val KEY = "save_history"
        const val DEFAULT = true
    }
}

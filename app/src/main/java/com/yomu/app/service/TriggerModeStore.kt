package com.yomu.app.service

import android.content.SharedPreferences

/** Where the reader starts a translation: the floating button over the page, or the notification in the shade. */
enum class TriggerMode { FLOATING_BUTTON, NOTIFICATION }

/** The reader's trigger mode: the floating button unless changed, and again for an unknown or wrongly typed value. */
class TriggerModeStore(private val prefs: SharedPreferences) {

    fun load(): TriggerMode = runCatching { prefs.getString(KEY, null) }.getOrNull()
        ?.let { stored -> TriggerMode.entries.firstOrNull { it.name == stored } }
        ?: DEFAULT

    fun save(mode: TriggerMode) = prefs.edit().putString(KEY, mode.name).apply()

    companion object {
        const val KEY = "trigger_mode"
        val DEFAULT = TriggerMode.FLOATING_BUTTON
    }
}

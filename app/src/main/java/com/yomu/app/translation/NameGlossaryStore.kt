package com.yomu.app.translation

import android.content.SharedPreferences
import com.google.gson.Gson
import com.yomu.core.GlossaryEntry
import javax.inject.Inject

/**
 * The reader's name glossary (#327), kept as one JSON array: empty unless entries are added, and
 * empty again for an unreadable stored value. Its switch (#401) pauses the glossary without touching
 * the entries: on unless turned off, and on again for a wrongly typed stored value.
 */
// One JSON string in SharedPreferences; move to Room if the glossary gets per-series scope or grows to thousands of entries.
class NameGlossaryStore @Inject constructor(private val prefs: SharedPreferences) {

    /** The entries in the order they were added. A stored entry missing a field makes the whole value unreadable. */
    fun load(): List<GlossaryEntry> = runCatching {
        // Gson leaves a missing field null despite the type, so isNotBlank throws for it.
        gson.fromJson(prefs.getString(KEY, null) ?: return emptyList(), Array<GlossaryEntry>::class.java)
            .toList()
            .onEach { require(it.term.isNotBlank() && it.rendering.isNotBlank()) }
    }.getOrDefault(emptyList())

    /**
     * Saves [term] as [rendering], both trimmed, in place of the entry for [replacing] when given.
     * A term already listed has its entry edited rather than a second one added. An edited entry keeps
     * its place; a new one goes last. Returns false, saving nothing, when either field is blank.
     */
    fun save(term: String, rendering: String, replacing: String? = null): Boolean {
        val entry = GlossaryEntry(term.trim(), rendering.trim())
        if (entry.term.isEmpty() || entry.rendering.isEmpty()) return false
        val entries = load().toMutableList()
        val edited: (GlossaryEntry) -> Boolean = { it.term == replacing || it.term == entry.term }
        val at = entries.indexOfFirst(edited)
        entries.removeAll(edited)
        if (at < 0) entries += entry else entries.add(at, entry)
        write(entries)
        return true
    }

    fun delete(term: String) = write(load().filter { it.term != term })

    /** The entries a page is translated with: [load] while the glossary is on, nothing while it is off. */
    fun entriesInUse(): List<GlossaryEntry> = if (isEnabled()) load() else emptyList()

    fun isEnabled(): Boolean = runCatching { prefs.getBoolean(ENABLED_KEY, ENABLED_DEFAULT) }.getOrDefault(ENABLED_DEFAULT)

    fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean(ENABLED_KEY, enabled).apply()

    private fun write(entries: List<GlossaryEntry>) = prefs.edit().putString(KEY, gson.toJson(entries)).apply()

    companion object {
        const val KEY = "name_glossary"
        const val ENABLED_KEY = "name_glossary_enabled"
        const val ENABLED_DEFAULT = true
        private val gson = Gson()
    }
}

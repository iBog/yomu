package com.yomu.app.ui.settings

import androidx.lifecycle.ViewModel
import com.yomu.app.translation.NameGlossaryStore
import com.yomu.core.GlossaryEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** The Name glossary screen's switch and entries, written through to [NameGlossaryStore] as the reader edits them. */
@HiltViewModel
class NameGlossaryViewModel @Inject constructor(private val store: NameGlossaryStore) : ViewModel() {

    private val _enabled = MutableStateFlow(store.isEnabled())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _entries = MutableStateFlow(store.load())
    val entries: StateFlow<List<GlossaryEntry>> = _entries.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        store.setEnabled(enabled)
        _enabled.value = store.isEnabled()
    }

    /** Saves the entry under [NameGlossaryStore.save]'s rules; false when it was refused. */
    fun save(term: String, rendering: String, replacing: String?): Boolean =
        store.save(term, rendering, replacing).also { refresh() }

    fun delete(term: String) {
        store.delete(term)
        refresh()
    }

    private fun refresh() {
        _entries.value = store.load()
    }
}

package com.yomu.app.ui.settings

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomu.app.db.entities.ModelType
import com.yomu.app.service.ModelManager
import com.yomu.app.service.ModelSlotSelection
import com.yomu.app.service.SourceLanguageSelection
import com.yomu.app.ui.UiText
import com.yomu.core.Constants
import com.yomu.core.SpokenLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The source language: Auto, or a language whose text-reading model is downloaded here when it is missing. */
@HiltViewModel
class SourceLanguageViewModel @Inject constructor(
    private val selection: SourceLanguageSelection,
    private val slotSelection: ModelSlotSelection,
    private val models: ModelManager,
    private val prefs: SharedPreferences
) : ViewModel() {
    private val _spoken = MutableStateFlow(selection.current())
    val spoken: StateFlow<SpokenLanguage?> = _spoken.asStateFlow()

    private val _hint = MutableStateFlow(hint())

    /** Which OCR model is in use for the source language, for the line under the setting. */
    val ocrHint: StateFlow<UiText> = _hint.asStateFlow()

    // Held in a field: SharedPreferences keeps listeners weakly. A model that finishes downloading takes over by writing this key.
    private val ocrListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Constants.PREF_OCR_MODEL) _hint.value = hint()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(ocrListener)
    }

    fun choose(language: SpokenLanguage?) {
        _spoken.value = language
        viewModelScope.launch {
            val download = selection.choose(language, models.deviceTotalMemBytes())
            _hint.value = hint()
            download?.let { slotSelection.download(it) }
            _hint.value = hint()
        }
    }

    private fun hint(): UiText {
        val readers = slotSelection.deliverables(ModelType.OCR)
        fun named(id: String?) = readers.firstOrNull { it.id == id }
        return ocrHint(_spoken.value, named(slotSelection.selectedId(ModelType.OCR)), named(slotSelection.pendingId(ModelType.OCR)))
    }

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(ocrListener)
    }
}

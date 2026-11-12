package com.yomu.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomu.app.service.ModelManager
import com.yomu.app.service.ModelSlotSelection
import com.yomu.app.service.SourceLanguageSelection
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
    private val models: ModelManager
) : ViewModel() {
    private val _spoken = MutableStateFlow(selection.current())
    val spoken: StateFlow<SpokenLanguage?> = _spoken.asStateFlow()

    fun choose(language: SpokenLanguage?) {
        _spoken.value = language
        viewModelScope.launch {
            selection.choose(language, models.deviceTotalMemBytes())?.let { slotSelection.download(it) }
        }
    }
}

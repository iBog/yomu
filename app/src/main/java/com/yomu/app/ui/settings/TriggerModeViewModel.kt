package com.yomu.app.ui.settings

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.yomu.app.service.TriggerMode
import com.yomu.app.service.TriggerModeStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Where reading starts a translation; a running session switches as soon as the choice is saved. */
@HiltViewModel
class TriggerModeViewModel @Inject constructor(sharedPreferences: SharedPreferences) : ViewModel() {

    private val store = TriggerModeStore(sharedPreferences)
    private val _mode = MutableStateFlow(store.load())
    val mode: StateFlow<TriggerMode> = _mode.asStateFlow()

    fun choose(mode: TriggerMode) {
        store.save(mode)
        _mode.value = store.load()
    }
}

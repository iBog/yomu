package com.yomu.app.ui.settings

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.yomu.app.service.SaveHistoryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Whether translated pages are remembered in Recent. */
@HiltViewModel
class SaveHistoryViewModel @Inject constructor(sharedPreferences: SharedPreferences) : ViewModel() {

    private val store = SaveHistoryStore(sharedPreferences)
    private val _enabled = MutableStateFlow(store.load())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(enabled: Boolean) {
        store.save(enabled)
        _enabled.value = store.load()
    }
}

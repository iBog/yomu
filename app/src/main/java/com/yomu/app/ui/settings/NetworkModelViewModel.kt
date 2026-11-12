package com.yomu.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomu.app.translation.network.ModelsResult
import com.yomu.app.translation.network.NetworkModelClient
import com.yomu.app.translation.network.NetworkModelStore
import com.yomu.app.translation.network.TranslationBackend
import com.yomu.app.translation.network.serverBase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What asking the server for its models has come to, as the Network Model section shows it. */
sealed interface ModelsState {
    /** No address to ask yet. */
    data object Idle : ModelsState
    data object Loading : ModelsState

    /** The server listed its models; the reader picks one. */
    data class Listed(val models: List<String>) : ModelsState

    /** The server answered but lists no models; the reader types the model's name. */
    data object Manual : ModelsState

    /** The server could not be used; [code] is the client's `network_` code. */
    data class Failed(val code: String) : ModelsState

    /** Whether a server answered at the address: it listed its models, or answered without a list. */
    val answered: Boolean get() = this is Listed || this === Manual
}

data class NetworkUiState(
    val url: String = "",
    val apiKey: String = "",
    val model: String = "",
    val backend: TranslationBackend = TranslationBackend.DEVICE,
    val models: ModelsState = ModelsState.Idle
) {
    /** Whether an address and a model are set, so the network model can be chosen. */
    val ready: Boolean get() = url.serverBase() != null && model.isNotBlank()

    /** Whether pages go to the server. */
    val active: Boolean get() = backend == TranslationBackend.NETWORK && ready
}

/** The Network Model section: the server's address, key and model, and which engine translates. */
@HiltViewModel
class NetworkModelViewModel @Inject constructor(
    private val store: NetworkModelStore,
    private val client: NetworkModelClient
) : ViewModel() {
    private val _state = MutableStateFlow(store.config().let {
        NetworkUiState(it.url, it.apiKey, it.model, store.backend())
    })
    val state: StateFlow<NetworkUiState> = _state.asStateFlow()
    private var loading: Job? = null

    init {
        if (_state.value.url.isNotBlank()) load(settle = 0L)
    }

    fun setUrl(url: String) {
        store.saveUrl(url)
        _state.update { it.copy(url = url, models = ModelsState.Idle) }
        load(SETTLE_MS)
    }

    fun setApiKey(key: String) {
        store.saveApiKey(key)
        _state.update { it.copy(apiKey = key) }
        load(SETTLE_MS)
    }

    fun setModel(model: String) {
        store.saveModel(model)
        _state.update { it.copy(model = model) }
    }

    fun setBackend(backend: TranslationBackend) {
        store.saveBackend(backend)
        _state.update { it.copy(backend = backend) }
    }

    /** Asks for the models once typing has paused for [settle] ms, so a half-typed address is not sent as it is typed. */
    private fun load(settle: Long) {
        loading?.cancel()
        val (url, key) = _state.value.let { it.url to it.apiKey }
        if (url.isBlank()) return
        loading = viewModelScope.launch {
            delay(settle)
            _state.update { it.copy(models = ModelsState.Loading) }
            val models = when (val result = client.listModels(url, key)) {
                is ModelsResult.Listed -> ModelsState.Listed(result.models)
                ModelsResult.Unlisted -> ModelsState.Manual
                is ModelsResult.Failed -> ModelsState.Failed(result.code)
            }
            _state.update { it.copy(models = models) }
        }
    }

    private companion object {
        const val SETTLE_MS = 700L
    }
}

package com.yomu.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.yomu.app.R
import com.yomu.app.db.entities.ModelType
import com.yomu.app.translation.network.NetworkModelStore
import com.yomu.app.translation.network.TranslationBackend
import com.yomu.app.ui.theme.PaperAction
import com.yomu.app.ui.theme.PaperSurface
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Which engine translates, and whether the network one has an address and a model to be chosen. */
data class BackendState(val backend: TranslationBackend, val networkReady: Boolean) {
    /** Whether pages go to the server: chosen, and set up. */
    val active: Boolean get() = backend == TranslationBackend.NETWORK && networkReady
}

@HiltViewModel
class BackendViewModel @Inject constructor(private val store: NetworkModelStore) : ViewModel() {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<BackendState> = _state.asStateFlow()

    fun choose(backend: TranslationBackend) {
        store.saveBackend(backend)
        _state.value = read()
    }

    private fun read() = BackendState(store.backend(), store.config().complete)
}

/** Providers: what translates a page, and its models: the one on the phone or the one on a server. */
@Composable
fun ProvidersSettings(settings: SettingsViewModel, onBack: () -> Unit, networkViewModel: NetworkModelViewModel = hiltViewModel()) {
    val state by settings.uiState.collectAsState()
    val network by networkViewModel.state.collectAsState()
    SettingsPage(stringResource(SettingsSection.Providers.title), stringResource(SettingsSection.Providers.description), onBack) {
        BackendPanel(BackendState(network.backend, network.ready), networkViewModel::setBackend)
        Dimmed(network.active) { ModelSlot(state, settings, ModelType.LLM, R.string.settings_model_title, R.string.settings_model_description) }
        // Open while it is being set up, so the choice above is never out of reach; once it is set up, only the chosen one is editable.
        NetworkModelPanel(
            network, networkViewModel::setUrl, networkViewModel::setApiKey, networkViewModel::setModel,
            enabled = network.backend == TranslationBackend.NETWORK || !network.ready
        )
    }
}

/** Where the translation models went: Translation only points to Providers. */
@Composable
internal fun ModelsHint(onOpenProviders: () -> Unit) {
    PaperAction(onClick = onOpenProviders, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_models_moved_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.settings_models_moved_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** Which engine translates: the model on the phone or the Network Model. The network one needs an address and a model first. */
@Composable
internal fun BackendPanel(state: BackendState, onChoose: (TranslationBackend) -> Unit) {
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingRow(stringResource(R.string.settings_backend_title), stringResource(R.string.settings_backend_description)) {
                Column(Modifier.selectableGroup()) {
                    BackendRow(R.string.settings_backend_device, null, state.backend == TranslationBackend.DEVICE, true) {
                        onChoose(TranslationBackend.DEVICE)
                    }
                    BackendRow(
                        R.string.settings_backend_network,
                        R.string.settings_backend_network_unset.takeIf { !state.networkReady },
                        state.backend == TranslationBackend.NETWORK,
                        state.networkReady
                    ) { onChoose(TranslationBackend.NETWORK) }
                }
            }
        }
    }
}

@Composable
private fun BackendRow(label: Int, note: Int?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
            note?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

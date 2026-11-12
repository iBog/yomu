package com.yomu.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yomu.app.R
import com.yomu.app.translation.network.TranslationBackend
import com.yomu.app.ui.theme.PaperSurface

/** Which engine translates: the model on the phone or the Network Model. The network one needs an address and a model first. */
@Composable
internal fun BackendPanel(state: NetworkUiState, onChoose: (TranslationBackend) -> Unit) {
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingRow(stringResource(R.string.settings_backend_title), stringResource(R.string.settings_backend_description)) {
                Column(Modifier.selectableGroup()) {
                    BackendRow(R.string.settings_backend_device, null, state.backend == TranslationBackend.DEVICE, true) {
                        onChoose(TranslationBackend.DEVICE)
                    }
                    BackendRow(
                        R.string.settings_backend_network,
                        R.string.settings_backend_network_unset.takeIf { !state.ready },
                        state.backend == TranslationBackend.NETWORK,
                        state.ready
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

/** The server's address, an optional key, and the model: picked from the server's list, or typed when it has none. */
@Composable
internal fun NetworkModelPanel(
    state: NetworkUiState,
    onUrl: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onModel: (String) -> Unit
) {
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_network_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_network_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val failure = (state.models as? ModelsState.Failed)?.code
            OutlinedTextField(
                value = state.url,
                onValueChange = onUrl,
                label = { Text(stringResource(R.string.settings_network_url)) },
                placeholder = { Text(stringResource(R.string.settings_network_url_hint)) },
                singleLine = true,
                isError = failure != null,
                trailingIcon = if (state.models.answered) ({ AnsweredMark() }) else null,
                supportingText = failure?.let { { Text(stringResource(networkFailureRes(it), it)) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = onApiKey,
                label = { Text(stringResource(R.string.settings_network_key)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            when (val models = state.models) {
                ModelsState.Loading -> Text(stringResource(R.string.settings_network_models_loading), style = MaterialTheme.typography.bodySmall)
                is ModelsState.Listed -> ModelDropdown(models.models, state.model, onModel)
                ModelsState.Manual -> OutlinedTextField(
                    value = state.model,
                    onValueChange = onModel,
                    label = { Text(stringResource(R.string.settings_network_model_name)) },
                    supportingText = { Text(stringResource(R.string.settings_network_models_manual_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                else -> Unit
            }
        }
    }
}

/** The server at this address answered, so the address is good. */
@Composable
private fun AnsweredMark() {
    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.settings_network_answered), tint = ANSWERED_GREEN)
}

private val ANSWERED_GREEN = Color(0xFF2E7D32)

@Composable
private fun ModelDropdown(models: List<String>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.settings_network_model), style = MaterialTheme.typography.labelMedium)
        Box {
            AssistChip(
                onClick = { open = true },
                label = { Text(selected.ifBlank { stringResource(R.string.settings_network_model_choose) }) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) }
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                models.forEach { model ->
                    DropdownMenuItem(text = { Text(model) }, onClick = {
                        open = false
                        onPick(model)
                    })
                }
            }
        }
    }
}

/** The text for a `network_` code from the client; the "other" wording takes the code as its argument. */
private fun networkFailureRes(code: String): Int = when (code) {
    "network_unauthorized" -> R.string.settings_network_error_unauthorized
    "network_timeout" -> R.string.settings_network_error_timeout
    "network_bad_url" -> R.string.settings_network_error_bad_url
    "network_unreachable" -> R.string.settings_network_error_unreachable
    else -> R.string.settings_network_error_other
}

package com.yomu.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yomu.app.R
import com.yomu.app.ui.theme.PaperSurface

/** The server's address, an optional key, and the model: picked from the server's list, or typed when it has none. */
@Composable
internal fun NetworkModelPanel(
    state: NetworkUiState,
    onUrl: (String) -> Unit,
    onApiKey: (String) -> Unit,
    onModel: (String) -> Unit,
    onKeepAlive: (Int) -> Unit
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
            KeepAliveDropdown(state.keepAliveMinutes, onKeepAlive)
        }
    }
}

/** How long Ollama keeps the model in memory after a page, so the next page does not wait for it to load. */
@Composable
private fun KeepAliveDropdown(selected: Int, onPick: (Int) -> Unit) {
    val options = KEEP_ALIVE_MINUTES.map { DropdownOption(it, keepAliveLabel(it)) }
    Column {
        Text(stringResource(R.string.settings_network_keep_alive), style = MaterialTheme.typography.labelMedium)
        CompactDropdown(keepAliveLabel(selected), options, isSelected = { it == selected }, onPick = onPick)
        Text(
            stringResource(R.string.settings_network_keep_alive_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val KEEP_ALIVE_MINUTES = listOf(5, 30, 60, 240)

@Composable
private fun keepAliveLabel(minutes: Int): String =
    if (minutes % 60 == 0) pluralStringResource(R.plurals.settings_network_hours, minutes / 60, minutes / 60)
    else pluralStringResource(R.plurals.settings_network_minutes, minutes, minutes)

/** The server at this address answered, so the address is good. */
@Composable
private fun AnsweredMark() {
    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.settings_network_answered), tint = ANSWERED_GREEN)
}

private val ANSWERED_GREEN = Color(0xFF2E7D32)

@Composable
private fun ModelDropdown(models: List<String>, selected: String, onPick: (String) -> Unit) {
    Column {
        Text(stringResource(R.string.settings_network_model), style = MaterialTheme.typography.labelMedium)
        CompactDropdown(
            selectedLabel = selected.ifBlank { stringResource(R.string.settings_network_model_choose) },
            options = models.map { DropdownOption(it, it) },
            isSelected = { it == selected },
            onPick = onPick
        )
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

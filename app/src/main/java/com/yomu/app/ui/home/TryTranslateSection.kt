package com.yomu.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.yomu.app.R
import com.yomu.app.ui.asString
import com.yomu.app.ui.theme.PaperButton
import com.yomu.app.ui.theme.PaperSurface
import com.yomu.core.SpokenLanguage
import com.yomu.core.TargetLanguage

/** Paste text, translate it with the current model, source and target, and read the result. */
@Composable
internal fun TryTranslateSection(
    spoken: SpokenLanguage?,
    target: TargetLanguage,
    viewModel: TryTranslateViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.try_title), style = MaterialTheme.typography.titleLarge)
        PaperSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(R.string.try_input_label)) },
                    // Fixed height: three lines, then it scrolls, so a long paste doesn't push the page down.
                    minLines = 3,
                    maxLines = 3,
                    isError = state.failed,
                    supportingText = if (state.failed) ({ Text(state.error?.asString().orEmpty()) }) else null,
                    modifier = Modifier.fillMaxWidth()
                )
                PaperButton(
                    stringResource(if (state.running) R.string.try_translating else R.string.try_translate),
                    { viewModel.translate(input, spoken, target) },
                    enabled = input.isNotBlank() && !state.running
                )
                state.text?.takeIf { !state.failed }?.let { text ->
                    SelectionContainer { Text(text) }
                    val seconds = stringResource(R.string.try_seconds, state.millis / 1000.0)
                    Text(listOfNotNull(state.note?.asString(), seconds).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

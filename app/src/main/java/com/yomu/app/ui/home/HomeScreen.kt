package com.yomu.app.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yomu.app.R
import com.yomu.app.service.TriggerMode
import com.yomu.app.ui.asString
import com.yomu.app.ui.labelRes
import com.yomu.app.ui.theme.*
import com.yomu.core.TargetLanguage
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onRequestScreenCapture: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val scroll = rememberLazyListState()
    var showAll by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val readingStatus = state.readingStatus
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.home_clear_title)) },
            text = { Text(stringResource(R.string.home_clear_message)) },
            confirmButton = { TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), onClick = { confirmClear = false; viewModel.clearHistory() }) { Text(stringResource(R.string.home_clear)) } },
            dismissButton = { TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), onClick = { confirmClear = false }) { Text(stringResource(R.string.home_keep)) } }
        )
    }
    LazyColumn(
        state = scroll,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(16.dp))
            val label = when (readingStatus) {
                ReadingStatus.On -> stringResource(R.string.home_reading_on)
                ReadingStatus.Off -> stringResource(R.string.home_reading_off)
                ReadingStatus.NotReady -> stringResource(R.string.home_finish_setup_to_read)
            }
            val icon = when (readingStatus) {
                ReadingStatus.On -> Icons.Default.Check
                ReadingStatus.Off -> Icons.Default.PlayArrow
                ReadingStatus.NotReady -> Icons.Default.Warning
            }
            PaperAction(
                onClick = {
                    if (readingStatus == ReadingStatus.On) viewModel.stopService() else onRequestScreenCapture()
                },
                enabled = readingStatus != ReadingStatus.NotReady,
                accented = readingStatus == ReadingStatus.On,
                modifier = Modifier.fillMaxWidth().semantics { role = Role.Switch; stateDescription = label }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(icon, contentDescription = null)
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(when (readingStatus) {
                            ReadingStatus.On -> R.string.home_tap_to_turn_off
                            ReadingStatus.Off -> R.string.home_tap_to_turn_on
                            ReadingStatus.NotReady -> R.string.home_needs_things_first
                        }), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(
                    if (readingStatus == ReadingStatus.On) when (state.triggerMode) {
                        TriggerMode.FLOATING_BUTTON -> R.string.home_hint_floating_button
                        TriggerMode.NOTIFICATION -> R.string.home_hint_notification
                    }
                    else R.string.home_hint_off
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            if (readingStatus != ReadingStatus.NotReady) {
                Spacer(Modifier.height(12.dp))
                CurrentSelection(state)
            }
        }
        state.confirmation?.let { message -> item { PaperSuccess(message.asString(), viewModel::dismissConfirmation) } }
        if (state.serviceError) item {
            PaperError(stringResource(R.string.home_service_error), viewModel::dismissServiceError)
        }
        if (readingStatus == ReadingStatus.NotReady) item {
            PaperSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.home_get_ready), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(when (state.readiness) {
                        Readiness.NeedsModels -> R.string.home_ready_needs_models
                        Readiness.NeedsPermission -> R.string.home_ready_needs_permission
                        Readiness.NeedsBoth -> R.string.home_ready_needs_both
                        Readiness.Ready -> R.string.home_ready_finish
                    }))
                    PaperButton(stringResource(R.string.home_finish_setup), viewModel::openSetup)
                }
            }
        }
        if (readingStatus != ReadingStatus.NotReady) item { TryTranslateSection(state.spoken, state.targetLanguage) }
        item {
            Text(pluralStringResource(R.plurals.home_pages_today, state.pagesTranslatedToday, state.pagesTranslatedToday), style = MaterialTheme.typography.bodySmall)
        }
        if (state.historyLoading || state.historyError || state.hasRead) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (state.translations.isNotEmpty()) TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), onClick = { confirmClear = true }) { Text(stringResource(R.string.home_clear)) }
                }
            }
            if (state.clearError) item {
                PaperError(stringResource(R.string.home_clear_error), viewModel::clearHistory)
            }
            when {
                state.historyLoading -> item { PaperLoading(stringResource(R.string.home_history_loading)) }
                state.historyError -> item { PaperError(stringResource(R.string.home_history_error), viewModel::loadRecentHistory) }
                state.translations.isEmpty() -> item {
                    PaperEmpty(stringResource(if (state.saveHistory) R.string.home_history_empty else R.string.home_history_off))
                }
                else -> {
                    items(if (showAll) state.translations else state.translations.take(3), key = { it.id }) { translation ->
                        var expanded by rememberSaveable(translation.id) { mutableStateOf(false) }
                        PaperAction(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(translation.translatedText, maxLines = if (expanded) Int.MAX_VALUE else 3, style = MaterialTheme.typography.bodyMedium)
                                val translatedAt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(translation.createdAt))
                                val language = TargetLanguage.fromCode(translation.targetLanguage)?.let { stringResource(it.labelRes()) }
                                Text(listOfNotNull(translatedAt, language).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(if (expanded) R.string.home_show_less else R.string.home_read_translation), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    if (state.translations.size > 3) item {
                        PaperButton(stringResource(if (showAll) R.string.home_show_recent_only else R.string.home_show_all), { showAll = !showAll })
                    }
                }
            }
        }
    }
}

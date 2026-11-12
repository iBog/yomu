package com.yomu.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yomu.app.R
import com.yomu.app.ui.theme.PaperButton
import com.yomu.app.ui.theme.PaperSurface
import com.yomu.core.GlossaryEntry

@Composable
fun NameGlossarySettings(viewModel: NameGlossaryViewModel, onBack: () -> Unit) {
    val enabled by viewModel.enabled.collectAsState()
    val entries by viewModel.entries.collectAsState()
    // The entry being edited; a blank term opens the dialog for a new one.
    var editing by remember { mutableStateOf<GlossaryEntry?>(null) }
    SettingsPage(stringResource(SettingsSection.NameGlossary.title), stringResource(SettingsSection.NameGlossary.description), onBack) {
        SwitchPanel(stringResource(R.string.settings_glossary_use_title), stringResource(R.string.settings_glossary_use_description), enabled, viewModel::setEnabled)
        PaperButton(stringResource(R.string.settings_glossary_add), { editing = GlossaryEntry("", "") })
        PaperSurface(Modifier.fillMaxWidth()) {
            Column {
                if (entries.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_glossary_empty),
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                entries.forEach { entry ->
                    Surface(onClick = { editing = entry }, color = Color.Transparent) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(entry.rendering, style = MaterialTheme.typography.titleMedium)
                            Text(entry.term, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    editing?.let { entry ->
        // The term being edited, or null for a new entry.
        val replacing = entry.term.ifEmpty { null }
        GlossaryEntryDialog(
            entry = entry,
            onSave = { term, rendering -> if (viewModel.save(term, rendering, replacing)) editing = null },
            onDelete = replacing?.let { { viewModel.delete(it); editing = null } },
            onDismiss = { editing = null }
        )
    }
}

/** Adds a name when [onDelete] is null, and edits [entry] otherwise. */
@Composable
private fun GlossaryEntryDialog(entry: GlossaryEntry, onSave: (String, String) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var term by remember { mutableStateOf(entry.term) }
    var rendering by remember { mutableStateOf(entry.rendering) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (onDelete == null) R.string.settings_glossary_add else R.string.settings_glossary_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(term, { term = it }, label = { Text(stringResource(R.string.settings_glossary_source)) }, singleLine = true)
                OutlinedTextField(rendering, { rendering = it }, label = { Text(stringResource(R.string.settings_glossary_rendering)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(term, rendering) }, enabled = term.isNotBlank() && rendering.isNotBlank()) { Text(stringResource(R.string.settings_glossary_save)) }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text(stringResource(R.string.settings_delete)) } }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
            }
        }
    )
}

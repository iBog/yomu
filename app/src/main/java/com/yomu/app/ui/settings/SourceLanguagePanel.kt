package com.yomu.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.yomu.app.R
import com.yomu.app.ui.asString
import com.yomu.app.ui.labelRes
import com.yomu.app.ui.theme.PaperSurface
import com.yomu.core.SpokenLanguage

/** Auto, then every source language; the same choice as the source language in Settings > Translation. */
@Composable
internal fun SourceLanguagePanel(viewModel: SourceLanguageViewModel = hiltViewModel()) {
    val selected by viewModel.spoken.collectAsState()
    val hint by viewModel.ocrHint.collectAsState()
    val auto = stringResource(R.string.language_auto)
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingRow(stringResource(R.string.settings_source_language_title), stringResource(R.string.settings_source_language_description)) {
                Column {
                    CompactDropdown(
                        selectedLabel = selected?.let { stringResource(it.labelRes()) } ?: auto,
                        options = listOf<SpokenLanguage?>(null).plus(SpokenLanguage.entries).map { DropdownOption(it, it?.let { l -> stringResource(l.labelRes()) } ?: auto) },
                        isSelected = { it == selected },
                        onPick = viewModel::choose
                    )
                    Text(hint.asString(), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

package com.yomu.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import com.yomu.app.R
import com.yomu.app.ui.labelRes
import com.yomu.app.ui.theme.PaperSurface
import com.yomu.core.SpokenLanguage

/** Auto, then every source language; the same choice as Home's "From" chip. */
@Composable
internal fun SourceLanguagePanel(viewModel: SourceLanguageViewModel = hiltViewModel()) {
    val selected by viewModel.spoken.collectAsState()
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingRow(stringResource(R.string.settings_source_language_title), stringResource(R.string.settings_source_language_description)) {
                Column(Modifier.selectableGroup()) {
                    listOf<SpokenLanguage?>(null).plus(SpokenLanguage.entries).forEach { language ->
                        Row(
                            Modifier.fillMaxWidth().selectable(language == selected, role = Role.RadioButton, onClick = { viewModel.choose(language) }),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = language == selected, onClick = null)
                            Text(
                                stringResource(language?.labelRes() ?: R.string.language_auto),
                                Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

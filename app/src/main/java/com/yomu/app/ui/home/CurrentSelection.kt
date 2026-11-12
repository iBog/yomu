package com.yomu.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yomu.app.ui.asString
import com.yomu.app.ui.theme.PaperSurface

/** What pages are translated with right now: the languages, and the model on the phone or on a server. Changed in Settings > Translation. */
@Composable
internal fun CurrentSelection(state: HomeUiState) {
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(state.languagesSummary().asString(), style = MaterialTheme.typography.titleSmall)
            Text(
                state.engineSummary().asString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

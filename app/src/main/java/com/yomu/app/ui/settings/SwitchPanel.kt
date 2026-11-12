package com.yomu.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yomu.app.ui.theme.PaperSurface

/** A titled on/off switch in its own paper card; the whole row toggles it. */
@Composable
internal fun SwitchPanel(title: String, description: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    PaperSurface(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = onChange).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}

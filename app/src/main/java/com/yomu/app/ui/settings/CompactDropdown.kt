package com.yomu.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One choice in a [CompactDropdown]; a [note] says why it can't be picked and greys it out. */
internal class DropdownOption<T>(val value: T, val label: String, val note: String? = null)

/**
 * A choice as a small chip that opens a short list. The rows are as tight as a line of text with a little padding,
 * where Material's menu item is 48 dp, so a dozen languages fit on one screen.
 */
@Composable
internal fun <T> CompactDropdown(
    selectedLabel: String,
    options: List<DropdownOption<T>>,
    isSelected: (T) -> Boolean,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        AssistChip(
            onClick = { open = true },
            enabled = enabled,
            label = { Text(selectedLabel, maxLines = 1) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                val pickable = option.note == null
                val selected = isSelected(option.value)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = pickable) {
                            open = false
                            onPick(option.value)
                        }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                !pickable -> MaterialTheme.colorScheme.onSurfaceVariant
                                selected -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                        option.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (selected) Icon(Icons.Default.Check, contentDescription = null, Modifier.padding(start = 12.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

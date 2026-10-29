package com.yomu.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yomu.app.ui.theme.*

enum class SettingsSection(val route: String, val title: String, val description: String) {
    Translation("settings/translation", "Translation", "How Yomu translates the Japanese into your language, on your device."),
    NameGlossary("settings/name-glossary", "Name glossary", "Names Yomu always translates the same way."),
    Pipeline("settings/pipeline", "Pipeline", "The models that find the speech bubbles and read the Japanese."),
    Typesetting("settings/typesetting", "Typesetting", "How translated text is set inside the bubbles."),
    Performance("settings/performance", "Performance", "What translation costs this phone, and how much the model may use."),
    Appearance("settings/appearance", "Appearance", "How Yomu looks and where you start a translation.")
}

@Composable
fun SettingsScreen(onOpen: (SettingsSection) -> Unit) {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    SettingsPage("Settings") {
        PaperSurface(Modifier.fillMaxWidth()) {
            Column {
                SettingsSection.entries.forEach { section ->
                    SettingsEntry(section.title, section.description, onClick = { onOpen(section) })
                }
                SettingsEntry("Providers", "Coming soon", enabled = false)
                SettingsEntry("About", "Yomu v$version")
            }
        }
    }
}

@Composable
private fun SettingsEntry(title: String, description: String, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val row: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) LocalContentColor.current else muted)
                Text(description, style = MaterialTheme.typography.bodySmall, color = muted)
            }
            if (onClick != null) Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
        }
    }
    if (onClick == null) row() else Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, content = row)
}

@Composable
internal fun SettingsPage(title: String, description: String? = null, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { IconButton(onClick = it) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } }
            Text(title, style = MaterialTheme.typography.headlineLarge)
        }
        description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        content()
    }
}

@Composable
internal fun SettingRow(label: String, description: String, control: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val text: @Composable () -> Unit = {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (maxWidth < 600.dp) {
            Column { text(); Spacer(Modifier.height(8.dp)); control() }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { text() }
                Spacer(Modifier.width(16.dp))
                Box(Modifier.weight(1f)) { control() }
            }
        }
    }
}

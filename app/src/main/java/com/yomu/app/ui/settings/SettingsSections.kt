package com.yomu.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yomu.app.db.entities.ModelType
import com.yomu.app.detection.DetectionThresholdStore
import com.yomu.app.service.TriggerMode
import com.yomu.app.ui.theme.*
import com.yomu.core.TargetLanguage

@Composable
fun TranslationSettings(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    SettingsPage(SettingsSection.Translation.title, SettingsSection.Translation.description, onBack) {
        TargetLanguagePanel(state.targetLanguage, state::languageUnavailable, viewModel::setTargetLanguage)
        state.languageOffer?.let {
            LanguageOfferDialog(it, state.targetLanguage, viewModel::acceptLanguageOffer, viewModel::declineLanguageOffer)
        }
        ModelSlot(state, viewModel, ModelType.LLM, "Model", "The on-device model that translates each bubble.")
        state.recoveryWarning?.let { RecoveryWarning(it) }
        AdvancedGenerationPanel(
            generation = state.generation,
            defaults = state.generationDefaults,
            overridden = state.generationOverridden,
            onChange = viewModel::setGeneration,
            onReset = viewModel::resetGeneration
        )
        PageRecallPanel(state.pageRecall, viewModel::setPageRecall)
    }
}

@Composable
private fun TargetLanguagePanel(
    selected: TargetLanguage,
    unavailable: (TargetLanguage) -> String?,
    onChange: (TargetLanguage) -> Unit
) {
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingRow("Language", "The language pages are translated into. English only models write English alone.") {
                Column(Modifier.selectableGroup()) {
                    TargetLanguage.entries.forEach { language ->
                        val reason = unavailable(language)
                        Row(
                            Modifier.fillMaxWidth().selectable(
                                language == selected, enabled = reason == null, role = Role.RadioButton, onClick = { onChange(language) }
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = language == selected, onClick = null, enabled = reason == null)
                            Column {
                                Text(
                                    language.promptName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (reason == null) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                reason?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageRecallPanel(enabled: Boolean, onChange: (Boolean) -> Unit) {
    PaperSurface(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = onChange).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Page recall", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Shows a page already translated while reading at once, instead of translating it again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}

@Composable
fun PipelineSettings(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    SettingsPage(SettingsSection.Pipeline.title, SettingsSection.Pipeline.description, onBack) {
        ModelSlot(state, viewModel, ModelType.DETECTION, "Detection", "Finds the speech bubbles on the page.")
        DetectionThresholdPanel(state.detectionThreshold, viewModel::setDetectionThreshold, viewModel::resetDetectionThreshold)
        ModelSlot(state, viewModel, ModelType.OCR, "OCR", "Reads the text inside each bubble. Pick the model for the language your comic is written in.")
    }
}

@Composable
private fun DetectionThresholdPanel(stored: Float, onChange: (Float) -> Unit, onReset: () -> Unit) {
    // Local while dragging; saved once on release, like the generation sliders.
    var value by remember(stored) { mutableFloatStateOf(stored) }
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingRow(
                "Detection threshold",
                "%.2f (default %.2f). Lower finds fainter bubbles, higher skips doubtful ones. Applied on the next capture."
                    .format(value, DetectionThresholdStore.DEFAULT)
            ) {
                Slider(
                    value = value,
                    onValueChange = { value = DetectionThresholdStore.snap(it) },
                    onValueChangeFinished = { onChange(value) },
                    valueRange = DetectionThresholdStore.MIN..DetectionThresholdStore.MAX,
                    steps = DetectionThresholdStore.STEPS - 1,
                    colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(12.dp))
            PaperButton("Reset to default", onReset, enabled = stored != DetectionThresholdStore.DEFAULT)
        }
    }
}

@Composable
private fun ModelSlot(state: SettingsUiState, viewModel: SettingsViewModel, type: ModelType, title: String, description: String) {
    SlotPicker(
        title = title,
        description = description,
        deliverables = state.deliverables[type].orEmpty(),
        selectedId = state.selectedIds[type],
        pendingId = state.pendingIds[type],
        statuses = state.models.associate { it.id to it.status },
        downloads = state.downloads,
        fits = state::fits,
        needsSlowWarning = state::needsSlowWarning,
        languageBlock = state::languageBlock,
        onPick = { viewModel.pickModel(type, it) },
        onCancel = viewModel::cancelDownload,
        onDelete = viewModel::deleteModel
    )
}

@Composable
fun TypesettingSettings(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    SettingsPage(SettingsSection.Typesetting.title, SettingsSection.Typesetting.description, onBack) {
        PaperSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                SettingRow("Font size", "${(state.fontSizeScale * 100).toInt()}% of the fitted size") {
                    Slider(
                        value = state.fontSizeScale,
                        onValueChange = viewModel::setFontSizeScale,
                        colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun AppearanceSettings(viewModel: SettingsViewModel, triggerMode: TriggerModeViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val mode by triggerMode.mode.collectAsState()
    var themeConfirmation by remember { mutableStateOf<String?>(null) }
    SettingsPage(SettingsSection.Appearance.title, SettingsSection.Appearance.description, onBack) {
        PaperSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                SettingRow("Theme", "Paper colours for the app and the overlay.") {
                    Column {
                        listOf("system" to "Follow system", "day" to "Day paper", "night" to "Night paper").forEach { (mode, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = state.theme == mode, onClick = {
                                    viewModel.setTheme(mode)
                                    themeConfirmation = "$label selected"
                                })
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        themeConfirmation?.let { PaperSuccess(it) { themeConfirmation = null } }
        TriggerModePanel(mode, triggerMode::choose)
    }
}

@Composable
private fun TriggerModePanel(selected: TriggerMode, onChange: (TriggerMode) -> Unit) {
    // The shade trigger lives in the notification, which Android 13+ hides until notifications are allowed.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val choose = { mode: TriggerMode ->
        onChange(mode)
        if (mode == TriggerMode.NOTIFICATION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val options = listOf(
        TriggerMode.FLOATING_BUTTON to ("Floating button" to "Tap the button over the page; long-press it for quick settings."),
        TriggerMode.NOTIFICATION to ("Notification shade" to "Pull down the shade and tap Yomu's notification. Nothing stays over the page.")
    )
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingRow("Translate from", "Where you start translating a page while reading.") {
                Column {
                    options.forEach { (mode, text) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode == selected, onClick = { choose(mode) })
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text(text.first, style = MaterialTheme.typography.bodyMedium)
                                Text(text.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.yomu.app.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.yomu.app.AppLanguage
import com.yomu.app.R
import com.yomu.app.db.entities.ModelType
import com.yomu.app.detection.DetectionThresholdStore
import com.yomu.app.service.TriggerMode
import com.yomu.app.ui.UiText
import com.yomu.app.ui.asString
import com.yomu.app.ui.labelRes
import com.yomu.app.ui.theme.*
import com.yomu.core.TargetLanguage

@Composable
fun TranslationSettings(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    SettingsPage(stringResource(SettingsSection.Translation.title), stringResource(SettingsSection.Translation.description), onBack) {
        SourceLanguagePanel()
        TargetLanguagePanel(state.targetLanguage, state::languageUnavailable, viewModel::setTargetLanguage)
        state.languageOffer?.let {
            LanguageOfferDialog(it, state.targetLanguage, viewModel::acceptLanguageOffer, viewModel::declineLanguageOffer)
        }
        ModelSlot(state, viewModel, ModelType.LLM, R.string.settings_model_title, R.string.settings_model_description)
        state.recoveryWarning?.let { RecoveryWarning(it) }
        AdvancedGenerationPanel(
            generation = state.generation,
            defaults = state.generationDefaults,
            overridden = state.generationOverridden,
            onChange = viewModel::setGeneration,
            onReset = viewModel::resetGeneration
        )
        PageRecallPanel(state.pageRecall, viewModel::setPageRecall)
        SaveHistoryPanel()
    }
}

@Composable
private fun TargetLanguagePanel(
    selected: TargetLanguage,
    unavailable: (TargetLanguage) -> UiText?,
    onChange: (TargetLanguage) -> Unit
) {
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingRow(stringResource(R.string.settings_target_language_title), stringResource(R.string.settings_target_language_description)) {
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
                                    stringResource(language.labelRes()),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (reason == null) LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                reason?.let {
                                    Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One on/off setting on its own surface; the whole row toggles it. */
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

@Composable
private fun PageRecallPanel(enabled: Boolean, onChange: (Boolean) -> Unit) =
    SwitchPanel(stringResource(R.string.settings_page_recall_title), stringResource(R.string.settings_page_recall_description), enabled, onChange)

@Composable
private fun SaveHistoryPanel(viewModel: SaveHistoryViewModel = hiltViewModel()) {
    val enabled by viewModel.enabled.collectAsState()
    SwitchPanel(stringResource(R.string.settings_save_history_title), stringResource(R.string.settings_save_history_description), enabled, viewModel::set)
}

@Composable
fun PipelineSettings(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    SettingsPage(stringResource(SettingsSection.Pipeline.title), stringResource(SettingsSection.Pipeline.description), onBack) {
        ModelSlot(state, viewModel, ModelType.DETECTION, R.string.settings_detection_title, R.string.settings_detection_description)
        DetectionThresholdPanel(state.detectionThreshold, viewModel::setDetectionThreshold, viewModel::resetDetectionThreshold)
        ModelSlot(state, viewModel, ModelType.OCR, R.string.settings_ocr_title, R.string.settings_ocr_description)
    }
}

@Composable
private fun DetectionThresholdPanel(stored: Float, onChange: (Float) -> Unit, onReset: () -> Unit) {
    // Local while dragging; saved once on release, like the generation sliders.
    var value by remember(stored) { mutableFloatStateOf(stored) }
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingRow(
                stringResource(R.string.settings_detection_threshold_title),
                stringResource(R.string.settings_detection_threshold_description, value, DetectionThresholdStore.DEFAULT)
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
            PaperButton(stringResource(R.string.settings_reset_to_default), onReset, enabled = stored != DetectionThresholdStore.DEFAULT)
        }
    }
}

@Composable
private fun ModelSlot(state: SettingsUiState, viewModel: SettingsViewModel, type: ModelType, @StringRes title: Int, @StringRes description: Int) {
    SlotPicker(
        title = stringResource(title),
        description = stringResource(description),
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
    SettingsPage(stringResource(SettingsSection.Typesetting.title), stringResource(SettingsSection.Typesetting.description), onBack) {
        PaperSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                SettingRow(stringResource(R.string.settings_font_size_title), stringResource(R.string.settings_font_size_description, (state.fontSizeScale * 100).toInt())) {
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
    var themeConfirmation by remember { mutableStateOf<Int?>(null) }
    SettingsPage(stringResource(SettingsSection.Appearance.title), stringResource(SettingsSection.Appearance.description), onBack) {
        PaperSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                SettingRow(stringResource(R.string.settings_theme_title), stringResource(R.string.settings_theme_description)) {
                    Column {
                        listOf("system" to R.string.settings_theme_system, "day" to R.string.settings_theme_day, "night" to R.string.settings_theme_night).forEach { (mode, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = state.theme == mode, onClick = {
                                    viewModel.setTheme(mode)
                                    themeConfirmation = label
                                })
                                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
        themeConfirmation?.let { PaperSuccess(stringResource(R.string.settings_theme_selected, stringResource(it))) { themeConfirmation = null } }
        AppLanguagePanel()
        TriggerModePanel(mode, triggerMode::choose)
    }
}

/** Picks the language of the app's own text; the activity is recreated so every screen is rewritten at once. */
@Composable
private fun AppLanguagePanel() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(AppLanguage.PREFS_NAME, Context.MODE_PRIVATE) }
    var selected by remember { mutableStateOf(AppLanguage.load(prefs)) }
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingRow(stringResource(R.string.settings_language_title), stringResource(R.string.settings_language_description)) {
                Column(Modifier.selectableGroup()) {
                    AppLanguage.entries.forEach { language ->
                        Row(
                            Modifier.selectable(selected = language == selected, role = Role.RadioButton, onClick = {
                                if (language == selected) return@selectable
                                AppLanguage.save(prefs, language)
                                selected = language
                                (context as? Activity)?.recreate()
                            }),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = language == selected, onClick = null)
                            Text(language.nativeName, Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
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
        TriggerMode.FLOATING_BUTTON to (R.string.settings_trigger_floating_button to R.string.settings_trigger_floating_button_description),
        TriggerMode.NOTIFICATION to (R.string.settings_trigger_notification to R.string.settings_trigger_notification_description)
    )
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingRow(stringResource(R.string.settings_trigger_title), stringResource(R.string.settings_trigger_description)) {
                Column {
                    options.forEach { (mode, text) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode == selected, onClick = { choose(mode) })
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text(stringResource(text.first), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(text.second), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

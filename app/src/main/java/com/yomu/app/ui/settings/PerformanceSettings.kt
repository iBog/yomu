package com.yomu.app.ui.settings

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yomu.app.R
import com.yomu.app.translation.ResourceLimit
import com.yomu.app.ui.asString
import com.yomu.app.ui.theme.PaperButton
import com.yomu.app.ui.theme.PaperSurface
import com.yomu.core.RuntimeLimits
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val REFRESH_MS = 1_000L

@Composable
fun PerformanceSettings(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    // Sampled only while this screen is showing.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshResources()
            delay(REFRESH_MS)
        }
    }
    SettingsPage(stringResource(SettingsSection.Performance.title), stringResource(SettingsSection.Performance.description), onBack) {
        ResourceReadoutPanel(state.resources)
        ResourceLimitsPanel(state.resourceLimits, state.limitRefusal, viewModel::setResourceLimit, viewModel::resetResourceLimits)
    }
}

@Composable
private fun ResourceReadoutPanel(readout: ResourceReadout?) {
    val context = LocalContext.current
    fun bytes(value: Long) = Formatter.formatShortFileSize(context, value)
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_perf_right_now), style = MaterialTheme.typography.titleSmall)
            // No early return here: leaving a composable lambda mid-group unbalances the Compose group stack on recompose.
            if (readout == null) {
                Text(stringResource(R.string.settings_perf_measuring), style = MaterialTheme.typography.bodySmall)
            } else {
                val noPage = stringResource(R.string.settings_perf_no_page)
                ReadoutRow(stringResource(R.string.settings_perf_app_memory), stringResource(R.string.settings_perf_app_memory_value, bytes(readout.appPssBytes)))
                ReadoutRow(stringResource(R.string.settings_perf_native_heap), bytes(readout.nativeHeapBytes))
                ReadoutRow(
                    stringResource(R.string.settings_perf_phone_memory),
                    stringResource(R.string.settings_perf_phone_memory_value, bytes(readout.deviceAvailableBytes), bytes(readout.deviceTotalBytes))
                )
                ReadoutRow(
                    stringResource(R.string.settings_perf_cpu),
                    readout.cpuPercent?.let { stringResource(R.string.settings_perf_cpu_value, it) } ?: stringResource(R.string.settings_perf_measuring)
                )
                ReadoutRow(
                    stringResource(R.string.settings_perf_page_time),
                    readout.lastPageTotalMs?.let { stringResource(R.string.settings_perf_ms, it) } ?: noPage
                )
                ReadoutRow(
                    stringResource(R.string.settings_perf_model_time),
                    readout.lastPageMs?.let { stringResource(R.string.settings_perf_ms, it) } ?: noPage
                )
            }
        }
    }
}

@Composable
private fun ReadoutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResourceLimitsPanel(
    stored: Map<ResourceLimit, Int>,
    refusal: LimitRefusal?,
    onChange: (ResourceLimit, Int) -> Unit,
    onReset: () -> Unit
) {
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            refusal?.let { Text(it.message.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            LimitSlider(ResourceLimit.THREADS, stored, refusal, R.string.settings_limit_threads_title, onChange) {
                stringResource(R.string.settings_limit_threads_description, it, RuntimeLimits.MAX_THREADS, ResourceLimit.THREADS.default)
            }
            LimitSlider(ResourceLimit.CONTEXT_TOKENS, stored, refusal, R.string.settings_limit_context_title, onChange) {
                stringResource(R.string.settings_limit_context_description, it, ResourceLimit.CONTEXT_TOKENS.default)
            }
            LimitSlider(ResourceLimit.FIT_BUDGET_PERCENT, stored, refusal, R.string.settings_limit_fit_title, onChange) {
                stringResource(R.string.settings_limit_fit_description, it, ResourceLimit.FIT_BUDGET_PERCENT.default)
            }
            PaperButton(stringResource(R.string.settings_reset_to_defaults), onReset, enabled = ResourceLimit.entries.any { stored[it] != it.default })
        }
    }
}

@Composable
private fun LimitSlider(
    limit: ResourceLimit,
    stored: Map<ResourceLimit, Int>,
    refusal: LimitRefusal?,
    @StringRes label: Int,
    onChange: (ResourceLimit, Int) -> Unit,
    describe: @Composable (Int) -> String
) {
    val options = limit.options
    val storedIndex = options.indexOf(stored.getValue(limit)).coerceAtLeast(0)
    // Local while dragging; saved once on release, like the other settings sliders.
    var index by remember(storedIndex, refusal) { mutableIntStateOf(storedIndex) }
    SettingRow(stringResource(label), describe(options[index])) {
        if (options.size > 1) {
            Slider(
                value = index.toFloat(),
                onValueChange = { index = it.roundToInt() },
                onValueChangeFinished = { onChange(limit, options[index]) },
                valueRange = 0f..(options.size - 1).toFloat(),
                steps = options.size - 2,
                colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

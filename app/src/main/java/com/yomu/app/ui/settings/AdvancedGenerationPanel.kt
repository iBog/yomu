package com.yomu.app.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yomu.app.R
import com.yomu.app.ui.UiText
import com.yomu.app.ui.theme.PaperButton
import com.yomu.app.ui.theme.PaperSurface
import com.yomu.core.GenerationBound
import com.yomu.core.GenerationParams
import kotlin.math.roundToInt

/** The bound's on-screen name; the exhaustive `when` makes a new entry a compile error until it has one (#196). */
@get:StringRes
internal val GenerationBound.labelRes: Int
    get() = when (this) {
        GenerationBound.TEMPERATURE -> R.string.settings_bound_temperature
        GenerationBound.TOP_K -> R.string.settings_bound_top_k
        GenerationBound.TOP_P -> R.string.settings_bound_top_p
    }

private fun GenerationBound.format(value: Float): String {
    val decimals = step.toString().substringAfter('.', "").trimEnd('0').length
    return "%.${decimals}f".format(value)
}

/** Snap a slider position to the declared step and keep it inside the range float error may leave. */
private fun GenerationBound.snap(value: Float): Float =
    (min + ((value - min) / step).roundToInt() * step).coerceIn(min, max)

@Composable
internal fun RecoveryWarning(recovered: List<UiText>) {
    val context = LocalContext.current
    Surface(
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.error), modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Warning, contentDescription = stringResource(R.string.settings_warning), tint = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.settings_recovery_warning, recovered.joinToString { it.resolve(context) }), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun AdvancedGenerationPanel(
    generation: GenerationParams,
    defaults: GenerationParams,
    overridden: Boolean,
    onChange: (GenerationBound, Float) -> Unit,
    onReset: () -> Unit
) {
    PaperSurface(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_generation_title), fontWeight = FontWeight.Medium)
            Text(
                text = stringResource(if (overridden) R.string.settings_generation_changed else R.string.settings_generation_defaults),
                fontSize = 12.sp,
                color = if (overridden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.settings_generation_description),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            GenerationBound.entries.forEach { bound ->
                Spacer(Modifier.height(12.dp))
                GenerationSlider(bound, bound.read(generation), bound.read(defaults), onChange)
            }
            Spacer(Modifier.height(12.dp))
            PaperButton(stringResource(R.string.settings_reset_to_defaults), onReset, enabled = overridden)
        }
    }
}

@Composable
private fun GenerationSlider(
    bound: GenerationBound,
    stored: Float,
    default: Float,
    onChange: (GenerationBound, Float) -> Unit
) {
    // Local while dragging; saved once on release so a drag does not queue a profile swap per frame.
    var value by remember(stored) { mutableFloatStateOf(stored) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(bound.labelRes), fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.settings_generation_value, bound.format(value), bound.format(default)), fontSize = 14.sp)
    }
    Slider(
        value = value,
        onValueChange = { value = bound.snap(it) },
        onValueChangeFinished = { onChange(bound, value) },
        valueRange = bound.min..bound.max,
        steps = ((bound.max - bound.min) / bound.step).roundToInt() - 1,
        colors = SliderDefaults.colors(inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.fillMaxWidth()
    )
}

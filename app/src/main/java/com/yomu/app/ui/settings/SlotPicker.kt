package com.yomu.app.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yomu.app.R
import com.yomu.app.db.entities.ModelStatus
import com.yomu.app.service.LanguageSwitch
import com.yomu.app.service.SlotDeliverable
import com.yomu.app.ui.UiText
import com.yomu.app.ui.labelRes
import com.yomu.app.ui.theme.*
import com.yomu.core.TargetLanguage
import com.yomu.core.toFileSizeString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SlotPicker(
    title: String,
    description: String,
    deliverables: List<SlotDeliverable>,
    selectedId: String?,
    pendingId: String?,
    statuses: Map<String, ModelStatus>,
    downloads: Map<String, Int>,
    fits: (SlotDeliverable) -> Boolean,
    needsSlowWarning: (SlotDeliverable) -> Boolean,
    languageBlock: (SlotDeliverable) -> UiText?,
    onPick: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmSlow by rememberSaveable { mutableStateOf<String?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    PaperSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            SettingRow(title, description) {
                Surface(onClick = { open = true }, color = Color.Transparent, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(deliverables.firstOrNull { it.id == selectedId }?.name ?: stringResource(R.string.settings_no_model_selected), style = MaterialTheme.typography.titleSmall)
                            deliverables.firstOrNull { it.id == pendingId }?.let {
                                Text(stringResource(R.string.settings_switches_when_downloaded, it.name), style = MaterialTheme.typography.bodySmall, color = muted)
                            }
                        }
                        Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            deliverables.forEach { deliverable ->
                DeliverableRow(
                    deliverable = deliverable,
                    selected = deliverable.id == selectedId,
                    pending = deliverable.id == pendingId,
                    status = statuses[deliverable.id],
                    progress = downloads[deliverable.id],
                    fits = fits(deliverable),
                    languageBlock = languageBlock(deliverable),
                    onPick = { if (needsSlowWarning(deliverable)) confirmSlow = deliverable.id else onPick(deliverable.id) },
                    onCancel = { onCancel(deliverable.id) },
                    onDelete = { if (deliverable.id == selectedId) confirmDelete = deliverable.id else onDelete(deliverable.id) }
                )
            }
        }
    }
    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.settings_delete_in_use_title)) },
            text = { Text(stringResource(R.string.settings_delete_in_use_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    onDelete(id)
                }) { Text(stringResource(R.string.settings_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.settings_keep)) } }
        )
    }
    confirmSlow?.let { id ->
        SlowDeliverableDialog(
            onConfirm = {
                confirmSlow = null
                onPick(id)
            },
            onDismiss = { confirmSlow = null }
        )
    }
}

/** Every surface that picks a model asks the same thing, so a Slow pick reads the same wherever it starts. */
@Composable
internal fun SlowDeliverableDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_slow_title)) },
        text = { Text(stringResource(R.string.settings_slow_text)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_slow_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } }
    )
}

/** Every surface that chooses a language asks the same thing when the model in use writes English only (#338). */
@Composable
internal fun LanguageOfferDialog(offer: LanguageSwitch.Offer, keep: TargetLanguage, onAccept: () -> Unit, onDecline: () -> Unit) {
    val language = stringResource(offer.language.labelRes())
    val text = stringResource(R.string.settings_offer_text, offer.current.name, offer.model.name, language)
    val download = if (offer.downloadBytes > 0) " " + stringResource(R.string.settings_offer_download, offer.downloadBytes.toFileSizeString()) else ""
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.settings_offer_title, offer.model.name)) },
        text = { Text(text + download) },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.settings_offer_switch)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.settings_offer_keep, stringResource(keep.labelRes()))) } }
    )
}

internal fun deliverableStatus(context: Context, status: ModelStatus?, progress: Int?, selected: Boolean, pending: Boolean): String = when {
    progress != null -> context.getString(R.string.settings_status_downloading, progress)
    status == ModelStatus.READY -> context.getString(if (selected) R.string.settings_status_in_use else R.string.settings_status_downloaded)
    status == ModelStatus.ERROR -> context.getString(R.string.settings_status_error)
    else -> context.getString(R.string.settings_status_not_downloaded)
} + if (pending) " · " + context.getString(R.string.settings_status_takes_over) else ""

internal fun deliverableDetail(context: Context, deliverable: SlotDeliverable, fits: Boolean, languageBlock: UiText? = null): String = listOfNotNull(
    deliverable.sizeBytes.toFileSizeString(),
    context.getString(deliverable.speed.labelRes),
    deliverable.languageLabelRes?.let(context::getString),
    deliverable.licence,
    context.getString(R.string.settings_experimental).takeIf { deliverable.experimental },
    context.getString(if (fits) R.string.settings_fits_device else R.string.settings_needs_memory),
    languageBlock?.resolve(context)
).joinToString(" · ")

@Composable
private fun DeliverableRow(
    deliverable: SlotDeliverable,
    selected: Boolean,
    pending: Boolean,
    status: ModelStatus?,
    progress: Int?,
    fits: Boolean,
    languageBlock: UiText?,
    onPick: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val pickable = fits && languageBlock == null
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val context = LocalContext.current
    val statusLabel = deliverableStatus(context, status, progress, selected, pending)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onPick, enabled = pickable)
            Column(Modifier.weight(1f)) {
                Text(deliverable.name, style = MaterialTheme.typography.titleSmall, color = if (pickable) LocalContentColor.current else muted)
                Text(deliverableDetail(context, deliverable, fits, languageBlock), style = MaterialTheme.typography.bodySmall, color = muted)
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == ModelStatus.ERROR && progress == null) MaterialTheme.colorScheme.error else muted
                )
            }
            when {
                progress != null -> PaperButton(stringResource(R.string.settings_cancel), onCancel)
                status == ModelStatus.READY -> PaperButton(stringResource(R.string.settings_delete), onDelete)
                else -> PaperButton(stringResource(if (status == ModelStatus.ERROR) R.string.settings_retry else R.string.settings_download), onPick, enabled = pickable)
            }
        }
        progress?.let {
            LinearProgressIndicator(progress = it / 100f, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

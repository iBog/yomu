package com.yomu.app.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yomu.app.R
import com.yomu.app.db.entities.ModelType
import com.yomu.app.ui.UiText
import com.yomu.app.ui.asString
import com.yomu.app.ui.settings.SlotPicker
import com.yomu.app.ui.theme.*
import com.yomu.core.toFileSizeString

@Composable
fun SetupScreen(state: HomeUiState, viewModel: HomeViewModel) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.setup_tagline), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.setup_intro))
        PaperSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.setup_apps_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.setup_apps_body))
                Text(stringResource(R.string.setup_models_body))
            }
        }
        if (!state.setupStarted || state.setupError) {
            SetupSlot(state, viewModel, ModelType.DETECTION, R.string.setup_detection_title, R.string.setup_detection_description)
            SetupSlot(state, viewModel, ModelType.OCR, R.string.setup_ocr_title, R.string.setup_ocr_description)
            SetupSlot(state, viewModel, ModelType.LLM, R.string.setup_translation_title, R.string.setup_translation_description)
        }
        when {
            state.setupBlockedBy != null -> PaperError(
                stringResource(R.string.setup_blocked, state.setupBlockedBy),
                viewModel::prepareSetup
            )
            state.setupError -> PaperError(
                stringResource(R.string.setup_error),
                viewModel::prepareSetup
            )
            !state.setupStarted -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (state.setupDownloadBytes > 0) stringResource(R.string.setup_download_size, state.setupDownloadBytes.toFileSizeString())
                    else stringResource(R.string.setup_all_downloaded),
                    style = MaterialTheme.typography.titleMedium
                )
                if (state.onMeteredNetwork && state.setupDownloadBytes > 0) {
                    Text(stringResource(R.string.setup_metered, state.setupDownloadBytes.toFileSizeString()))
                }
                PaperButton(stringResource(R.string.setup_download), viewModel::prepareSetup, Modifier.fillMaxWidth())
            }
            !state.setupDownloadsReady -> PaperSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text((state.downloading ?: UiText(R.string.setup_preparing)).asString(), style = MaterialTheme.typography.titleMedium)
                    if (state.downloadProgress == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        LinearProgressIndicator(progress = state.downloadProgress / 100f, modifier = Modifier.fillMaxWidth())
                        Text("${state.downloadProgress}%")
                    }
                    Text(stringResource(R.string.setup_keep_open), style = MaterialTheme.typography.bodySmall)
                }
            }
            state.readiness != Readiness.Ready -> PaperSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.setup_permission_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.setup_permission_body))
                    PaperButton(stringResource(R.string.setup_open_permission), {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    })
                }
            }
            else -> PaperSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.setup_ready_description))
                    Text(stringResource(R.string.setup_ready_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.setup_ready_body))
                    PaperButton(stringResource(R.string.setup_go_home), viewModel::completeSetup)
                }
            }
        }
        TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), onClick = viewModel::dismissSetup) { Text(stringResource(R.string.setup_back_home)) }
    }
}

@Composable
private fun SetupSlot(state: HomeUiState, viewModel: HomeViewModel, type: ModelType, @StringRes title: Int, @StringRes description: Int) {
    SlotPicker(
        title = stringResource(title),
        description = stringResource(description),
        deliverables = state.deliverables[type].orEmpty(),
        selectedId = state.chosenIds[type],
        pendingId = null,
        statuses = state.models.associate { it.id to it.status },
        downloads = emptyMap(),
        fits = state::fits,
        needsSlowWarning = state::needsSlowWarning,
        languageBlock = state::languageBlock,
        onPick = { viewModel.pickSetupModel(type, it) },
        onCancel = {},
        onDelete = viewModel::deleteModel
    )
}

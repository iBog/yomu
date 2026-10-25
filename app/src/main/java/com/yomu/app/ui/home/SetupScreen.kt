package com.yomu.app.ui.home

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yomu.app.db.entities.ModelType
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
        Text("Set up reading", style = MaterialTheme.typography.headlineLarge)
        Text("Your manga, in English", style = MaterialTheme.typography.headlineSmall)
        Text("Yomu finds the speech bubbles, reads the Japanese and translates it on your device. Your pages stay with you.")
        PaperSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Read in the apps you love", style = MaterialTheme.typography.titleLarge)
                Text("Turn reading on and leave it on while you browse. Tap Yomu’s floating button whenever you want to translate a page.")
                Text("First, download the models that read and translate the page. The defaults suit most devices; tap a model to pick another.")
            }
        }
        if (!state.setupStarted || state.setupError) {
            SetupSlot(state, viewModel, ModelType.DETECTION, "Detection", "Finds the speech bubbles on the page.")
            SetupSlot(state, viewModel, ModelType.OCR, "OCR", "Reads the text inside each bubble. Pick the model for the language your comic is written in.")
            SetupSlot(state, viewModel, ModelType.LLM, "Translation", "The on-device model that translates each bubble.")
        }
        when {
            state.setupBlockedBy != null -> PaperError(
                "${state.setupBlockedBy} needs more memory than this phone's fit budget allows. Pick a smaller model, then try again.",
                viewModel::prepareSetup
            )
            state.setupError -> PaperError(
                "Setup couldn't finish. Check your connection and available storage, then try again. Completed downloads are kept.",
                viewModel::prepareSetup
            )
            !state.setupStarted -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (state.setupDownloadBytes > 0) "Download size: ${state.setupDownloadBytes.toFileSizeString()}"
                    else "Every chosen model is already downloaded.",
                    style = MaterialTheme.typography.titleMedium
                )
                if (state.onMeteredNetwork && state.setupDownloadBytes > 0) {
                    Text("You're on mobile data. This ${state.setupDownloadBytes.toFileSizeString()} download will count toward your data plan.")
                }
                PaperButton("Download", viewModel::prepareSetup, Modifier.fillMaxWidth())
            }
            !state.setupDownloadsReady -> PaperSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.downloading ?: "Preparing reading…", style = MaterialTheme.typography.titleMedium)
                    if (state.downloadProgress == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else {
                        LinearProgressIndicator(progress = state.downloadProgress / 100f, modifier = Modifier.fillMaxWidth())
                        Text("${state.downloadProgress}%")
                    }
                    Text("Keep Yomu open while the download finishes.", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.readiness != Readiness.Ready -> PaperSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Allow reading over other apps", style = MaterialTheme.typography.titleLarge)
                    Text("Yomu needs permission to show its floating button and English text over your manga. Enable ‘Allow display over other apps’, then come back here.")
                    PaperButton("Open permission settings", {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    })
                }
            }
            else -> PaperSurface(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Check, contentDescription = "Ready")
                    Text("You're ready to read", style = MaterialTheme.typography.titleLarge)
                    Text("When you turn reading on, Android will ask to share your screen with Yomu for that reading session.")
                    PaperButton("Go to Home", viewModel::completeSetup)
                }
            }
        }
        TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), onClick = viewModel::dismissSetup) { Text("Back to Home") }
    }
}

@Composable
private fun SetupSlot(state: HomeUiState, viewModel: HomeViewModel, type: ModelType, title: String, description: String) {
    SlotPicker(
        title = title,
        description = description,
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

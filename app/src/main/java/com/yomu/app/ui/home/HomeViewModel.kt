package com.yomu.app.ui.home

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomu.app.db.HistoryDao
import com.yomu.app.db.entities.ModelStatus
import com.yomu.app.db.entities.ModelType
import com.yomu.app.service.LanguageSwitch
import com.yomu.app.service.ModelManager
import com.yomu.app.service.ModelSlotSelection
import com.yomu.app.service.OverlayService
import com.yomu.core.TargetLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sharedPreferences: SharedPreferences,
    private val modelManager: ModelManager,
    private val historyDao: HistoryDao,
    private val slotSelection: ModelSlotSelection
) : ViewModel() {
    private companion object {
        const val SETUP_COMPLETE = "reading_setup_complete"
        const val HAS_READ = "has_read"
        val DOWNLOAD_LABELS = mapOf(
            ModelType.DETECTION to "Finding speech bubbles",
            ModelType.OCR to "Reading text",
            ModelType.LLM to "Translating into English"
        )
    }

    private val _uiState = MutableStateFlow(HomeUiState(
        setupComplete = sharedPreferences.getBoolean(SETUP_COMPLETE, false),
        hasRead = sharedPreferences.getBoolean(HAS_READ, false),
        deliverables = ModelType.entries.associateWith(slotSelection::deliverables),
        fitBudget = slotSelection.fitBudget(modelManager.deviceTotalMemBytes())
    ).withSlots())
    val uiState = _uiState.asStateFlow()
    private var historyJob: Job? = null
    private var countJob: Job? = null
    private var setupJob: Job? = null

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                OverlayService.ACTION_SERVICE_STARTED -> _uiState.update {
                    it.copy(isServiceRunning = true, serviceError = false, confirmation = "Reading is on")
                }
                OverlayService.ACTION_SERVICE_STOPPED -> _uiState.update { it.copy(isServiceRunning = false) }
                OverlayService.ACTION_SERVICE_START_FAILED -> _uiState.update {
                    it.copy(isServiceRunning = false, serviceError = true)
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(context, serviceStateReceiver, IntentFilter().apply {
            addAction(OverlayService.ACTION_SERVICE_STARTED)
            addAction(OverlayService.ACTION_SERVICE_STOPPED)
            addAction(OverlayService.ACTION_SERVICE_START_FAILED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        viewModelScope.launch {
            try {
                modelManager.refreshModelList()
                modelManager.getAllModels().collect { models ->
                    slotSelection.commitPending(models.associate { it.id to it.status })
                    _uiState.update { it.copy(models = models).withSlots() }
                    refreshReadiness()
                    _uiState.update { state ->
                        state.copy(
                            setupVisible = state.setupVisible ||
                                (state.modelsLoading && (!state.setupComplete || state.readiness != Readiness.Ready)),
                            modelsLoading = false
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(modelsLoading = false, setupVisible = true, setupError = true) }
            }
        }
        loadRecentHistory()
        refreshEnvironment()
    }

    private fun refreshReadiness() {
        val permission = Settings.canDrawOverlays(context)
        _uiState.update { state ->
            val readiness = resolveReadiness(
                state.models.associate { it.id to it.status },
                slotSelection.selectedId(ModelType.DETECTION),
                slotSelection.selectedId(ModelType.OCR),
                slotSelection.selectedId(ModelType.LLM),
                permission
            )
            state.copy(
                readiness = readiness,
                setupVisible = state.setupVisible ||
                    (state.readiness == Readiness.Ready && readiness != Readiness.Ready)
            )
        }
    }

    @Suppress("DEPRECATION")
    fun refreshEnvironment() {
        refreshReadiness()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val running = manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == OverlayService::class.java.name && it.foreground
        }
        _uiState.update {
            it.withSlots().copy(
                isServiceRunning = running,
                onMeteredNetwork = modelManager.isOnMeteredNetwork(),
                fitBudget = slotSelection.fitBudget(it.fitBudget.totalMemBytes)
            )
        }
        countJob?.cancel()
        countJob = viewModelScope.launch {
            val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            historyDao.getTranslationCountSince(start)
                .catch { _uiState.update { it.copy(historyError = true) } }
                .collect { count -> _uiState.update { it.copy(pagesTranslatedToday = count) } }
        }
    }

    fun openSetup() = _uiState.update { it.copy(setupVisible = true) }

    fun dismissSetup() = _uiState.update { it.copy(setupVisible = false) }

    /** Setup only records the choice; its Download button fetches every slot's choice at once. */
    fun pickSetupModel(type: ModelType, id: String) {
        viewModelScope.launch {
            slotSelection.pick(type, id, modelManager.getModel(id)?.status, _uiState.value.fitBudget.totalMemBytes)
            _uiState.update { it.withSlots() }
        }
    }

    /** Home's quick pick: unlike setup, a model that isn't downloaded starts downloading and takes over when READY. */
    fun pickTranslationModel(id: String) {
        viewModelScope.launch {
            val status = modelManager.getModel(id)?.status
            if (!slotSelection.pick(ModelType.LLM, id, status, _uiState.value.fitBudget.totalMemBytes)) return@launch
            downloadIfPending(id)
        }
    }

    /** From the quick-settings chip here, or from the overlay's when its switch needs the model offer (#338). */
    fun chooseLanguage(language: TargetLanguage) {
        viewModelScope.launch {
            val switch = slotSelection.switchLanguage(language)
            _uiState.update { it.copy(languageOffer = switch as? LanguageSwitch.Offer).withSlots() }
        }
    }

    fun acceptLanguageOffer() {
        val offer = _uiState.value.languageOffer ?: return
        _uiState.update { it.copy(languageOffer = null) }
        viewModelScope.launch {
            if (slotSelection.accept(offer, _uiState.value.fitBudget.totalMemBytes)) downloadIfPending(offer.model.id)
        }
    }

    fun declineLanguageOffer() = _uiState.update { it.copy(languageOffer = null) }

    private suspend fun downloadIfPending(id: String) {
        _uiState.update { it.withSlots() }
        if (slotSelection.pendingId(ModelType.LLM) != id) return
        slotSelection.download(id)
        _uiState.update { it.withSlots() }
    }

    fun deleteModel(id: String) {
        viewModelScope.launch {
            slotSelection.delete(id)
            _uiState.update { it.withSlots() }
        }
    }

    private fun HomeUiState.withSlots(): HomeUiState = copy(
        chosenIds = ModelType.entries.associateWith(slotSelection::chosenId),
        translationSelectedId = slotSelection.selectedId(ModelType.LLM),
        translationPendingId = slotSelection.pendingId(ModelType.LLM),
        // With the slots, since a download that never completes can return the language to English.
        targetLanguage = slotSelection.targetLanguage(),
        setupDownloadBytes = slotSelection.downloadBytes(models.associate { it.id to it.status })
    )

    fun prepareSetup() {
        if (setupJob?.isActive == true) return
        setupJob = viewModelScope.launch {
            _uiState.update {
                it.copy(setupStarted = true, setupError = false, setupBlockedBy = null, setupDownloadsReady = false, onMeteredNetwork = modelManager.isOnMeteredNetwork())
            }
            try {
                modelManager.refreshModelList()
                for (type in ModelType.entries) {
                    val id = slotSelection.chosenId(type)
                    // Refused before downloading: pick would turn it down afterwards, leaving a download that never takes over.
                    if (!ModelSlotSelection.fits(id, slotSelection.fitBudget(_uiState.value.fitBudget.totalMemBytes))) {
                        val name = slotSelection.deliverables(type).firstOrNull { it.id == id }?.name ?: id
                        _uiState.update { it.copy(setupError = true, setupBlockedBy = name) }
                        return@launch
                    }
                    if (modelManager.getModel(id)?.status != ModelStatus.READY) {
                        _uiState.update { it.copy(downloading = DOWNLOAD_LABELS.getValue(type), downloadProgress = 0) }
                        val success = modelManager.downloadModel(id) { progress ->
                            _uiState.update { it.copy(downloadProgress = progress.percentage.coerceIn(0, 100)) }
                        }
                        if (!success) {
                            _uiState.update { it.copy(setupError = true) }
                            return@launch
                        }
                    }
                    slotSelection.pick(type, id, ModelStatus.READY, _uiState.value.fitBudget.totalMemBytes)
                }
                refreshReadiness()
                _uiState.update { it.copy(setupDownloadsReady = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(setupError = true) }
            } finally {
                _uiState.update { it.copy(downloading = null) }
            }
        }
    }

    fun completeSetup() {
        refreshReadiness()
        if (_uiState.value.readiness != Readiness.Ready || !_uiState.value.setupDownloadsReady) return
        sharedPreferences.edit().putBoolean(SETUP_COMPLETE, true).apply()
        _uiState.update { it.copy(setupComplete = true, setupVisible = false, setupStarted = false) }
    }

    fun loadRecentHistory() {
        historyJob?.cancel()
        _uiState.update { it.copy(historyLoading = true, historyError = false) }
        historyJob = viewModelScope.launch {
            historyDao.getAllTranslations()
                .catch { _uiState.update { it.copy(historyLoading = false, historyError = true) } }
                .collect { translations ->
                    if (translations.isNotEmpty() && !_uiState.value.hasRead) {
                        sharedPreferences.edit().putBoolean(HAS_READ, true).apply()
                    }
                    _uiState.update {
                        it.copy(translations = translations, hasRead = it.hasRead || translations.isNotEmpty(), historyLoading = false)
                    }
                }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            try {
                historyDao.clearHistory()
                _uiState.update { it.copy(confirmation = "Recent translations cleared", clearError = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(clearError = true) }
            }
        }
    }

    fun dismissConfirmation() = _uiState.update { it.copy(confirmation = null) }

    fun dismissServiceError() = _uiState.update { it.copy(serviceError = false) }

    fun stopService() {
        OverlayService.stop(context)
    }

    override fun onCleared() {
        context.unregisterReceiver(serviceStateReceiver)
        super.onCleared()
    }
}

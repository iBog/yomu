package com.yomu.app.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yomu.app.detection.DetectionThresholdStore
import com.yomu.app.db.entities.ModelEntity
import com.yomu.app.db.entities.ModelStatus
import com.yomu.app.db.entities.ModelType
import com.yomu.app.service.LanguageSwitch
import com.yomu.app.service.ModelManager
import com.yomu.app.service.ModelSlotSelection
import com.yomu.app.service.PageRecallStore
import com.yomu.app.service.SlotDeliverable
import com.yomu.app.translation.FitBudget
import com.yomu.app.translation.ResourceLimit
import com.yomu.app.translation.TranslationModelSelection
import com.yomu.core.Constants
import com.yomu.core.GenerationBound
import com.yomu.core.GenerationParams
import com.yomu.core.TargetLanguage
import com.yomu.pipeline.TranslationPipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val translationMode: String = "local",
    val targetLanguage: TargetLanguage = TargetLanguage.ENGLISH,
    /** The model switch a language choice is waiting on the reader to accept or decline (#338), or null. */
    val languageOffer: LanguageSwitch.Offer? = null,
    val sourceLanguage: String = "ja",
    val deliverables: Map<ModelType, List<SlotDeliverable>> = emptyMap(),
    val selectedIds: Map<ModelType, String> = emptyMap(),
    val pendingIds: Map<ModelType, String> = emptyMap(),
    // Total device RAM, for the per-model fit budget (part D). 0 until read.
    val deviceTotalMemBytes: Long = 0L,
    val fontSizeScale: Float = Constants.DEFAULT_FONT_SIZE_SCALE,
    val theme: String = "system",
    val models: List<ModelEntity> = emptyList(),
    /** Percentage done of every download in progress, by model id. */
    val downloads: Map<String, Int> = emptyMap(),
    /** The effective sampler profile: the selected model's maker defaults under the reader's fields (#192, ADR-0017). */
    val generation: GenerationParams = GenerationParams(),
    /** The selected model's maker sampling — what the sliders label as the default and what Reset returns to. */
    val generationDefaults: GenerationParams = GenerationParams(),
    /** The reader's bubble confidence threshold (#227). */
    val detectionThreshold: Float = DetectionThresholdStore.DEFAULT,
    /** Whether a page already translated this session is shown again instead of translated afresh (#351). */
    val pageRecall: Boolean = PageRecallStore.DEFAULT,
    /** The reader's caps on the translation model (#79). */
    val resourceLimits: Map<ResourceLimit, Int> = ResourceLimit.entries.associateWith { it.default },
    /** Why the last limit change was refused, or null. */
    val limitRefusal: LimitRefusal? = null,
    /** What the app is using right now; null until the Performance screen first samples it. */
    val resources: ResourceReadout? = null,
    /** One message naming every stored value that was recovered to its default, or null. */
    val recoveryWarning: String? = null
) {
    val generationOverridden: Boolean
        get() = GenerationBound.entries.any { it.read(generation) != it.read(generationDefaults) }

    fun fits(deliverable: SlotDeliverable): Boolean = ModelSlotSelection.fits(deliverable.id, fitBudget())

    fun needsSlowWarning(deliverable: SlotDeliverable): Boolean =
        ModelSlotSelection.needsSlowWarning(deliverable, fitBudget())

    fun languageBlock(deliverable: SlotDeliverable): String? = ModelSlotSelection.languageBlock(deliverable, targetLanguage)

    fun languageUnavailable(language: TargetLanguage): String? =
        ModelSlotSelection.languageUnavailable(language, deliverables[ModelType.LLM].orEmpty(), ::fits)

    private fun fitBudget() = FitBudget(
        deviceTotalMemBytes,
        resourceLimits.getValue(ResourceLimit.FIT_BUDGET_PERCENT),
        resourceLimits.getValue(ResourceLimit.CONTEXT_TOKENS)
    )
}

/** Identity equality on purpose: refusing the same change twice must still snap the slider back. */
class LimitRefusal(val message: String)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val sharedPreferences: SharedPreferences,
    private val modelSelection: TranslationModelSelection,
    private val slotSelection: ModelSlotSelection,
    private val modelManager: ModelManager,
    private val translationPipeline: TranslationPipeline
) : ViewModel() {

    private val detectionThresholdStore = DetectionThresholdStore(sharedPreferences)
    private val pageRecallStore = PageRecallStore(sharedPreferences)
    private val resourceMonitor = ResourceMonitor(context)
    // Held in a field: SharedPreferences keeps listeners weakly. The overlay's quick settings flip recall while this screen may be open (#401).
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PageRecallStore.KEY) _uiState.update { it.copy(pageRecall = pageRecallStore.load()) }
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        if (sharedPreferences.getString(Constants.PREF_THEME, "system") == "dark") {
            sharedPreferences.edit().putString(Constants.PREF_THEME, "night").apply()
        }
        _uiState.value = SettingsUiState(
            translationMode = sharedPreferences.getString(Constants.PREF_TRANSLATION_MODE, "local") ?: "local",
            sourceLanguage = sharedPreferences.getString(Constants.PREF_SOURCE_LANGUAGE, "ja") ?: "ja",
            deliverables = ModelType.entries.associateWith(slotSelection::deliverables),
            deviceTotalMemBytes = modelManager.deviceTotalMemBytes(),
            fontSizeScale = sharedPreferences.getFloat(Constants.PREF_FONT_SIZE_SCALE, Constants.DEFAULT_FONT_SIZE_SCALE),
            theme = sharedPreferences.getString(Constants.PREF_THEME, "system") ?: "system",
            detectionThreshold = detectionThresholdStore.load(),
            pageRecall = pageRecallStore.load(),
            resourceLimits = storedResourceLimits()
        ).withSlots()
        // The warning is now on screen; forget the bad values so it does not return on every visit.
        if (_uiState.value.recoveryWarning != null) modelSelection.clearRecovered()
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceListener)

        viewModelScope.launch {
            modelManager.refreshModelList()
        }

        viewModelScope.launch {
            modelManager.getAllModels().collect { models ->
                slotSelection.commitPending(models.associate { it.id to it.status })
                _uiState.update { it.copy(models = models).withSlots() }
            }
        }

        viewModelScope.launch {
            slotSelection.progress.collect { progress -> _uiState.update { it.copy(downloads = progress) } }
        }
    }

    override fun onCleared() = sharedPreferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)

    fun setTheme(theme: String) {
        require(theme in listOf("system", "day", "night"))
        sharedPreferences.edit().putString(Constants.PREF_THEME, theme).apply()
        _uiState.value = _uiState.value.copy(theme = theme)
    }

    fun setTranslationMode(mode: String) {
        sharedPreferences.edit().putString(Constants.PREF_TRANSLATION_MODE, mode).apply()
        _uiState.value = _uiState.value.copy(translationMode = mode)
    }

    fun pickModel(type: ModelType, id: String) {
        // Off the main thread: committing a translation model waits out any in-flight generation before swapping the
        // native model, which can take up to a batch timeout.
        viewModelScope.launch {
            val status = modelManager.getModel(id)?.status
            if (!slotSelection.pick(type, id, status, _uiState.value.deviceTotalMemBytes)) return@launch
            _uiState.update { it.withSlots() }
            if (status != ModelStatus.READY) downloadModel(id)
        }
    }

    /** Values the bounds refuse are dropped by the store; the panel just re-reads what stuck. */
    fun setGeneration(bound: GenerationBound, value: Float) {
        viewModelScope.launch {
            modelSelection.saveGeneration(bound, value)
            _uiState.value = _uiState.value.withGenerationProfile()
        }
    }

    fun resetGeneration() {
        viewModelScope.launch {
            modelSelection.resetGeneration()
            _uiState.value = _uiState.value.withGenerationProfile()
        }
    }

    fun setDetectionThreshold(value: Float) {
        detectionThresholdStore.save(value)
        _uiState.update { it.copy(detectionThreshold = detectionThresholdStore.load()) }
    }

    fun resetDetectionThreshold() {
        detectionThresholdStore.reset()
        _uiState.update { it.copy(detectionThreshold = detectionThresholdStore.load()) }
    }

    fun setTargetLanguage(language: TargetLanguage) {
        viewModelScope.launch {
            val switch = slotSelection.switchLanguage(language)
            _uiState.update { it.copy(languageOffer = switch as? LanguageSwitch.Offer).withSlots() }
        }
    }

    fun acceptLanguageOffer() {
        val offer = _uiState.value.languageOffer ?: return
        _uiState.update { it.copy(languageOffer = null) }
        viewModelScope.launch {
            if (!slotSelection.accept(offer, _uiState.value.deviceTotalMemBytes)) return@launch
            _uiState.update { it.withSlots() }
            if (slotSelection.pendingId(ModelType.LLM) == offer.model.id) downloadModel(offer.model.id)
        }
    }

    fun declineLanguageOffer() = _uiState.update { it.copy(languageOffer = null) }

    fun setPageRecall(enabled: Boolean) {
        pageRecallStore.save(enabled)
        _uiState.update { it.copy(pageRecall = pageRecallStore.load()) }
    }

    fun setResourceLimit(limit: ResourceLimit, value: Int) =
        changeResourceLimits(mapOf(limit to value)) { modelSelection.saveResourceLimit(limit, value) }

    fun resetResourceLimits() =
        changeResourceLimits(ResourceLimit.entries.associateWith { it.default }) { modelSelection.resetResourceLimits() }

    /**
     * Refused when it would push the model in use out of the fit budget, so reading keeps working. Like [pickModel],
     * off the main thread: a threads or context change waits out any in-flight generation.
     */
    private fun changeResourceLimits(changes: Map<ResourceLimit, Int>, apply: suspend () -> Unit) {
        viewModelScope.launch {
            val blocker = slotSelection.limitBlocker(changes, _uiState.value.deviceTotalMemBytes)
            if (blocker == null) apply()
            _uiState.update { state ->
                state.copy(
                    resourceLimits = storedResourceLimits(),
                    limitRefusal = blocker?.let { LimitRefusal("${it.name} needs more memory than that allows. Pick a smaller model first.") }
                )
            }
        }
    }

    fun refreshResources() {
        viewModelScope.launch {
            val readout = withContext(Dispatchers.Default) { resourceMonitor.sample(modelSelection.lastPageDurationMs(), translationPipeline.lastPageTotalMs) }
            _uiState.update { it.copy(resources = readout) }
        }
    }

    private fun storedResourceLimits(): Map<ResourceLimit, Int> =
        ResourceLimit.entries.associateWith(modelSelection::resourceLimit)

    private fun SettingsUiState.withGenerationProfile(): SettingsUiState {
        val loaded = modelSelection.generationProfile()
        val recovered = loaded.recovered.map { it.label } +
            if (modelSelection.storedLlmModelRecovered()) listOf("Model") else emptyList()
        return copy(
            generation = loaded.params,
            generationDefaults = modelSelection.generationDefaults(),
            recoveryWarning = recovered.takeIf { it.isNotEmpty() }
                ?.let { "Some saved settings were invalid and are using their defaults: ${it.joinToString()}." }
        )
    }

    /**
     * Which model sits in each slot — and, with it, the generation profile, because the selected
     * deliverable is what the reader's saved fields lie on top of (ADR-0017). Resolved together so
     * no path that commits a pick can leave the panel showing the previous model's defaults.
     */
    private fun SettingsUiState.withSlots(): SettingsUiState = copy(
        // With the slots, since a download that never completes can return the language to English.
        targetLanguage = slotSelection.targetLanguage(),
        selectedIds = ModelType.entries.mapNotNull { type -> slotSelection.selectedId(type)?.let { type to it } }.toMap(),
        pendingIds = ModelType.entries.mapNotNull { type -> slotSelection.pendingId(type)?.let { type to it } }.toMap()
    ).withGenerationProfile()

    fun setFontSizeScale(scale: Float) {
        sharedPreferences.edit().putFloat(Constants.PREF_FONT_SIZE_SCALE, scale).apply()
        _uiState.value = _uiState.value.copy(fontSizeScale = scale)
    }

    fun downloadModel(modelId: String) {
        viewModelScope.launch {
            slotSelection.download(modelId)
            _uiState.update { it.withSlots() }
        }
    }

    fun cancelDownload(modelId: String) {
        slotSelection.cancel(modelId)
        _uiState.update { it.withSlots() }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            slotSelection.delete(modelId)
            _uiState.update { it.withSlots() }
        }
    }
}

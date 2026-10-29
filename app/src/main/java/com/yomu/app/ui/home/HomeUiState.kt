package com.yomu.app.ui.home

import com.yomu.app.db.entities.ModelEntity
import com.yomu.app.db.entities.ModelType
import com.yomu.app.db.entities.TranslationEntity
import com.yomu.app.service.LanguageSwitch
import com.yomu.app.service.ModelSlotSelection
import com.yomu.app.service.SlotDeliverable
import com.yomu.app.service.TriggerMode
import com.yomu.app.service.TriggerModeStore
import com.yomu.app.translation.FitBudget
import com.yomu.app.translation.LlmModelCatalog
import com.yomu.core.RuntimeLimits
import com.yomu.core.TargetLanguage

data class HomeUiState(
    val isServiceRunning: Boolean = false,
    /** Where reading starts a translation, for the hint under the reading control. */
    val triggerMode: TriggerMode = TriggerModeStore.DEFAULT,
    val readiness: Readiness = Readiness.NeedsBoth,
    val models: List<ModelEntity> = emptyList(),
    val modelsLoading: Boolean = true,
    val setupComplete: Boolean = false,
    val setupVisible: Boolean = false,
    val setupStarted: Boolean = false,
    val setupDownloadsReady: Boolean = false,
    val downloading: String? = null,
    val downloadProgress: Int = 0,
    val setupError: Boolean = false,
    /** Name of the chosen model setup refused to download because it is outside the fit budget, or null. */
    val setupBlockedBy: String? = null,
    val deliverables: Map<ModelType, List<SlotDeliverable>> = emptyMap(),
    val chosenIds: Map<ModelType, String> = emptyMap(),
    val translationSelectedId: String? = null,
    val translationPendingId: String? = null,
    val targetLanguage: TargetLanguage = TargetLanguage.ENGLISH,
    /** The model switch a language choice is waiting on the reader to accept or decline (#338), or null. */
    val languageOffer: LanguageSwitch.Offer? = null,
    val setupDownloadBytes: Long = 0L,
    val fitBudget: FitBudget = FitBudget(0L, LlmModelCatalog.DEFAULT_FIT_BUDGET_PERCENT, RuntimeLimits.DEFAULT_CONTEXT_TOKENS),
    val onMeteredNetwork: Boolean = false,
    val pagesTranslatedToday: Int = 0,
    val translations: List<TranslationEntity> = emptyList(),
    val hasRead: Boolean = false,
    val historyLoading: Boolean = true,
    val historyError: Boolean = false,
    val clearError: Boolean = false,
    val serviceError: Boolean = false,
    val confirmation: String? = null
) {
    val readingStatus: ReadingStatus
        get() = resolveReadingStatus(readiness == Readiness.Ready && setupComplete, isServiceRunning)

    fun fits(deliverable: SlotDeliverable): Boolean = ModelSlotSelection.fits(deliverable.id, fitBudget)

    fun needsSlowWarning(deliverable: SlotDeliverable): Boolean =
        ModelSlotSelection.needsSlowWarning(deliverable, fitBudget)

    fun languageBlock(deliverable: SlotDeliverable): String? = ModelSlotSelection.languageBlock(deliverable, targetLanguage)

    fun languageUnavailable(language: TargetLanguage): String? =
        ModelSlotSelection.languageUnavailable(language, deliverables[ModelType.LLM].orEmpty(), ::fits)

    fun languageChipLabel(): String = "Japanese → ${targetLanguage.promptName}"

    fun translationChipLabel(): String {
        val options = deliverables[ModelType.LLM].orEmpty()
        val selected = options.firstOrNull { it.id == translationSelectedId }?.name ?: "No model selected"
        return options.firstOrNull { it.id == translationPendingId }?.let { "$selected · ${it.name} when downloaded" } ?: selected
    }
}

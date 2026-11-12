package com.yomu.app.ui.settings

import com.yomu.app.db.entities.ModelEntity
import com.yomu.app.db.entities.ModelType
import com.yomu.app.detection.DetectionThresholdStore
import com.yomu.app.service.LanguageSwitch
import com.yomu.app.service.ModelSlotSelection
import com.yomu.app.service.PageRecallStore
import com.yomu.app.service.SlotDeliverable
import com.yomu.app.translation.FitBudget
import com.yomu.app.translation.ResourceLimit
import com.yomu.app.ui.UiText
import com.yomu.core.Constants
import com.yomu.core.GenerationBound
import com.yomu.core.GenerationParams
import com.yomu.core.TargetLanguage

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
    /** Names of every stored value that was recovered to its default, or null. */
    val recoveryWarning: List<UiText>? = null
) {
    val generationOverridden: Boolean
        get() = GenerationBound.entries.any { it.read(generation) != it.read(generationDefaults) }

    fun fits(deliverable: SlotDeliverable): Boolean = ModelSlotSelection.fits(deliverable.id, fitBudget())

    fun needsSlowWarning(deliverable: SlotDeliverable): Boolean =
        ModelSlotSelection.needsSlowWarning(deliverable, fitBudget())

    fun languageBlock(deliverable: SlotDeliverable): UiText? = ModelSlotSelection.languageBlock(deliverable, targetLanguage)

    fun languageUnavailable(language: TargetLanguage): UiText? =
        ModelSlotSelection.languageUnavailable(language, deliverables[ModelType.LLM].orEmpty(), ::fits)

    private fun fitBudget() = FitBudget(
        deviceTotalMemBytes,
        resourceLimits.getValue(ResourceLimit.FIT_BUDGET_PERCENT),
        resourceLimits.getValue(ResourceLimit.CONTEXT_TOKENS)
    )
}

/** Identity equality on purpose: refusing the same change twice must still snap the slider back. */
class LimitRefusal(val message: UiText)

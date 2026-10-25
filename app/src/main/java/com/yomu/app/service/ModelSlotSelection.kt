package com.yomu.app.service

import com.yomu.app.db.entities.ModelStatus
import com.yomu.app.db.entities.ModelType
import com.yomu.app.translation.DeliverableSpeed
import com.yomu.app.translation.FitBudget
import com.yomu.app.translation.LlmModelCatalog
import com.yomu.app.translation.ResourceLimit
import com.yomu.app.translation.TargetLanguageStore
import com.yomu.app.translation.TranslationModelSelection
import com.yomu.core.Constants
import com.yomu.core.TargetLanguage
import com.yomu.pipeline.TranslationPipeline
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import javax.inject.Singleton

data class SlotDeliverable(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val licence: String,
    val experimental: Boolean = false,
    val speedOverride: DeliverableSpeed? = null,
    /** Null for the reading models, which write no language. */
    val multilingual: Boolean? = null
) {
    val languageLabel: String? get() = multilingual?.let { if (it) "Multilingual" else "English only" }

    /** Read off the download size unless the entry states its own, so a reader is told the same thing on every device. */
    val speed: DeliverableSpeed
        get() = speedOverride ?: when {
            sizeBytes <= FAST_MAX_BYTES -> DeliverableSpeed.FAST
            sizeBytes <= MEDIUM_MAX_BYTES -> DeliverableSpeed.MEDIUM
            else -> DeliverableSpeed.SLOW
        }

    val slow: Boolean get() = speed == DeliverableSpeed.SLOW

    companion object {
        private const val GB = 1024L * 1024 * 1024
        private const val FAST_MAX_BYTES = 3 * GB / 2
        private const val MEDIUM_MAX_BYTES = 7 * GB / 2
    }
}

/** What choosing a target language does (#338). */
sealed interface LanguageSwitch {
    /** The language is set. */
    data object Done : LanguageSwitch

    /**
     * [current] writes English only, so the language stays as it is unless the reader switches to [model].
     * [downloadBytes] is what that switch downloads, 0 when [model] is already downloaded.
     */
    data class Offer(val language: TargetLanguage, val current: SlotDeliverable, val model: SlotDeliverable, val downloadBytes: Long) :
        LanguageSwitch
}

/**
 * Which curated model fills each slot, and which one is waiting to take over. A model picked before it is READY is
 * held as pending, so the stored selection, and the model already working, stay put until its download completes.
 * Deleting a selected model empties its slot until another is picked.
 */
@Singleton
class ModelSlotSelection @Inject constructor(
    private val translation: TranslationModelSelection,
    private val reading: ReadingModelSelection,
    private val pipeline: TranslationPipeline,
    private val models: ModelManager,
    private val languages: TargetLanguageStore
) {
    private val pending = mutableMapOf<ModelType, String>()
    /** An accepted offer's language, set when its model takes over so no page asks the old model for it. */
    private var pendingLanguage: Pair<String, TargetLanguage>? = null
    private val downloads = mutableMapOf<String, Job>()
    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Percentage done of every running [download], by model id, whichever screen started it. */
    val progress: StateFlow<Map<String, Int>> = _progress.asStateFlow()

    fun deliverables(type: ModelType): List<SlotDeliverable> = when (type) {
        ModelType.LLM -> LlmModelCatalog.ALL.map {
            SlotDeliverable(it.id, it.displayName, it.sizeBytes, it.licenceWithCredit, it.experimental, it.speed, it.multilingual)
        }
        else -> ModelManager.REGISTRY.filter { it.type == type }.map {
            SlotDeliverable(
                it.id,
                it.name,
                it.fileSize + ModelManager.additionalFiles(it.id).sumOf { file -> file.size },
                READING_LICENCES.getValue(it.id)
            )
        }
    }

    fun selectedId(type: ModelType): String? =
        if (type == ModelType.LLM) translation.currentLlmModel().id.takeUnless { translation.llmModelCleared() }
        else reading.selected(type).id.takeUnless { reading.isCleared(type) }

    fun pendingId(type: ModelType): String? = pending[type]

    // Setup offers the curated default for a slot emptied by a delete, so it always has something to download.
    fun chosenId(type: ModelType): String =
        pending[type] ?: selectedId(type) ?: ModelManager.SLOT_DEFAULTS.getValue(type)

    fun ready(statuses: Map<String, ModelStatus>): Boolean =
        ModelType.entries.all { type -> selectedId(type)?.let(statuses::get) == ModelStatus.READY }

    fun downloadBytes(statuses: Map<String, ModelStatus>): Long = ModelType.entries.sumOf { type ->
        val id = chosenId(type).takeIf { statuses[it] != ModelStatus.READY }
        deliverables(type).firstOrNull { it.id == id }?.sizeBytes ?: 0L
    }

    /** Refuses a model outside the fit budget, and an English-only one while the language is not English. */
    suspend fun pick(type: ModelType, id: String, status: ModelStatus?, totalMemBytes: Long): Boolean {
        val deliverable = deliverables(type).firstOrNull { it.id == id } ?: return false
        if (!fits(id, fitBudget(totalMemBytes)) || languageBlock(deliverable, languages.load()) != null) return false
        if (type == ModelType.LLM && pendingLanguage?.first != id) pendingLanguage = null
        when {
            id == selectedId(type) -> pending.remove(type)
            status == ModelStatus.READY -> {
                pending.remove(type)
                commit(type, id)
            }
            else -> pending[type] = id
        }
        return true
    }

    fun fitBudget(totalMemBytes: Long): FitBudget = FitBudget(
        totalMemBytes,
        translation.resourceLimit(ResourceLimit.FIT_BUDGET_PERCENT),
        translation.resourceLimit(ResourceLimit.CONTEXT_TOKENS)
    )

    /** The translation model in use, or waiting to take over, that [changes] would push out of the fit budget; null if none. */
    fun limitBlocker(changes: Map<ResourceLimit, Int>, totalMemBytes: Long): SlotDeliverable? {
        val now = fitBudget(totalMemBytes)
        val after = now.copy(
            percent = changes[ResourceLimit.FIT_BUDGET_PERCENT] ?: now.percent,
            contextTokens = changes[ResourceLimit.CONTEXT_TOKENS] ?: now.contextTokens
        )
        val blocker = listOfNotNull(selectedId(ModelType.LLM), pending[ModelType.LLM])
            .firstOrNull { fits(it, now) && !fits(it, after) } ?: return null
        return deliverables(ModelType.LLM).firstOrNull { it.id == blocker }
    }

    suspend fun commitPending(statuses: Map<String, ModelStatus>) {
        for ((type, id) in pending.toMap()) {
            if (statuses[id] != ModelStatus.READY) continue
            pending.remove(type)
            commit(type, id)
        }
    }

    fun targetLanguage(): TargetLanguage = languages.load()

    /**
     * Sets [language] unless the translation model in use, or the one waiting to take over, writes English only;
     * then the default is offered instead and nothing changes until [accept].
     */
    suspend fun switchLanguage(language: TargetLanguage): LanguageSwitch {
        val options = deliverables(ModelType.LLM)
        val englishOnly = listOfNotNull(selectedId(ModelType.LLM), pending[ModelType.LLM])
            .mapNotNull { id -> options.firstOrNull { it.id == id } }
            .firstOrNull { languageBlock(it, language) != null }
        if (englishOnly == null) {
            languages.save(language)
            return LanguageSwitch.Done
        }
        val model = options.first { it.id == LlmModelCatalog.DEFAULT.id }
        val downloaded = models.getModel(model.id)?.status == ModelStatus.READY
        return LanguageSwitch.Offer(language, englishOnly, model, if (downloaded) 0L else model.sizeBytes)
    }

    /**
     * Picks the offered model; the language is set when that model takes over. A model that isn't downloaded waits as
     * pending, and the caller starts its [download]; if that download never completes, the language stays as it was.
     */
    suspend fun accept(offer: LanguageSwitch.Offer, totalMemBytes: Long): Boolean {
        if (!pick(ModelType.LLM, offer.model.id, models.getModel(offer.model.id)?.status, totalMemBytes)) return false
        if (selectedId(ModelType.LLM) == offer.model.id) languages.save(offer.language)
        else pendingLanguage = offer.model.id to offer.language
        return true
    }

    /** Why [language] can't be chosen on this device, or null. */
    fun languageUnavailable(language: TargetLanguage, totalMemBytes: Long): String? {
        val budget = fitBudget(totalMemBytes)
        return languageUnavailable(language, deliverables(ModelType.LLM)) { fits(it.id, budget) }
    }

    private fun dropPending(id: String) {
        pending.values.remove(id)
        if (pendingLanguage?.first == id) pendingLanguage = null
    }

    /**
     * One download per model, shared by every screen that picks, since two would write the same file. A pick waiting
     * on a download that failed, was refused or was cancelled would otherwise never take over, so it is dropped.
     */
    suspend fun download(id: String): Boolean {
        if (id in downloads) return false
        downloads[id] = currentCoroutineContext().job
        _progress.update { it + (id to 0) }
        var ready = false
        try {
            ready = models.downloadModel(id) { p -> _progress.update { it + (id to p.percentage.coerceIn(0, 100)) } }
        } finally {
            downloads.remove(id)
            _progress.update { it - id }
            if (!ready) dropPending(id)
        }
        return ready
    }

    fun cancel(id: String) {
        dropPending(id)
        downloads[id]?.cancel()
    }

    /** Unloads a selected model before its files go, so a running overlay never reads a deleted file. */
    suspend fun delete(id: String) {
        for (type in ModelType.entries.filter { selectedId(it) == id }) {
            when (type) {
                ModelType.LLM -> {
                    translation.clearLlmModel()
                    pipeline.unloadTranslation()
                }
                ModelType.DETECTION -> {
                    reading.clear(type)
                    pipeline.unloadDetection()
                }
                ModelType.OCR -> {
                    reading.clear(type)
                    pipeline.unloadOcr()
                }
            }
        }
        models.deleteModel(id)
    }

    private suspend fun commit(type: ModelType, id: String) {
        if (type == ModelType.LLM) {
            LlmModelCatalog.fromId(id)?.let { translation.selectLlmModel(it) }
            pendingLanguage?.takeIf { it.first == id }?.let { languages.save(it.second) }
            pendingLanguage = null
        } else {
            ModelManager.REGISTRY.firstOrNull { it.id == id }?.let(reading::select)
        }
    }

    companion object {
        private const val PPOCR_LICENCE = "Apache-2.0 (PP-OCRv5)"

        // ADR-0007: the detector's card says Apache-2.0, but it is a fine-tune of AGPL-3.0 Ultralytics YOLO26.
        private val READING_LICENCES = mapOf(
            Constants.BUBBLE_DETECTION_MODEL_ID to "AGPL-3.0 (YOLO26)",
            Constants.MANGA_OCR_MODEL_ID to "Apache-2.0 (manga-ocr)",
            Constants.PPOCR_CHINESE_MODEL_ID to PPOCR_LICENCE,
            Constants.PPOCR_KOREAN_MODEL_ID to PPOCR_LICENCE,
            Constants.PPOCR_LATIN_MODEL_ID to PPOCR_LICENCE,
            Constants.PPOCR_CYRILLIC_MODEL_ID to PPOCR_LICENCE
        )

        const val NO_MULTILINGUAL_FIT = "No Multilingual model fits this device"

        /** Why [deliverable] can't translate into [language], or null when it can. Reading models write no language. */
        fun languageBlock(deliverable: SlotDeliverable, language: TargetLanguage): String? =
            "Can't write ${language.promptName}".takeIf { language != TargetLanguage.ENGLISH && deliverable.multilingual == false }

        /** Why [language] can't be chosen, or null: any language but English needs a Multilingual model that [fits]. */
        fun languageUnavailable(language: TargetLanguage, deliverables: List<SlotDeliverable>, fits: (SlotDeliverable) -> Boolean): String? =
            NO_MULTILINGUAL_FIT.takeIf { language != TargetLanguage.ENGLISH && deliverables.none { it.multilingual == true && fits(it) } }

        /** A Slow deliverable asks before gigabytes are downloaded; one that doesn't fit can't be picked at all. */
        fun needsSlowWarning(deliverable: SlotDeliverable, budget: FitBudget): Boolean =
            deliverable.slow && fits(deliverable.id, budget)

        // Only translation models have a fit budget. Unreadable RAM (0 bytes) leaves only the exempt default (#298).
        fun fits(id: String, budget: FitBudget): Boolean {
            val option = LlmModelCatalog.fromId(id) ?: return true
            return LlmModelCatalog.canRunOnDevice(option, budget)
        }
    }
}

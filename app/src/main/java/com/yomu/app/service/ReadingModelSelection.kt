package com.yomu.app.service

import android.content.SharedPreferences
import com.yomu.app.db.entities.ModelEntity
import com.yomu.app.db.entities.ModelType
import com.yomu.core.Constants
import com.yomu.core.SourceLanguage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The curated model selected for the detection and OCR slots. An unset, unreadable or unknown stored id falls back to
 * the slot's default, so a registry change never leaves a slot empty.
 */
@Singleton
class ReadingModelSelection @Inject constructor(private val sharedPreferences: SharedPreferences) {

    fun selected(type: ModelType): ModelEntity {
        val stored = stored(type)
        return ModelManager.REGISTRY.firstOrNull { it.type == type && it.id == stored }
            ?: ModelManager.REGISTRY.single { it.id == ModelManager.SLOT_DEFAULTS.getValue(type) }
    }

    fun select(model: ModelEntity) {
        sharedPreferences.edit().putString(prefKey(model.type), model.id).apply()
    }

    /** The language the selected OCR model reads, which is the language every page is read in. */
    fun source(): SourceLanguage = sourceOf(selected(ModelType.OCR).id)

    /** An empty stored id marks a slot whose model was deleted; [select] stores an id over it. */
    fun isCleared(type: ModelType): Boolean = stored(type) == ""

    fun clear(type: ModelType) {
        sharedPreferences.edit().putString(prefKey(type), "").apply()
    }

    private fun stored(type: ModelType): String? =
        runCatching { sharedPreferences.getString(prefKey(type), null) }.getOrNull()

    private fun prefKey(type: ModelType): String = when (type) {
        ModelType.DETECTION -> Constants.PREF_DETECTION_MODEL
        ModelType.OCR -> Constants.PREF_OCR_MODEL
        ModelType.LLM -> throw IllegalArgumentException("The translation slot is selected through TranslationModelSelection")
    }

    companion object {
        private val OCR_SOURCES = mapOf(
            Constants.MANGA_OCR_MODEL_ID to SourceLanguage.JAPANESE,
            Constants.PPOCR_CHINESE_MODEL_ID to SourceLanguage.CHINESE,
            Constants.PPOCR_KOREAN_MODEL_ID to SourceLanguage.KOREAN,
            Constants.PPOCR_LATIN_MODEL_ID to SourceLanguage.LATIN_SCRIPT,
            Constants.PPOCR_CYRILLIC_MODEL_ID to SourceLanguage.CYRILLIC_SCRIPT
        )

        /** The source [ocrModelId] reads; Japanese for manga-ocr and any id it does not know. */
        fun sourceOf(ocrModelId: String): SourceLanguage = OCR_SOURCES[ocrModelId] ?: SourceLanguage.JAPANESE
    }
}

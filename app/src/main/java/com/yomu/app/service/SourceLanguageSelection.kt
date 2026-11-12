package com.yomu.app.service

import android.content.SharedPreferences
import com.yomu.app.db.entities.ModelType
import com.yomu.app.translation.SpokenLanguageStore
import com.yomu.core.SpokenLanguage
import javax.inject.Inject
import javax.inject.Singleton

/** The reader's source language (#444), chosen on Home or in Settings; null is Auto. */
@Singleton
class SourceLanguageSelection @Inject constructor(
    prefs: SharedPreferences,
    private val slotSelection: ModelSlotSelection,
    private val models: ModelManager
) {
    private val store = SpokenLanguageStore(prefs)

    fun current(): SpokenLanguage? = store.load()

    /**
     * Saves [language]. Auto leaves the OCR model alone; a language picks the OCR model that reads its script.
     * Returns that model's id when it isn't downloaded yet, so the caller can download it and it takes over when READY.
     */
    suspend fun choose(language: SpokenLanguage?, totalMemBytes: Long): String? {
        store.save(language)
        val id = ReadingModelSelection.ocrModelFor((language ?: return null).script)
        if (!slotSelection.pick(ModelType.OCR, id, models.getModel(id)?.status, totalMemBytes)) return null
        return id.takeIf { slotSelection.pendingId(ModelType.OCR) == id }
    }
}

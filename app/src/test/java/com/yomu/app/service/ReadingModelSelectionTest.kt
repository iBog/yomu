package com.yomu.app.service

import com.yomu.app.db.entities.ModelType
import com.yomu.app.translation.MapSharedPreferences
import com.yomu.core.Constants
import com.yomu.core.SourceLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingModelSelectionTest {

    private val prefs = MapSharedPreferences()
    private val selection = ReadingModelSelection(prefs)

    @Test
    fun `an unset slot falls back to its curated default`() {
        assertEquals(Constants.BUBBLE_DETECTION_MODEL_ID, selection.selected(ModelType.DETECTION).id)
        assertEquals(Constants.MANGA_OCR_MODEL_ID, selection.selected(ModelType.OCR).id)
    }

    @Test
    fun `a selection is stored per slot`() {
        val ocr = ModelManager.REGISTRY.first { it.type == ModelType.OCR }

        selection.select(ocr)

        assertEquals(ocr.id, prefs.values[Constants.PREF_OCR_MODEL])
        assertEquals(ocr, selection.selected(ModelType.OCR))
        assertEquals(null, prefs.values[Constants.PREF_DETECTION_MODEL])
    }

    @Test
    fun `clearing a slot empties only that slot until a model is selected`() {
        selection.clear(ModelType.OCR)

        assertTrue(selection.isCleared(ModelType.OCR))
        assertFalse(selection.isCleared(ModelType.DETECTION))

        selection.select(ModelManager.REGISTRY.first { it.type == ModelType.OCR })

        assertFalse(selection.isCleared(ModelType.OCR))
    }

    @Test
    fun `an unknown or wrong-slot stored id falls back to the slot default`() {
        prefs.values[Constants.PREF_DETECTION_MODEL] = "retired-model"
        prefs.values[Constants.PREF_OCR_MODEL] = Constants.BUBBLE_DETECTION_MODEL_ID

        assertEquals(Constants.BUBBLE_DETECTION_MODEL_ID, selection.selected(ModelType.DETECTION).id)
        assertEquals(Constants.MANGA_OCR_MODEL_ID, selection.selected(ModelType.OCR).id)
    }

    @Test
    fun `a wrongly typed stored id falls back to the slot default`() {
        prefs.values[Constants.PREF_DETECTION_MODEL] = 42

        assertEquals(Constants.BUBBLE_DETECTION_MODEL_ID, selection.selected(ModelType.DETECTION).id)
    }

    @Test
    fun `the selected OCR model fixes the language a page is read in`() {
        assertEquals(SourceLanguage.JAPANESE, selection.source())

        selection.select(ModelManager.REGISTRY.single { it.id == Constants.PPOCR_CYRILLIC_MODEL_ID })

        assertEquals(SourceLanguage.CYRILLIC_SCRIPT, selection.source())
    }

    @Test
    fun `every OCR model in the registry reads a known source`() {
        val sources = ModelManager.REGISTRY.filter { it.type == ModelType.OCR }.map { ReadingModelSelection.sourceOf(it.id) }

        assertEquals(SourceLanguage.entries.toSet(), sources.toSet())
    }

    @Test
    fun `a line reader downloads its dictionary with it`() {
        val files = ModelManager.additionalFiles(Constants.PPOCR_LATIN_MODEL_ID)

        assertEquals(listOf("ppocr_v5_latin_rec.yml"), files.map { it.fileName })
        assertTrue(files.single().url.endsWith("/inference.yml"))
    }
}

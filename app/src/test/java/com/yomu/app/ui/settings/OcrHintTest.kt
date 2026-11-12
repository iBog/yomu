package com.yomu.app.ui.settings

import com.yomu.app.R
import com.yomu.app.service.SlotDeliverable
import com.yomu.app.ui.UiText
import com.yomu.core.Constants
import com.yomu.core.SpokenLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrHintTest {
    private val manga = SlotDeliverable(Constants.MANGA_OCR_MODEL_ID, "MangaOCR", 1L, "x")
    private val latin = SlotDeliverable(Constants.PPOCR_LATIN_MODEL_ID, "PP-OCRv5 Latin", 1L, "x")
    private val cyrillic = SlotDeliverable(Constants.PPOCR_CYRILLIC_MODEL_ID, "PP-OCRv5 Cyrillic", 1L, "x")

    @Test
    fun `Auto says it keeps the reading model`() {
        assertEquals(UiText(R.string.settings_source_ocr_auto, "MangaOCR"), ocrHint(null, manga, null))
    }

    @Test
    fun `a language says which model reads it`() {
        assertEquals(
            UiText(R.string.settings_source_ocr_language, UiText(R.string.language_fr), "PP-OCRv5 Latin"),
            ocrHint(SpokenLanguage.FRENCH, latin, null)
        )
    }

    @Test
    fun `a model still downloading is named, with the one in use meanwhile`() {
        assertEquals(
            UiText(R.string.settings_source_ocr_pending, "PP-OCRv5 Cyrillic", "MangaOCR"),
            ocrHint(SpokenLanguage.RUSSIAN, manga, cyrillic)
        )
        assertEquals(
            UiText(R.string.settings_source_ocr_pending, "PP-OCRv5 Cyrillic", UiText(R.string.service_no_model_selected)),
            ocrHint(SpokenLanguage.RUSSIAN, null, cyrillic)
        )
    }

    @Test
    fun `a model chosen in Pipeline that does not read the language is said to leave the source as Auto`() {
        assertEquals(
            UiText(R.string.settings_source_ocr_mismatch, "MangaOCR", UiText(R.string.language_fr)),
            ocrHint(SpokenLanguage.FRENCH, manga, null)
        )
    }

    @Test
    fun `an empty reading slot says so`() {
        assertEquals(UiText(R.string.settings_source_ocr_none), ocrHint(SpokenLanguage.FRENCH, null, null))
    }
}

package com.yomu.app.ui.home

import com.yomu.app.R
import com.yomu.app.db.entities.ModelType
import com.yomu.app.service.SlotDeliverable
import com.yomu.app.ui.UiText
import com.yomu.core.SpokenLanguage
import com.yomu.core.TargetLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentSelectionTest {
    private val qwen = SlotDeliverable("qwen", "Qwen2.5 1.5B Instruct", 1L, "Apache-2.0", multilingual = true)
    private val cat = SlotDeliverable("cat", "CAT-Translate 1.4B", 1L, "MIT", multilingual = false)
    private val state = HomeUiState(deliverables = mapOf(ModelType.LLM to listOf(qwen, cat)))

    private fun model(name: String) = UiText(R.string.home_verbatim, name)

    @Test
    fun `the on-device model is named with its engine`() {
        assertEquals(
            UiText(R.string.home_selection_device, model("Qwen2.5 1.5B Instruct")),
            state.copy(selectedIds = mapOf(ModelType.LLM to "qwen")).engineSummary()
        )
    }

    @Test
    fun `a pending pick names the model that takes over when its download finishes`() {
        assertEquals(
            UiText(R.string.home_selection_device, UiText(R.string.home_chip_pending, model("Qwen2.5 1.5B Instruct"), "CAT-Translate 1.4B")),
            state.copy(selectedIds = mapOf(ModelType.LLM to "qwen"), pendingIds = mapOf(ModelType.LLM to "cat")).engineSummary()
        )
    }

    @Test
    fun `an empty translation slot says no model is selected`() {
        assertEquals(UiText(R.string.home_selection_device, UiText(R.string.home_chip_no_model)), state.engineSummary())
    }

    @Test
    fun `the network model is named instead of the on-device one`() {
        assertEquals(
            UiText(R.string.home_selection_network, "qwen2.5:7b"),
            state.copy(selectedIds = mapOf(ModelType.LLM to "qwen"), networkModel = "qwen2.5:7b").engineSummary()
        )
    }

    @Test
    fun `the detector and the OCR model are named, with a pending successor`() {
        val readers = HomeUiState(
            deliverables = mapOf(
                ModelType.DETECTION to listOf(SlotDeliverable("rt", "RT-DETR", 1L, "x"), SlotDeliverable("yolo", "YOLO", 1L, "x")),
                ModelType.OCR to listOf(SlotDeliverable("manga", "MangaOCR", 1L, "x"), SlotDeliverable("latin", "Latin", 1L, "x"))
            ),
            selectedIds = mapOf(ModelType.DETECTION to "rt", ModelType.OCR to "manga"),
            pendingIds = mapOf(ModelType.OCR to "latin")
        )

        assertEquals(UiText(R.string.home_selection_detector, model("RT-DETR")), readers.detectorSummary())
        assertEquals(
            UiText(R.string.home_selection_ocr, UiText(R.string.home_chip_pending, model("MangaOCR"), "Latin")),
            readers.ocrSummary()
        )
        assertEquals(UiText(R.string.home_selection_ocr, UiText(R.string.home_chip_no_model)), HomeUiState().ocrSummary())
    }

    @Test
    fun `the languages are Auto to the target until a source is chosen`() {
        assertEquals(
            UiText(R.string.home_selection_languages, UiText(R.string.language_auto), UiText(R.string.language_en)),
            state.languagesSummary()
        )
        assertEquals(
            UiText(R.string.home_selection_languages, UiText(R.string.language_fr), UiText(R.string.language_es)),
            state.copy(spoken = SpokenLanguage.FRENCH, targetLanguage = TargetLanguage.SPANISH).languagesSummary()
        )
    }

    @Test
    fun `an English-only model is unavailable with the reason while the language is not English`() {
        val spanish = state.copy(targetLanguage = TargetLanguage.SPANISH)

        assertEquals(UiText(R.string.language_block, UiText(R.string.language_es)), spanish.languageBlock(cat))
        assertNull(spanish.languageBlock(qwen))
        assertNull(state.languageBlock(cat))
    }
}

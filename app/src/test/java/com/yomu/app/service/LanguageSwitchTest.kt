package com.yomu.app.service

import com.yomu.app.R
import com.yomu.app.db.entities.ModelEntity
import com.yomu.app.db.entities.ModelStatus
import com.yomu.app.db.entities.ModelType
import com.yomu.app.translation.LlmModelCatalog
import com.yomu.app.translation.MapSharedPreferences
import com.yomu.app.translation.TargetLanguageStore
import com.yomu.app.translation.TranslationModelSelection
import com.yomu.app.ui.UiText
import com.yomu.core.Constants
import com.yomu.core.TargetLanguage
import com.yomu.ml.LlamaTranslationBridge
import com.yomu.pipeline.TranslationPipeline
import com.yomu.pipeline.bubble.BubbleDetector
import com.yomu.pipeline.context.ContextAssembler
import com.yomu.pipeline.ocr.OcrEngine
import com.yomu.pipeline.translation.TranslationEngine
import com.yomu.pipeline.typesetting.Typesetter
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class LanguageSwitchTest {

    private val prefs = MapSharedPreferences()
    private val eightGb = 8L * 1024 * 1024 * 1024
    private val cat = Constants.CAT_TRANSLATION_MODEL_ID
    private val qwen = LlmModelCatalog.DEFAULT.id

    private fun newSelection(ready: Set<String> = emptySet(), downloads: Boolean = true): ModelSlotSelection {
        val models = Mockito.mock(ModelManager::class.java) { invocation ->
            when (invocation.method.name) {
                "getModel" -> (invocation.arguments[0] as String).takeIf { it in ready }?.let(::readyEntity)
                "downloadModel" -> downloads
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val translation = TranslationModelSelection(Mockito.mock(LlamaTranslationBridge::class.java), prefs, File("models"))
        val pipeline = TranslationPipeline(
            Mockito.mock(BubbleDetector::class.java),
            Mockito.mock(OcrEngine::class.java),
            ContextAssembler(),
            TranslationEngine(translation::current, translation::close),
            Mockito.mock(Typesetter::class.java)
        )
        return ModelSlotSelection(translation, ReadingModelSelection(prefs), pipeline, models, TargetLanguageStore(prefs))
    }

    private fun readyEntity(id: String) =
        ModelEntity(id, id, ModelType.LLM, "$id.gguf", 1L, "", "", ModelStatus.READY, version = "1", isRequired = false)

    private suspend fun ModelSlotSelection.withCat() = apply { pick(ModelType.LLM, cat, ModelStatus.READY, eightGb) }

    @Test
    fun `a Multilingual model in use switches the language at once`() = runTest {
        val selection = newSelection()

        assertEquals(LanguageSwitch.Done, selection.switchLanguage(TargetLanguage.SPANISH))

        assertEquals(TargetLanguage.SPANISH, selection.targetLanguage())
    }

    @Test
    fun `an English-only model in use offers the default with its download size and keeps English`() = runTest {
        val selection = newSelection().withCat()

        val offer = selection.switchLanguage(TargetLanguage.SPANISH) as LanguageSwitch.Offer

        assertEquals(TargetLanguage.SPANISH, offer.language)
        assertEquals(cat, offer.current.id)
        assertEquals(qwen, offer.model.id)
        assertEquals(LlmModelCatalog.DEFAULT.sizeBytes, offer.downloadBytes)
        assertEquals(TargetLanguage.ENGLISH, selection.targetLanguage())
    }

    @Test
    fun `an offer of a downloaded default names no download`() = runTest {
        val selection = newSelection(ready = setOf(qwen)).withCat()

        val offer = selection.switchLanguage(TargetLanguage.SPANISH) as LanguageSwitch.Offer

        assertEquals(0L, offer.downloadBytes)
    }

    @Test
    fun `an English-only pick waiting on its download also needs the offer`() = runTest {
        val selection = newSelection()
        selection.pick(ModelType.LLM, cat, ModelStatus.AVAILABLE, eightGb)

        assertTrue(selection.switchLanguage(TargetLanguage.FRENCH) is LanguageSwitch.Offer)
    }

    @Test
    fun `accepting a downloaded default selects it and sets the language`() = runTest {
        val selection = newSelection(ready = setOf(qwen)).withCat()
        val offer = selection.switchLanguage(TargetLanguage.SPANISH) as LanguageSwitch.Offer

        assertTrue(selection.accept(offer, eightGb))

        assertEquals(qwen, selection.selectedId(ModelType.LLM))
        assertEquals(TargetLanguage.SPANISH, selection.targetLanguage())
    }

    @Test
    fun `accepting a default that isn't downloaded keeps English until the default takes over`() = runTest {
        val selection = newSelection().withCat()
        val offer = selection.switchLanguage(TargetLanguage.SPANISH) as LanguageSwitch.Offer

        assertTrue(selection.accept(offer, eightGb))

        assertEquals(qwen, selection.pendingId(ModelType.LLM))
        assertEquals(TargetLanguage.ENGLISH, selection.targetLanguage())

        selection.commitPending(mapOf(qwen to ModelStatus.READY))

        assertEquals(qwen, selection.selectedId(ModelType.LLM))
        assertEquals(TargetLanguage.SPANISH, selection.targetLanguage())
    }

    @Test
    fun `another pick replacing the accepted default drops the language waiting on it`() = runTest {
        val qwen35 = Constants.QWEN35_2B_MODEL_ID
        val selection = newSelection().withCat()
        selection.accept(selection.switchLanguage(TargetLanguage.SPANISH) as LanguageSwitch.Offer, eightGb)

        selection.pick(ModelType.LLM, qwen35, ModelStatus.AVAILABLE, eightGb)
        selection.commitPending(mapOf(qwen35 to ModelStatus.READY))

        assertEquals(TargetLanguage.ENGLISH, selection.targetLanguage())
    }

    @Test
    fun `a failed download of the accepted default leaves the language English`() = runTest {
        val selection = newSelection(downloads = false).withCat()
        selection.accept(selection.switchLanguage(TargetLanguage.SPANISH) as LanguageSwitch.Offer, eightGb)

        assertFalse(selection.download(qwen))

        assertEquals(cat, selection.selectedId(ModelType.LLM))
        selection.commitPending(mapOf(qwen to ModelStatus.READY))
        assertEquals(TargetLanguage.ENGLISH, selection.targetLanguage())
    }

    @Test
    fun `cancelling the accepted default's download leaves the language English`() = runTest {
        val selection = newSelection().withCat()
        selection.accept(selection.switchLanguage(TargetLanguage.SPANISH) as LanguageSwitch.Offer, eightGb)

        selection.cancel(qwen)

        assertNull(selection.pendingId(ModelType.LLM))
        selection.commitPending(mapOf(qwen to ModelStatus.READY))
        assertEquals(TargetLanguage.ENGLISH, selection.targetLanguage())
    }

    @Test
    fun `English never needs the offer`() = runTest {
        val selection = newSelection().withCat()

        assertEquals(LanguageSwitch.Done, selection.switchLanguage(TargetLanguage.ENGLISH))
    }

    @Test
    fun `an English-only model can't be picked while the language is not English`() = runTest {
        val selection = newSelection()
        selection.switchLanguage(TargetLanguage.GERMAN)

        assertFalse(selection.pick(ModelType.LLM, cat, ModelStatus.READY, eightGb))

        assertEquals(qwen, selection.selectedId(ModelType.LLM))
    }

    @Test
    fun `an English-only model is blocked for another language with the reason`() {
        val catEntry = SlotDeliverable(cat, "CAT-Translate 0.8B", 1L, "MIT", multilingual = false)
        val qwenEntry = SlotDeliverable(qwen, "Qwen2.5 1.5B Instruct", 1L, "Apache-2.0", multilingual = true)
        val ocrEntry = SlotDeliverable("ocr", "manga-ocr", 1L, "Apache-2.0")

        assertEquals(UiText(R.string.language_block, UiText(R.string.language_es)), ModelSlotSelection.languageBlock(catEntry, TargetLanguage.SPANISH))
        assertNull(ModelSlotSelection.languageBlock(catEntry, TargetLanguage.ENGLISH))
        assertNull(ModelSlotSelection.languageBlock(qwenEntry, TargetLanguage.SPANISH))
        assertNull(ModelSlotSelection.languageBlock(ocrEntry, TargetLanguage.SPANISH))
    }

    @Test
    fun `other languages are unavailable with the reason when no Multilingual model fits`() {
        val catEntry = SlotDeliverable(cat, "CAT-Translate 0.8B", 1L, "MIT", multilingual = false)
        val qwenEntry = SlotDeliverable(qwen, "Qwen2.5 1.5B Instruct", 1L, "Apache-2.0", multilingual = true)
        val noneFit = { _: SlotDeliverable -> false }
        val allFit = { _: SlotDeliverable -> true }

        assertEquals(
            ModelSlotSelection.NO_MULTILINGUAL_FIT,
            ModelSlotSelection.languageUnavailable(TargetLanguage.FRENCH, listOf(catEntry, qwenEntry), noneFit)
        )
        assertEquals(
            ModelSlotSelection.NO_MULTILINGUAL_FIT,
            ModelSlotSelection.languageUnavailable(TargetLanguage.FRENCH, listOf(catEntry), allFit)
        )
        assertNull(ModelSlotSelection.languageUnavailable(TargetLanguage.ENGLISH, listOf(catEntry), noneFit))
        assertNull(ModelSlotSelection.languageUnavailable(TargetLanguage.FRENCH, listOf(catEntry, qwenEntry), allFit))
    }

    @Test
    fun `the default keeps other languages available on any device, even with unknown memory`() {
        assertNull(newSelection().languageUnavailable(TargetLanguage.FRENCH, 0L))
    }
}

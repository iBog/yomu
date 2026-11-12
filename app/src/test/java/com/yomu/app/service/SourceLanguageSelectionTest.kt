package com.yomu.app.service

import com.yomu.app.db.entities.ModelType
import com.yomu.app.translation.MapSharedPreferences
import com.yomu.app.translation.TargetLanguageStore
import com.yomu.app.translation.TranslationModelSelection
import com.yomu.core.Constants
import com.yomu.core.SpokenLanguage
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
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito

class SourceLanguageSelectionTest {
    private val prefs = MapSharedPreferences()
    private val eightGb = 8L * 1024 * 1024 * 1024
    private val models = Mockito.mock(ModelManager::class.java)
    private val reading = ReadingModelSelection(prefs)

    private fun selection(): SourceLanguageSelection {
        val translation = TranslationModelSelection(Mockito.mock(LlamaTranslationBridge::class.java), prefs, File("models"))
        val pipeline = TranslationPipeline(
            Mockito.mock(BubbleDetector::class.java),
            Mockito.mock(OcrEngine::class.java),
            ContextAssembler(),
            TranslationEngine(translation::current, translation::close),
            Mockito.mock(Typesetter::class.java)
        )
        val slots = ModelSlotSelection(translation, reading, pipeline, models, TargetLanguageStore(prefs))
        return SourceLanguageSelection(prefs, slots, models)
    }

    @Test
    fun `Auto is saved and leaves the OCR model alone`() = runTest {
        val selection = selection()
        selection.choose(SpokenLanguage.FRENCH, eightGb)

        assertNull(selection.choose(null, eightGb))

        assertNull(selection.current())
        assertEquals(Constants.MANGA_OCR_MODEL_ID, reading.selected(ModelType.OCR).id)
    }

    @Test
    fun `a language whose OCR model is not downloaded is saved and the model is returned to download`() = runTest {
        val selection = selection()

        assertEquals(Constants.PPOCR_LATIN_MODEL_ID, selection.choose(SpokenLanguage.FRENCH, eightGb))

        assertEquals(SpokenLanguage.FRENCH, selection.current())
        assertEquals(Constants.MANGA_OCR_MODEL_ID, reading.selected(ModelType.OCR).id)
    }

    @Test
    fun `a language the selected OCR model already reads needs no download`() = runTest {
        val selection = selection()

        assertNull(selection.choose(SpokenLanguage.JAPANESE, eightGb))

        assertEquals(SpokenLanguage.JAPANESE, selection.current())
    }
}

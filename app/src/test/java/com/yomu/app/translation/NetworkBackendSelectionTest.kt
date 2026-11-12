package com.yomu.app.translation

import com.yomu.app.db.entities.ModelStatus
import com.yomu.app.db.entities.ModelType
import com.yomu.app.service.LanguageSwitch
import com.yomu.app.service.ModelManager
import com.yomu.app.service.ModelSlotSelection
import com.yomu.app.service.ReadingModelSelection
import com.yomu.app.translation.network.NetworkModelClient
import com.yomu.app.translation.network.NetworkModelStore
import com.yomu.app.translation.network.NetworkTranslationSlot
import com.yomu.app.translation.network.TranslationBackend
import com.yomu.core.Constants
import com.yomu.core.TargetLanguage
import com.yomu.ml.LlamaTranslationBridge
import com.yomu.pipeline.TranslationPipeline
import com.yomu.pipeline.bubble.BubbleDetector
import com.yomu.pipeline.context.ContextAssembler
import com.yomu.pipeline.ocr.OcrEngine
import com.yomu.pipeline.translation.TranslationEngine
import com.yomu.pipeline.typesetting.Typesetter
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File

class NetworkBackendSelectionTest {
    private val prefs = MapSharedPreferences()
    private val store = NetworkModelStore(prefs, MapSharedPreferences())
    private val llama = Mockito.mock(LlamaTranslationBridge::class.java)
    private val network = NetworkTranslationSlot(NetworkModelClient(OkHttpClient()), store)
    private val selection = TranslationModelSelection(llama, prefs, File("models"), 0L, network, store)
    private val eightGb = 8L * 1024 * 1024 * 1024

    private fun useNetwork() {
        store.saveUrl("http://h:11434")
        store.saveModel("qwen")
        store.saveBackend(TranslationBackend.NETWORK)
    }

    private fun slots(): ModelSlotSelection {
        val pipeline = TranslationPipeline(
            Mockito.mock(BubbleDetector::class.java),
            Mockito.mock(OcrEngine::class.java),
            ContextAssembler(),
            TranslationEngine(selection::current, selection::close),
            Mockito.mock(Typesetter::class.java)
        )
        return ModelSlotSelection(selection, ReadingModelSelection(prefs), pipeline, Mockito.mock(ModelManager::class.java), TargetLanguageStore(prefs))
    }

    @Test
    fun `pages go to the model on the phone until the network model is chosen and set up`() {
        assertSame(llama, selection.current())

        store.saveBackend(TranslationBackend.NETWORK)
        assertSame(llama, selection.current())

        useNetwork()
        assertSame(network, selection.current())
        assertEquals("qwen", selection.networkModelName())

        store.saveBackend(TranslationBackend.DEVICE)
        assertSame(llama, selection.current())
        assertNull(selection.networkModelName())
    }

    @Test
    fun `the recall key changes with the backend, the server and the model`() {
        val onDevice = selection.activeModelKey()
        useNetwork()
        val first = selection.activeModelKey()
        store.saveModel("gemma")
        val second = selection.activeModelKey()

        assertNotEquals(onDevice, first)
        assertNotEquals(first, second)
        assertTrue(first!!.contains("http://h:11434") && first.contains("qwen"))
    }

    @Test
    fun `the network model writes every language, so an English-only model in use does not block one`() = runTest {
        val slots = slots()
        slots.pick(ModelType.LLM, Constants.CAT_TRANSLATION_MODEL_ID, ModelStatus.READY, eightGb)
        assertTrue(slots.switchLanguage(TargetLanguage.SPANISH) is LanguageSwitch.Offer)

        useNetwork()

        assertEquals(LanguageSwitch.Done, slots.switchLanguage(TargetLanguage.SPANISH))
        assertEquals(TargetLanguage.SPANISH, slots.targetLanguage())
        assertNull(slots.languageUnavailable(TargetLanguage.FRENCH, 0L))
    }

    @Test
    fun `slots are ready without a translation model while the network model is in use`() = runTest {
        val slots = slots()
        val reading = listOf(slots.selectedId(ModelType.DETECTION), slots.selectedId(ModelType.OCR)).associate { it!! to ModelStatus.READY }
        assertFalse(slots.ready(reading))

        useNetwork()

        assertTrue(slots.ready(reading))
    }
}

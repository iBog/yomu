package com.yomu.app.service

import com.yomu.app.db.entities.ModelStatus
import com.yomu.app.db.entities.ModelType
import com.yomu.app.translation.DeliverableSpeed
import com.yomu.app.translation.FitBudget
import com.yomu.app.translation.LlmModelCatalog
import com.yomu.app.translation.MapSharedPreferences
import com.yomu.app.translation.ResourceLimit
import com.yomu.app.translation.TargetLanguageStore
import com.yomu.app.translation.TranslationModelSelection
import com.yomu.core.Constants
import com.yomu.ml.LlamaTranslationBridge
import com.yomu.pipeline.TranslationPipeline
import com.yomu.pipeline.bubble.BubbleDetector
import com.yomu.pipeline.context.ContextAssembler
import com.yomu.pipeline.ocr.OcrEngine
import com.yomu.pipeline.translation.TranslationEngine
import com.yomu.pipeline.typesetting.Typesetter
import java.io.File
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class ModelSlotSelectionTest {

    private val prefs = MapSharedPreferences()
    private val llama = Mockito.mock(LlamaTranslationBridge::class.java)
    private val detector = Mockito.mock(BubbleDetector::class.java)
    private val ocr = Mockito.mock(OcrEngine::class.java)
    private val modelManager = Mockito.mock(ModelManager::class.java)
    private val selection = newSelection()

    private fun newSelection(models: ModelManager = modelManager): ModelSlotSelection {
        val translation = TranslationModelSelection(llama, prefs, File("models"))
        val pipeline = TranslationPipeline(
            detector,
            ocr,
            ContextAssembler(),
            TranslationEngine(translation::current, translation::close),
            Mockito.mock(Typesetter::class.java)
        )
        return ModelSlotSelection(translation, ReadingModelSelection(prefs), pipeline, models, TargetLanguageStore(prefs))
    }

    private fun downloadingWith(result: Any): ModelManager = Mockito.mock(ModelManager::class.java) { invocation ->
        if (invocation.method.name == "downloadModel") result else Mockito.RETURNS_DEFAULTS.answer(invocation)
    }

    private fun downloadCalls(models: ModelManager): Int =
        Mockito.mockingDetails(models).invocations.count { it.method.name == "downloadModel" }

    @Test
    fun `a failed download drops the pick waiting on it`() = runTest {
        val selection = newSelection(downloadingWith(false))
        selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_MODEL_ID, ModelStatus.AVAILABLE, eightGb)

        assertFalse(selection.download(Constants.CAT_TRANSLATION_MODEL_ID))

        assertNull(selection.pendingId(ModelType.LLM))
    }

    @Test
    fun `a model already downloading is not downloaded a second time`() = runTest {
        val models = downloadingWith(COROUTINE_SUSPENDED)
        val selection = newSelection(models)
        selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_MODEL_ID, ModelStatus.AVAILABLE, eightGb)
        backgroundScope.launch { selection.download(Constants.CAT_TRANSLATION_MODEL_ID) }
        runCurrent()

        assertFalse(selection.download(Constants.CAT_TRANSLATION_MODEL_ID))

        assertEquals(1, downloadCalls(models))
        assertEquals(Constants.CAT_TRANSLATION_MODEL_ID, selection.pendingId(ModelType.LLM))
    }
    @Test
    fun `a running download's progress is visible whichever screen started it`() = runTest {
        val selection = newSelection(downloadingWith(COROUTINE_SUSPENDED))
        backgroundScope.launch { selection.download(Constants.CAT_TRANSLATION_MODEL_ID) }
        runCurrent()

        assertEquals(mapOf(Constants.CAT_TRANSLATION_MODEL_ID to 0), selection.progress.value)
    }

    @Test
    fun `a finished download leaves no progress behind`() = runTest {
        val selection = newSelection(downloadingWith(false))

        selection.download(Constants.CAT_TRANSLATION_MODEL_ID)

        assertEquals(emptyMap<String, Int>(), selection.progress.value)
    }

    @Test
    fun `cancelling stops a download another screen started and drops the pick waiting on it`() = runTest {
        val selection = newSelection(downloadingWith(COROUTINE_SUSPENDED))
        selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_MODEL_ID, ModelStatus.AVAILABLE, eightGb)
        val download = backgroundScope.launch { selection.download(Constants.CAT_TRANSLATION_MODEL_ID) }
        runCurrent()

        selection.cancel(Constants.CAT_TRANSLATION_MODEL_ID)

        assertTrue(download.isCancelled)
        assertNull(selection.pendingId(ModelType.LLM))
    }

    private val oneGb = 1L * 1024 * 1024 * 1024
    private val eightGb = 8L * 1024 * 1024 * 1024

    @Test
    fun `picking a model that isn't downloaded records a pending choice and leaves the stored selection unchanged`() = runTest {
        assertTrue(selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_MODEL_ID, ModelStatus.AVAILABLE, eightGb))

        assertEquals(Constants.CAT_TRANSLATION_MODEL_ID, selection.pendingId(ModelType.LLM))
        assertEquals(LlmModelCatalog.DEFAULT.id, selection.selectedId(ModelType.LLM))
        assertNull(prefs.values[Constants.PREF_LLM_MODEL])
    }

    @Test
    fun `a pending choice waits while its download runs`() = runTest {
        selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_MODEL_ID, ModelStatus.AVAILABLE, eightGb)

        selection.commitPending(mapOf(Constants.CAT_TRANSLATION_MODEL_ID to ModelStatus.DOWNLOADING))

        assertEquals(Constants.CAT_TRANSLATION_MODEL_ID, selection.pendingId(ModelType.LLM))
        assertEquals(LlmModelCatalog.DEFAULT.id, selection.selectedId(ModelType.LLM))
    }

    @Test
    fun `reaching READY commits the pending choice`() = runTest {
        selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_MODEL_ID, ModelStatus.AVAILABLE, eightGb)

        selection.commitPending(mapOf(Constants.CAT_TRANSLATION_MODEL_ID to ModelStatus.READY))

        assertNull(selection.pendingId(ModelType.LLM))
        assertEquals(Constants.CAT_TRANSLATION_MODEL_ID, selection.selectedId(ModelType.LLM))
        assertEquals(Constants.CAT_TRANSLATION_MODEL_ID, prefs.values[Constants.PREF_LLM_MODEL])
    }

    @Test
    fun `picking a READY model commits immediately and replaces a pending choice`() = runTest {
        selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_MODEL_ID, ModelStatus.DOWNLOADING, eightGb)

        assertTrue(selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_14B_MODEL_ID, ModelStatus.READY, eightGb))
        selection.commitPending(mapOf(Constants.CAT_TRANSLATION_MODEL_ID to ModelStatus.READY))

        assertNull(selection.pendingId(ModelType.LLM))
        assertEquals(Constants.CAT_TRANSLATION_14B_MODEL_ID, selection.selectedId(ModelType.LLM))
    }

    @Test
    fun `a deliverable outside the fit budget can't be selected`() = runTest {
        assertFalse(selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_14B_MODEL_ID, ModelStatus.READY, oneGb))
        assertFalse(selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_14B_MODEL_ID, ModelStatus.AVAILABLE, oneGb))

        assertNull(selection.pendingId(ModelType.LLM))
        assertEquals(LlmModelCatalog.DEFAULT.id, selection.selectedId(ModelType.LLM))
    }

    @Test
    fun `a model from another slot or outside the registry can't be selected`() = runTest {
        assertFalse(selection.pick(ModelType.OCR, Constants.BUBBLE_DETECTION_MODEL_ID, ModelStatus.READY, eightGb))
        assertFalse(selection.pick(ModelType.LLM, "retired-model", ModelStatus.AVAILABLE, eightGb))

        assertNull(selection.pendingId(ModelType.OCR))
        assertNull(selection.pendingId(ModelType.LLM))
    }

    @Test
    fun `pending choices are kept per slot`() = runTest {
        selection.delete(Constants.MANGA_OCR_MODEL_ID)
        selection.pick(ModelType.OCR, Constants.MANGA_OCR_MODEL_ID, ModelStatus.AVAILABLE, eightGb)
        selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_MODEL_ID, ModelStatus.AVAILABLE, eightGb)

        assertEquals(Constants.MANGA_OCR_MODEL_ID, selection.pendingId(ModelType.OCR))
        assertNull(selection.pendingId(ModelType.DETECTION))
        assertEquals(Constants.CAT_TRANSLATION_MODEL_ID, selection.pendingId(ModelType.LLM))

        selection.commitPending(mapOf(Constants.MANGA_OCR_MODEL_ID to ModelStatus.READY))

        assertNull(selection.pendingId(ModelType.OCR))
        assertEquals(Constants.CAT_TRANSLATION_MODEL_ID, selection.pendingId(ModelType.LLM))
        assertEquals(Constants.MANGA_OCR_MODEL_ID, prefs.values[Constants.PREF_OCR_MODEL])
    }

    @Test
    fun `cancelling a pending choice leaves the stored selection`() = runTest {
        selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_MODEL_ID, ModelStatus.DOWNLOADING, eightGb)

        selection.cancel(Constants.CAT_TRANSLATION_MODEL_ID)
        selection.commitPending(mapOf(Constants.CAT_TRANSLATION_MODEL_ID to ModelStatus.READY))

        assertNull(selection.pendingId(ModelType.LLM))
        assertEquals(LlmModelCatalog.DEFAULT.id, selection.selectedId(ModelType.LLM))
    }

    @Test
    fun `re-picking the stored selection drops a pending switch`() = runTest {
        selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_MODEL_ID, ModelStatus.DOWNLOADING, eightGb)

        assertTrue(selection.pick(ModelType.LLM, LlmModelCatalog.DEFAULT.id, ModelStatus.AVAILABLE, eightGb))

        assertNull(selection.pendingId(ModelType.LLM))
        assertEquals(LlmModelCatalog.DEFAULT.id, selection.selectedId(ModelType.LLM))
    }

    @Test
    fun `deleting the selected translation model clears its slot and unloads it`() = runTest {
        selection.delete(LlmModelCatalog.DEFAULT.id)

        assertNull(selection.selectedId(ModelType.LLM))
        Mockito.verify(llama).close()
        assertTrue(deleted(LlmModelCatalog.DEFAULT.id))
    }

    @Test
    fun `deleting a selected reading model clears only its slot and releases it`() = runTest {
        selection.delete(Constants.MANGA_OCR_MODEL_ID)

        assertNull(selection.selectedId(ModelType.OCR))
        assertEquals(Constants.BUBBLE_DETECTION_MODEL_ID, selection.selectedId(ModelType.DETECTION))
        assertEquals(LlmModelCatalog.DEFAULT.id, selection.selectedId(ModelType.LLM))
        Mockito.verify(ocr).release()
        Mockito.verifyNoInteractions(detector, llama)
        assertTrue(deleted(Constants.MANGA_OCR_MODEL_ID))
    }

    @Test
    fun `deleting a model that isn't selected leaves every slot and unloads nothing`() = runTest {
        selection.delete(Constants.CAT_TRANSLATION_MODEL_ID)

        assertEquals(LlmModelCatalog.DEFAULT.id, selection.selectedId(ModelType.LLM))
        Mockito.verifyNoInteractions(detector, ocr, llama)
        assertTrue(deleted(Constants.CAT_TRANSLATION_MODEL_ID))
    }

    @Test
    fun `a cleared slot stays empty until a model is picked`() = runTest {
        selection.delete(LlmModelCatalog.DEFAULT.id)

        assertNull(newSelection().selectedId(ModelType.LLM))

        selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_MODEL_ID, ModelStatus.READY, eightGb)

        assertEquals(Constants.CAT_TRANSLATION_MODEL_ID, selection.selectedId(ModelType.LLM))
    }

    @Test
    fun `slots are ready only when every selected model is READY`() = runTest {
        val statuses = listOf(Constants.BUBBLE_DETECTION_MODEL_ID, Constants.MANGA_OCR_MODEL_ID, LlmModelCatalog.DEFAULT.id)
            .associateWith { ModelStatus.READY }

        assertTrue(selection.ready(statuses))
        assertFalse(selection.ready(statuses + (Constants.MANGA_OCR_MODEL_ID to ModelStatus.DOWNLOADING)))

        selection.delete(Constants.MANGA_OCR_MODEL_ID)

        assertFalse(selection.ready(statuses))
    }

    @Test
    fun `the download size counts each slot's chosen model that isn't downloaded`() = runTest {
        selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_MODEL_ID, ModelStatus.AVAILABLE, eightGb)
        val size = { id: String -> ModelType.entries.flatMap(selection::deliverables).single { it.id == id }.sizeBytes }

        assertEquals(
            listOf(Constants.BUBBLE_DETECTION_MODEL_ID, Constants.MANGA_OCR_MODEL_ID, Constants.CAT_TRANSLATION_MODEL_ID),
            ModelType.entries.map(selection::chosenId)
        )
        assertEquals(
            size(Constants.MANGA_OCR_MODEL_ID) + size(Constants.CAT_TRANSLATION_MODEL_ID),
            selection.downloadBytes(mapOf(Constants.BUBBLE_DETECTION_MODEL_ID to ModelStatus.READY))
        )
    }

    @Test
    fun `a cleared slot is set up with its curated default`() = runTest {
        selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_MODEL_ID, ModelStatus.READY, eightGb)
        selection.delete(Constants.CAT_TRANSLATION_MODEL_ID)

        assertNull(selection.selectedId(ModelType.LLM))
        assertEquals(LlmModelCatalog.DEFAULT.id, selection.chosenId(ModelType.LLM))
        assertEquals(
            LlmModelCatalog.DEFAULT.sizeBytes,
            selection.downloadBytes(mapOf(Constants.BUBBLE_DETECTION_MODEL_ID to ModelStatus.READY, Constants.MANGA_OCR_MODEL_ID to ModelStatus.READY))
        )
    }

    private fun deleted(id: String): Boolean =
        Mockito.mockingDetails(modelManager).invocations.any { it.method.name == "deleteModel" && it.arguments[0] == id }

    @Test
    fun `every slot lists its curated deliverables with a size and licence`() {
        assertEquals(LlmModelCatalog.ALL.map { it.id }, selection.deliverables(ModelType.LLM).map { it.id })
        for (type in listOf(ModelType.DETECTION, ModelType.OCR)) {
            assertEquals(
                ModelManager.REGISTRY.filter { it.type == type }.map { it.id },
                selection.deliverables(type).map { it.id }
            )
        }
        for (deliverable in ModelType.entries.flatMap { selection.deliverables(it) }) {
            assertTrue(deliverable.name, deliverable.licence.isNotBlank())
            assertTrue(deliverable.name, deliverable.sizeBytes > 0)
        }
    }

    @Test
    fun `a deliverable reports whether it is experimental`() {
        val llm = selection.deliverables(ModelType.LLM)

        assertTrue(llm.single { it.id == Constants.QWEN35_2B_MODEL_ID }.experimental)
        assertFalse(llm.single { it.id == LlmModelCatalog.DEFAULT.id }.experimental)
        assertTrue(selection.deliverables(ModelType.DETECTION).single { it.id == Constants.COMIC_TEXT_DETECTOR_MODEL_ID }.experimental)
        assertFalse(selection.deliverables(ModelType.DETECTION).single { it.id == Constants.BUBBLE_DETECTION_MODEL_ID }.experimental)
        assertTrue(selection.deliverables(ModelType.OCR).none { it.experimental })
    }

    @Test
    fun `a deliverable's size counts its additional files`() {
        val ocr = selection.deliverables(ModelType.OCR).single { it.id == Constants.MANGA_OCR_MODEL_ID }
        val entity = ModelManager.REGISTRY.single { it.id == Constants.MANGA_OCR_MODEL_ID }

        assertEquals(entity.fileSize + ModelManager.additionalFiles(entity.id).sumOf { it.size }, ocr.sizeBytes)
    }

    @Test
    fun `fit is judged by the translation budget`() {
        assertFalse(ModelSlotSelection.fits(Constants.CAT_TRANSLATION_14B_MODEL_ID, selection.fitBudget(oneGb)))
        assertTrue(ModelSlotSelection.fits(LlmModelCatalog.DEFAULT.id, selection.fitBudget(oneGb)))
        assertTrue(ModelSlotSelection.fits(Constants.MANGA_OCR_MODEL_ID, selection.fitBudget(oneGb)))
    }

    @Test
    fun `an unknown memory size fits only the default translation model`() {
        assertTrue(ModelSlotSelection.fits(Constants.QWEN35_08B_MODEL_ID, selection.fitBudget(eightGb)))
        assertFalse(ModelSlotSelection.fits(Constants.QWEN35_08B_MODEL_ID, selection.fitBudget(0L)))
        assertFalse(ModelSlotSelection.fits(Constants.QWEN35_9B_MODEL_ID, selection.fitBudget(0L)))
        assertTrue(ModelSlotSelection.fits(LlmModelCatalog.DEFAULT.id, selection.fitBudget(0L)))
        assertTrue(ModelSlotSelection.fits(Constants.MANGA_OCR_MODEL_ID, selection.fitBudget(0L)))
    }

    @Test
    fun `the fit budget carries the reader's stored percent and context size`() {
        prefs.values[ResourceLimit.FIT_BUDGET_PERCENT.key] = 45
        prefs.values[ResourceLimit.CONTEXT_TOKENS.key] = 2816

        assertEquals(FitBudget(eightGb, 45, 2816), selection.fitBudget(eightGb))
    }

    @Test
    fun `a limit change that would push the model in use out of the fit budget names it`() = runTest {
        val fourGb = 4L * 1024 * 1024 * 1024
        selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_14B_MODEL_ID, ModelStatus.READY, eightGb)

        assertEquals(
            Constants.CAT_TRANSLATION_14B_MODEL_ID,
            selection.limitBlocker(mapOf(ResourceLimit.FIT_BUDGET_PERCENT to 30), fourGb)?.id
        )
        assertEquals(
            Constants.CAT_TRANSLATION_14B_MODEL_ID,
            selection.limitBlocker(mapOf(ResourceLimit.FIT_BUDGET_PERCENT to 45, ResourceLimit.CONTEXT_TOKENS to 2816), fourGb)?.id
        )
        assertNull(selection.limitBlocker(mapOf(ResourceLimit.FIT_BUDGET_PERCENT to 45, ResourceLimit.CONTEXT_TOKENS to 1536), fourGb))
        assertNull(selection.limitBlocker(mapOf(ResourceLimit.THREADS to 1), fourGb))
    }

    @Test
    fun `a pending choice also blocks a limit change that would push it out`() = runTest {
        val fourGb = 4L * 1024 * 1024 * 1024
        selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_14B_MODEL_ID, ModelStatus.AVAILABLE, eightGb)

        assertEquals(
            Constants.CAT_TRANSLATION_14B_MODEL_ID,
            selection.limitBlocker(mapOf(ResourceLimit.FIT_BUDGET_PERCENT to 30), fourGb)?.id
        )
    }

    @Test
    fun `the default model never blocks a limit change`() {
        assertNull(selection.limitBlocker(mapOf(ResourceLimit.FIT_BUDGET_PERCENT to 30), oneGb))
    }

    @Test
    fun `the reader's stored fit budget gates what can be picked`() = runTest {
        val fourGb = 4L * 1024 * 1024 * 1024
        prefs.values[ResourceLimit.FIT_BUDGET_PERCENT.key] = 30

        assertFalse(selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_14B_MODEL_ID, ModelStatus.READY, fourGb))

        prefs.values[ResourceLimit.FIT_BUDGET_PERCENT.key] = 60
        assertTrue(selection.pick(ModelType.LLM, Constants.CAT_TRANSLATION_14B_MODEL_ID, ModelStatus.READY, fourGb))
    }

    @Test
    fun `the speed label switches exactly at 1_5 GB and 3_5 GB`() {
        val oneAndAHalfGb = 3L * 1024 * 1024 * 1024 / 2
        val threeAndAHalfGb = 7L * 1024 * 1024 * 1024 / 2

        assertEquals(DeliverableSpeed.FAST, deliverableOf(oneAndAHalfGb).speed)
        assertEquals(DeliverableSpeed.MEDIUM, deliverableOf(oneAndAHalfGb + 1).speed)
        assertEquals(DeliverableSpeed.MEDIUM, deliverableOf(threeAndAHalfGb).speed)
        assertEquals(DeliverableSpeed.SLOW, deliverableOf(threeAndAHalfGb + 1).speed)
        assertFalse(deliverableOf(threeAndAHalfGb).slow)
        assertTrue(deliverableOf(threeAndAHalfGb + 1).slow)
    }

    @Test
    fun `Ternary-Bonsai is never labelled Fast, whatever its size`() {
        val llm = selection.deliverables(ModelType.LLM)

        assertEquals(DeliverableSpeed.MEDIUM, llm.single { it.id == Constants.TERNARY_BONSAI_4B_MODEL_ID }.speed)
        assertEquals(DeliverableSpeed.MEDIUM, llm.single { it.id == Constants.TERNARY_BONSAI_8B_MODEL_ID }.speed)
    }

    @Test
    fun `a deliverable's licence carries the credit its maker asks for`() {
        val llm = selection.deliverables(ModelType.LLM)

        assertEquals(
            "Apache-2.0 (Created using Bonsai by Prism ML)",
            llm.single { it.id == Constants.TERNARY_BONSAI_4B_MODEL_ID }.licence
        )
        assertEquals("Apache-2.0", llm.single { it.id == LlmModelCatalog.DEFAULT.id }.licence)
    }

    @Test
    fun `only a Slow deliverable that fits needs the warning`() {
        val id = LlmModelCatalog.DEFAULT.id
        val fast = SlotDeliverable(id, "Fits and fast", oneGb, "MIT")
        val slow = SlotDeliverable(id, "Fits and slow", 4L * 1024 * 1024 * 1024, "MIT")

        assertTrue(ModelSlotSelection.fits(id, selection.fitBudget(eightGb)))
        assertTrue(ModelSlotSelection.needsSlowWarning(slow, selection.fitBudget(eightGb)))
        assertFalse(ModelSlotSelection.needsSlowWarning(fast, selection.fitBudget(eightGb)))
    }

    @Test
    fun `a Slow deliverable that does not fit never asks`() {
        // No curated deliverable is Slow yet, so the id of one outside the budget carries a Slow size here.
        val tooBig = SlotDeliverable(Constants.CAT_TRANSLATION_14B_MODEL_ID, "Too big", 4L * 1024 * 1024 * 1024, "MIT")

        assertTrue(tooBig.slow)
        assertFalse(ModelSlotSelection.fits(tooBig.id, selection.fitBudget(oneGb)))
        assertFalse(ModelSlotSelection.needsSlowWarning(tooBig, selection.fitBudget(oneGb)))
    }

    private fun deliverableOf(sizeBytes: Long) = SlotDeliverable("size-only", "Size only", sizeBytes, "MIT")
}

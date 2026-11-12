package com.yomu.app.ui.home

import com.yomu.app.db.entities.ModelStatus
import com.yomu.core.Constants
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingStateTest {
    private val detector = "selected_detector"
    private val reader = "selected_reader"
    private val translator = Constants.QWEN25_15B_MODEL_ID

    private fun readiness(models: Map<String, ModelStatus>, permission: Boolean) =
        resolveReadiness(models, detector, reader, translator, permission)

    @Test
    fun `reading is on only when ready and service is running`() {
        assertEquals(ReadingStatus.NotReady, resolveReadingStatus(false, false))
        assertEquals(ReadingStatus.NotReady, resolveReadingStatus(false, true))
        assertEquals(ReadingStatus.Off, resolveReadingStatus(true, false))
        assertEquals(ReadingStatus.On, resolveReadingStatus(true, true))
    }

    @Test
    fun `readiness requires the selected detector, reader, translator and overlay permission`() {
        val ready = mapOf(
            detector to ModelStatus.READY,
            reader to ModelStatus.READY,
            translator to ModelStatus.READY
        )
        assertEquals(Readiness.Ready, readiness(ready, true))
        assertEquals(Readiness.NeedsPermission, readiness(ready, false))
        assertEquals(Readiness.NeedsModels, readiness(emptyMap(), true))
        assertEquals(Readiness.NeedsBoth, readiness(emptyMap(), false))
        assertEquals(Readiness.NeedsModels, readiness(ready - reader, true))
        ModelStatus.entries.filter { it != ModelStatus.READY }.forEach { status ->
            val incomplete = ready + (detector to status)
            assertEquals(Readiness.NeedsModels, readiness(incomplete, true))
            assertEquals(Readiness.NeedsBoth, readiness(incomplete, false))
        }
    }

    @Test
    fun `a network model needs no translation model downloaded`() {
        val readingOnly = mapOf(detector to ModelStatus.READY, reader to ModelStatus.READY)

        assertEquals(Readiness.NeedsModels, resolveReadiness(readingOnly, detector, reader, translator, true))
        assertEquals(Readiness.Ready, resolveReadiness(readingOnly, detector, reader, translator, true, translationOnDevice = false))
        assertEquals(Readiness.NeedsModels, resolveReadiness(mapOf(detector to ModelStatus.READY), detector, reader, translator, true, translationOnDevice = false))
    }

    @Test
    fun `unselected models in any status do not affect readiness`() {
        val ready = mapOf(
            detector to ModelStatus.READY,
            reader to ModelStatus.READY,
            translator to ModelStatus.READY
        )
        val unselected = listOf(
            Constants.BUBBLE_DETECTION_MODEL_ID,
            Constants.MANGA_OCR_MODEL_ID,
            Constants.CAT_TRANSLATION_MODEL_ID
        )
        ModelStatus.entries.forEach { status ->
            val others = unselected.associateWith { status }
            assertEquals(Readiness.Ready, readiness(ready + others, true))
            assertEquals(Readiness.NeedsModels, readiness(ready - detector + others, true))
        }
    }

    @Test
    fun `a slot cleared by deleting its model needs models`() {
        val ready = mapOf(
            detector to ModelStatus.READY,
            reader to ModelStatus.READY,
            translator to ModelStatus.READY
        )
        assertEquals(Readiness.NeedsModels, resolveReadiness(ready, detector, reader, null, true))
        assertEquals(Readiness.NeedsModels, resolveReadiness(ready, null, reader, translator, true))
        assertEquals(Readiness.NeedsBoth, resolveReadiness(ready, detector, null, translator, false))
    }

    @Test
    fun `a missing translator means models are needed`() {
        val readers = mapOf(detector to ModelStatus.READY, reader to ModelStatus.READY)
        assertEquals(Readiness.NeedsModels, readiness(readers, true))
        assertEquals(Readiness.NeedsModels, readiness(readers + (translator to ModelStatus.DOWNLOADING), true))
    }
}

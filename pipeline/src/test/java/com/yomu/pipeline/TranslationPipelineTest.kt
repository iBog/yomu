package com.yomu.pipeline

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.yomu.core.GlossaryEntry
import com.yomu.core.PageTranslation
import com.yomu.core.SourceLanguage
import com.yomu.core.TargetLanguage
import com.yomu.pipeline.bubble.Bubble
import com.yomu.pipeline.bubble.BubbleDetector
import com.yomu.pipeline.context.ContextAssembler
import com.yomu.pipeline.ocr.LineOcrEngine
import com.yomu.pipeline.ocr.OcrEngine
import com.yomu.pipeline.ocr.OcrResult
import com.yomu.pipeline.translation.FakeTranslationSlot
import com.yomu.pipeline.translation.TranslationEngine
import com.yomu.pipeline.typesetting.Typesetter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TranslationPipelineTest {

    private val statusBar = RectF(0f, 0f, 400f, 40f)
    private val clock = Bubble(1, RectF(10f, 5f, 60f, 35f), 0.9f)
    private val speech = Bubble(2, RectF(100f, 100f, 180f, 300f), 0.9f)
    private val sfx = Bubble(3, RectF(220f, 100f, 280f, 300f), 0.9f)
    private val caption = Bubble(4, RectF(300f, 100f, 370f, 300f), 0.9f)
    private val terse = Bubble(5, RectF(10f, 320f, 100f, 390f), 0.9f)

    // The fake OCR sees only a crop, so each box above has its own width to be told apart by.
    private val readsByCropWidth = mapOf(50 to "12:00", 80 to "こんにちは", 60 to "!?", 70 to "よくラるって", 90 to "御意")
    // Emulator scores from #366: a garbled caption, and a clean two-kanji line.
    private val confidenceByCropWidth = mapOf(70 to 0.738f, 90 to 0.898f)
    private val reads = mutableListOf<String>()
    private val slot = FakeTranslationSlot(PageTranslation(mapOf(2 to "Hello", 3 to "What?"), "", 1L))

    private fun pipeline(vararg boxes: Bubble) = TranslationPipeline(
        detector(boxes.toList()),
        ocr(),
        ContextAssembler(),
        TranslationEngine { slot },
        Typesetter()
    )

    private val lineReads = mutableListOf<String>()

    // Reads the same boxes as another script would: the clock and a two-line Latin bubble.
    private fun lineReader(): LineOcrEngine = Mockito.mock(LineOcrEngine::class.java) { invocation ->
        when (invocation.method.name) {
            "isModelLoaded" -> true
            "extractText" -> {
                val text = mapOf(50 to "12:00", 80 to "Where are you going?").getValue(invocation.getArgument<Bitmap>(0).width)
                lineReads += text
                OcrResult(text, 0.99f, floatArrayOf(), minConfidence = LineOcrEngine.MIN_CONFIDENCE)
            }
            else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    }

    private fun linePipeline(vararg boxes: Bubble) = TranslationPipeline(
        detector(boxes.toList()),
        ocr(),
        ContextAssembler(),
        TranslationEngine { slot },
        Typesetter(),
        lineReader()
    )

    private fun detector(boxes: List<Bubble>): BubbleDetector = Mockito.mock(BubbleDetector::class.java) { invocation ->
        when (invocation.method.name) {
            "isModelLoaded" -> true
            "detect" -> boxes
            else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    }

    private fun ocr(): OcrEngine = Mockito.mock(OcrEngine::class.java) { invocation ->
        when (invocation.method.name) {
            "isModelLoaded" -> true
            "extractText" -> {
                val text = readsByCropWidth.getValue(invocation.getArgument<Bitmap>(0).width)
                reads += text
                OcrResult(text, confidenceByCropWidth[invocation.getArgument<Bitmap>(0).width] ?: 1f, floatArrayOf())
            }
            else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
    }

    private fun page(): Bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
    }

    @Test
    fun `a box centered in an ignored region never reaches OCR`() = runTest {
        pipeline(clock, speech).processPage(page(), ignoredRegions = listOf(statusBar), targetLanguage = TargetLanguage.ENGLISH)

        assertEquals(listOf("こんにちは"), reads)
    }

    @Test
    fun `a read with no kana or kanji never reaches onOcrComplete, the slot or onBubble`() = runTest {
        val previewed = mutableListOf<Int>()
        val drawn = mutableListOf<Int>()

        pipeline(speech, sfx).processPage(
            page(),
            onOcrComplete = { bubbleId, _, _ -> previewed += bubbleId },
            onBubble = { drawn += it.bubbleId },
            targetLanguage = TargetLanguage.ENGLISH
        )

        assertEquals(listOf(2), previewed)
        assertEquals(listOf(listOf(2 to "こんにちは")), slot.pagePairs())
        assertEquals(listOf(2), drawn)
    }

    @Test
    fun `an unsure read never reaches onOcrComplete, the slot or onBubble, but stays in the result`() = runTest {
        val previewed = mutableListOf<Int>()
        val drawn = mutableListOf<Int>()

        val result = pipeline(speech, caption).processPage(
            page(),
            onOcrComplete = { bubbleId, _, _ -> previewed += bubbleId },
            onBubble = { drawn += it.bubbleId },
            targetLanguage = TargetLanguage.ENGLISH
        )

        assertEquals(listOf(2), previewed)
        assertEquals(listOf(listOf(2 to "こんにちは")), slot.pagePairs())
        assertEquals(listOf(2), drawn)
        assertEquals(
            mapOf(2 to ("こんにちは" to 1f), 4 to ("よくラるって" to 0.738f)),
            checkNotNull(result).reads.mapValues { (_, read) -> read.text to read.confidence }
        )
    }

    @Test
    fun `a clean read the decoder is less sure of is still translated`() = runTest {
        pipeline(terse).processPage(page(), targetLanguage = TargetLanguage.ENGLISH)

        assertEquals(listOf(listOf(5 to "御意")), slot.pagePairs())
    }

    @Test
    fun `the page is translated into the language it was asked for`() = runTest {
        val result = pipeline(speech).processPage(page(), targetLanguage = TargetLanguage.FRENCH)

        assertEquals(TargetLanguage.FRENCH, slot.pages.single().targetLanguage)
        assertEquals(TargetLanguage.FRENCH, result?.translationResult?.targetLanguage)
    }

    @Test
    fun `another source language is read by the line reader and keeps reads with letters`() = runTest {
        val result = linePipeline(clock, speech).processPage(
            page(),
            targetLanguage = TargetLanguage.RUSSIAN,
            sourceLanguage = SourceLanguage.LATIN_SCRIPT
        )

        assertTrue(reads.isEmpty())
        assertEquals(listOf("12:00", "Where are you going?"), lineReads)
        assertEquals(listOf(listOf(2 to "Where are you going?")), slot.pagePairs())
        assertEquals(SourceLanguage.LATIN_SCRIPT, slot.pages.single().sourceLanguage)
        assertEquals(mapOf(1 to "12:00"), checkNotNull(result).droppedReads)
    }

    @Test
    fun `a line read below its own confidence floor is skipped`() {
        assertTrue(OcrResult("x", 0.69f, floatArrayOf(), minConfidence = LineOcrEngine.MIN_CONFIDENCE).unsure)
        assertTrue(!OcrResult("x", 0.71f, floatArrayOf(), minConfidence = LineOcrEngine.MIN_CONFIDENCE).unsure)
    }

    @Test
    fun `the page reaches the slot with the glossary applied`() = runTest {
        pipeline(speech).processPage(page(), targetLanguage = TargetLanguage.ENGLISH, glossary = listOf(GlossaryEntry("こんにちは", "Hello")))

        assertEquals(listOf(listOf(2 to "Hello")), slot.pagePairs())
    }

    @Test
    fun `the last finished page's whole time is kept for the readout`() = runTest {
        val pipeline = pipeline(speech)
        assertNull(pipeline.lastPageTotalMs)

        val result = pipeline.processPage(page(), targetLanguage = TargetLanguage.ENGLISH)

        assertEquals(result?.totalTimeMs, pipeline.lastPageTotalMs)
    }

    @Test
    fun `a page that fails keeps the time of the last one that finished`() = runTest {
        val pipeline = pipeline(speech)
        val finished = pipeline.processPage(page(), targetLanguage = TargetLanguage.ENGLISH)

        pipeline.processPage(page(), ignoredRegions = listOf(RectF(0f, 0f, 400f, 400f)), targetLanguage = TargetLanguage.ENGLISH)

        assertEquals(finished?.totalTimeMs, pipeline.lastPageTotalMs)
    }

    @Test
    fun `a page with no bubbles left reports its error without calling the slot`() = runTest {
        val errors = mutableListOf<Pair<TranslationPipeline.Stage, String>>()
        val callback = object : TranslationPipeline.PipelineCallback {
            override fun onStageProgress(stage: TranslationPipeline.Stage, progress: Float) = Unit
            override fun onError(stage: TranslationPipeline.Stage, message: String) {
                errors += stage to message
            }
            override fun onComplete(result: PipelineResult) = Unit
        }

        val result = pipeline(clock).processPage(page(), callback, ignoredRegions = listOf(statusBar), targetLanguage = TargetLanguage.ENGLISH)

        assertNull(result)
        assertEquals(listOf(TranslationPipeline.Stage.BUBBLE_DETECTION to "No bubbles detected on the page"), errors)
        assertTrue(slot.pages.isEmpty())
    }
}

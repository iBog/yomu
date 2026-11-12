package com.yomu.app.service

import com.yomu.app.overlay.OverlayBounds
import com.yomu.core.GenerationParams
import com.yomu.core.GlossaryEntry
import com.yomu.core.RuntimeLimits
import com.yomu.core.TargetLanguage
import com.yomu.core.TranslationOutcome
import com.yomu.pipeline.PipelineResult
import com.yomu.pipeline.translation.TranslatedBubble
import com.yomu.pipeline.translation.TranslationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageRecallTest {

    private val width = 256
    private val height = 256
    private val statusBar = OverlayBounds(0f, 0f, width.toFloat(), 24f)
    private val button = OverlayBounds(216f, 216f, 248f, 248f)
    private val movedButton = OverlayBounds(8f, 216f, 40f, 248f)
    private val settings = PageSettings(
        translationModel = "qwen2.5-1.5b",
        detectionModel = "bubble-detector",
        ocrModel = "manga-ocr",
        generation = GenerationParams(),
        runtime = RuntimeLimits(threads = 4, contextTokens = 2048),
        detectionThreshold = 0.25f,
        fontScale = 1.0f,
        targetLanguage = TargetLanguage.ENGLISH,
        spoken = null,
        glossary = emptyList()
    )

    /** Encodes a grayscale level as an opaque ARGB pixel for synthetic captures. */
    private fun gray(level: Int): Int = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

    /** Page [number] is textured art with a bright square at its own place, clear of the status bar and button. */
    private fun page(number: Int, change: (x: Int, y: Int, level: Int) -> Int = { _, _, level -> level }): IntArray =
        IntArray(width * height) { index ->
            val x = index % width
            val y = index / width
            val inMark = x / 32 == number % 8 && y / 32 == 1 + number / 8
            change(x, y, if (inMark) 255 else (x * 7 + y * 13) % 200)
        }.map(::gray).toIntArray()

    /** Checks pixel membership in a region with exclusive right and bottom edges. */
    private fun inside(region: OverlayBounds, x: Int, y: Int) =
        x >= region.left && x < region.right && y >= region.top && y < region.bottom

    /** Fingerprints a synthetic capture, ignoring the default status bar and button unless overridden. */
    private fun fingerprint(pixels: IntArray, ignored: List<OverlayBounds> = listOf(statusBar, button)) =
        PageFingerprint.of(pixels, width, height, ignored)

    /** Builds a page result without rendered bubbles to isolate recall behavior from typesetting. */
    private fun result(label: String, bubbles: List<TranslatedBubble> = listOf(answered(0, label))) = PipelineResult(
        typesetBubbles = emptyList(),
        translationResult = TranslationResult(bubbles, label, 0L),
        pageWidth = width,
        pageHeight = height,
        totalTimeMs = 0L
    )

    /** Creates a bubble whose translation was answered by the model. */
    private fun answered(id: Int, text: String) = TranslatedBubble(id, "こんにちは", text, answered = true)

    /** Creates a bubble that retains its source text because the model did not answer it. */
    private fun unanswered(id: Int) = TranslatedBubble(id, "やっちょっと…!", "やっちょっと…!", answered = false)

    /** Verifies an identical capture and settings retrieve the stored page result. */
    @Test
    fun `a page captured again with the same settings is recalled`() {
        val recall = PageRecall()
        recall.remember(fingerprint(page(0)), settings, result("page 0"))

        assertEquals(result("page 0"), recall.recall(fingerprint(page(0)), settings))
    }

    /** Verifies a two-level brightness shift stays within the fingerprint tolerance. */
    @Test
    fun `a page slightly brighter everywhere is still recalled`() {
        val recall = PageRecall()
        recall.remember(fingerprint(page(0)), settings, result("page 0"))

        val brighter = page(0) { _, _, level -> (level + 2).coerceAtMost(255) }

        assertEquals(result("page 0"), recall.recall(fingerprint(brighter), settings))
    }

    /** Verifies a localized contrast change prevents reuse of the earlier page. */
    @Test
    fun `a page with one bubble changed is not recalled`() {
        val recall = PageRecall()
        recall.remember(fingerprint(page(0)), settings, result("page 0"))

        val changedBubble = page(0) { x, y, level -> if (x in 96 until 128 && y in 160 until 176) 255 - level else level }

        assertNull(recall.recall(fingerprint(changedBubble), settings))
    }

    /** Verifies moving the synthetic page marker produces a recall miss. */
    @Test
    fun `a different page is not recalled`() {
        val recall = PageRecall()
        recall.remember(fingerprint(page(0)), settings, result("page 0"))

        assertNull(recall.recall(fingerprint(page(1)), settings))
    }

    /** Verifies overlay changes are ignored only when their regions are excluded from fingerprints. */
    @Test
    fun `the status bar and the floating button do not decide a match`() {
        val recall = PageRecall()
        recall.remember(fingerprint(page(0)), settings, result("page 0"))

        val newClockAndButton = page(0) { x, y, level ->
            if (inside(statusBar, x, y) || inside(button, x, y)) 255 - level else level
        }

        assertEquals(result("page 0"), recall.recall(fingerprint(newClockAndButton), settings))

        val nothingIgnored = PageRecall()
        nothingIgnored.remember(fingerprint(page(0), ignored = emptyList()), settings, result("page 0"))
        assertNull(nothingIgnored.recall(fingerprint(newClockAndButton, ignored = emptyList()), settings))
    }

    /** Verifies matching excludes both the old and new floating button positions. */
    @Test
    fun `a floating button moved between captures does not decide a match`() {
        val recall = PageRecall()
        val buttonHere = page(0) { x, y, level -> if (inside(button, x, y)) 255 else level }
        val buttonMoved = page(0) { x, y, level -> if (inside(movedButton, x, y)) 255 else level }
        recall.remember(fingerprint(buttonHere, listOf(statusBar, button)), settings, result("page 0"))

        assertEquals(result("page 0"), recall.recall(fingerprint(buttonMoved, listOf(statusBar, movedButton)), settings))
    }

    /** Verifies each recall-key setting invalidates reuse while the original settings still match. */
    @Test
    fun `changing any setting that shapes the page makes it a fresh translation`() {
        val recall = PageRecall()
        recall.remember(fingerprint(page(0)), settings, result("page 0"))

        val changed = listOf(
            settings.copy(translationModel = "cat-translate-1.4b"),
            settings.copy(detectionModel = "other-detector"),
            settings.copy(ocrModel = "other-ocr"),
            settings.copy(generation = GenerationParams(temperature = 0.7f)),
            settings.copy(runtime = RuntimeLimits(threads = 2, contextTokens = 2048)),
            settings.copy(runtime = RuntimeLimits(threads = 4, contextTokens = 4096)),
            settings.copy(detectionThreshold = 0.3f),
            settings.copy(fontScale = 1.2f),
            settings.copy(targetLanguage = TargetLanguage.BRAZILIAN_PORTUGUESE),
            settings.copy(glossary = listOf(GlossaryEntry("ルフィ", "Luffy")))
        )

        for (setting in changed) assertNull("$setting", recall.recall(fingerprint(page(0)), setting))
        assertEquals(result("page 0"), recall.recall(fingerprint(page(0)), settings))
    }

    /** Verifies a recall refreshes recency and the thirty-first insertion evicts the oldest unused page. */
    @Test
    fun `the least recently used page is dropped past thirty pages`() {
        val recall = PageRecall()
        for (number in 0 until 30) recall.remember(fingerprint(page(number)), settings, result("page $number"))

        recall.recall(fingerprint(page(0)), settings)
        recall.remember(fingerprint(page(30)), settings, result("page 30"))

        assertNull(recall.recall(fingerprint(page(1)), settings))
        for (number in listOf(0) + (2..30)) {
            assertEquals(result("page $number"), recall.recall(fingerprint(page(number)), settings))
        }
    }

    /** Verifies a successful page remains reusable when at least one bubble was answered. */
    @Test
    fun `a page with a few bubbles the model could not answer is still recalled`() {
        val recall = PageRecall()
        val partial = result("page 0", listOf(answered(0, "Hello"), unanswered(1)))
        recall.remember(fingerprint(page(0)), settings, partial)

        assertEquals(partial, recall.recall(fingerprint(page(0)), settings))
    }

    /** Verifies timed-out and wholly unanswered pages remain eligible for a fresh translation. */
    @Test
    fun `a page the model did not finish or answered nowhere is not recalled, so capturing it again retries it`() {
        val recall = PageRecall()
        val timedOut = result("page 0").copy(
            translationResult = TranslationResult(listOf(answered(0, "Hello")), "[0] Hello", 0L, outcome = TranslationOutcome.TIMEOUT)
        )
        recall.remember(fingerprint(page(0)), settings, timedOut)
        recall.remember(fingerprint(page(1)), settings, result("page 1", listOf(unanswered(0), unanswered(1))))

        assertNull(recall.recall(fingerprint(page(0)), settings))
        assertNull(recall.recall(fingerprint(page(1)), settings))
    }

    /** Verifies clearing the session removes all previously remembered pages. */
    @Test
    fun `remembering a page again replaces its earlier translation`() {
        val recall = PageRecall()
        recall.remember(fingerprint(page(0)), settings, result("first"))
        recall.remember(fingerprint(page(0)), settings, result("second"))

        assertEquals(result("second"), recall.recall(fingerprint(page(0)), settings))
    }

    /** A retranslation that fails keeps the earlier translation, so the next capture still recalls it (#402). */
    @Test
    fun `a failed second translation of a page leaves the first one recalled`() {
        val recall = PageRecall()
        recall.remember(fingerprint(page(0)), settings, result("first"))
        val timedOut = result("second").copy(
            translationResult = TranslationResult(listOf(answered(0, "second")), "[0] second", 0L, outcome = TranslationOutcome.TIMEOUT)
        )

        recall.remember(fingerprint(page(0)), settings, timedOut)
        recall.remember(fingerprint(page(0)), settings, result("unanswered", listOf(unanswered(0))))

        assertEquals(result("first"), recall.recall(fingerprint(page(0)), settings))
    }

    @Test
    fun `while recall is off a remembered page is not recalled`() {
        val recall = PageRecall()
        recall.enabled = false

        recall.remember(fingerprint(page(0)), settings, result("page 0"))

        assertNull(recall.recall(fingerprint(page(0)), settings))
        recall.enabled = true
        assertNull(recall.recall(fingerprint(page(0)), settings))
    }

    @Test
    fun `turning recall off forgets the pages remembered before it`() {
        val recall = PageRecall()
        recall.remember(fingerprint(page(0)), settings, result("page 0"))

        recall.enabled = false
        recall.enabled = true

        assertNull(recall.recall(fingerprint(page(0)), settings))
        recall.remember(fingerprint(page(0)), settings, result("page 0 again"))
        assertEquals(result("page 0 again"), recall.recall(fingerprint(page(0)), settings))
    }

    @Test
    fun `a page begun before recall was turned off and on again is not remembered`() {
        val recall = PageRecall()
        val begunAt = recall.timesTurnedOff

        recall.enabled = false
        recall.enabled = true
        recall.remember(fingerprint(page(0)), settings, result("page 0"), begunAt)

        assertNull(recall.recall(fingerprint(page(0)), settings))
    }

    @Test
    fun `clearing forgets every page`() {
        val recall = PageRecall()
        recall.remember(fingerprint(page(0)), settings, result("page 0"))
        recall.remember(fingerprint(page(1)), settings, result("page 1"))

        recall.clear()

        assertNull(recall.recall(fingerprint(page(0)), settings))
        assertNull(recall.recall(fingerprint(page(1)), settings))
    }
}

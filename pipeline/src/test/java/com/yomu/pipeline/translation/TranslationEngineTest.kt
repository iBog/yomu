package com.yomu.pipeline.translation

import android.graphics.RectF
import com.yomu.core.ERROR_ENGLISH_ONLY
import com.yomu.core.GlossaryEntry
import com.yomu.core.PageTranslation
import com.yomu.core.TargetLanguage
import com.yomu.core.TranslationOutcome
import com.yomu.core.TranslationStatus
import com.yomu.pipeline.bubble.Bubble
import com.yomu.pipeline.context.ConversationBlock
import com.yomu.pipeline.ocr.OcrResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationEngineTest {

    @Test
    fun translate_appliesTranslationsAndResultMetadata() = runTest {
        val slot = FakeTranslationSlot(
            PageTranslation(mapOf(1 to "Hello"), "raw model output", 55L)
        )

        val result = TranslationEngine { slot }.translate(listOf(block(1 to "こんにちは")))

        assertEquals("Hello", result.translations.single().translatedText)
        assertTrue(result.translations.single().answered)
        assertEquals("raw model output", result.rawResponse)
        assertEquals(55L, result.translationTimeMs)
    }

    @Test
    fun translate_glossaryRenderingReachesTheSourceTextButNotTheOcrText() = runTest {
        val slot = FakeTranslationSlot(PageTranslation(mapOf(1 to "Luffy is here"), "", 1L))

        val result = TranslationEngine { slot }
            .translate(listOf(block(1 to "ルフィはここだ")), glossary = listOf(GlossaryEntry("ルフィ", "Luffy")))

        assertEquals(listOf(listOf(1 to "Luffyはここだ")), slot.pagePairs())
        assertEquals("ルフィはここだ", result.translations.single().originalText)
        assertEquals("Luffyはここだ", result.translations.single().sourceText)
    }

    @Test
    fun translate_emptyGlossaryLeavesEverySourceTextAsOcrRead() = runTest {
        val slot = FakeTranslationSlot(PageTranslation(emptyMap(), "", 1L))

        val result = TranslationEngine { slot }.translate(listOf(block(1 to "ルフィ", 2 to "ゾロ")))

        assertEquals(listOf(listOf(1 to "ルフィ", 2 to "ゾロ")), slot.pagePairs())
        assertEquals(listOf("ルフィ", "ゾロ"), result.translations.map { it.sourceText })
        assertEquals(listOf("ルフィ", "ゾロ"), result.translations.map { it.translatedText })
    }

    @Test
    fun translate_reportsAPageAnsweredPerLineAfterABatchOverflow() = runTest {
        val slot = FakeTranslationSlot(
            PageTranslation(mapOf(1 to "Hello"), "Hello", 1L, batchOverflowFallback = true)
        )

        val result = TranslationEngine { slot }.translate(listOf(block(1 to "こんにちは")))

        assertTrue(result.batchOverflowFallback)
    }

    @Test
    fun translate_missingIdLeavesThatBubbleOnlyUnanswered() = runTest {
        val slot = FakeTranslationSlot(PageTranslation(mapOf(1 to "Hello"), "", 1L))

        val result = TranslationEngine { slot }
            .translate(listOf(block(1 to "こんにちは", 2 to "さようなら")))

        assertEquals(listOf("Hello", "さようなら"), result.translations.map { it.translatedText })
        assertEquals(listOf(true, false), result.translations.map { it.answered })
    }

    @Test
    fun translate_unusableRepliesLeaveTheirBubblesUnanswered() = runTest {
        val slot = FakeTranslationSlot(
            PageTranslation(
                mapOf(1 to "I'm sorry, but I can't help with that.", 2 to "おはよう, everyone", 3 to "Goodbye"),
                "",
                1L
            )
        )

        val result = TranslationEngine { slot }
            .translate(listOf(block(1 to "こんにちは", 2 to "おはよう", 3 to "さようなら")))

        assertEquals(listOf("こんにちは", "おはよう", "Goodbye"), result.translations.map { it.translatedText })
        assertEquals(listOf(false, false, true), result.translations.map { it.answered })
    }

    @Test
    fun translate_timedOutPageLeavesTheBubblesPastTheDeadlineUnanswered() = runTest {
        val slot = FakeTranslationSlot(
            PageTranslation(mapOf(1 to "Hello"), "[1] Hello", 60_000L, TranslationOutcome.TIMEOUT, "deadline")
        )

        val result = TranslationEngine { slot }
            .translate(listOf(block(1 to "こんにちは", 2 to "さようなら", 3 to "またね")))

        assertEquals(listOf(true, false, false), result.translations.map { it.answered })
        assertEquals("2 of 3 bubbles not translated", result.untranslatedNotice())
    }

    /** Verifies a fully answered page produces no untranslated-bubble notice. */
    @Test
    fun untranslatedNotice_isNullWhenEveryBubbleWasAnswered() = runTest {
        val slot = FakeTranslationSlot(PageTranslation(mapOf(1 to "Hello"), "", 1L))

        val result = TranslationEngine { slot }.translate(listOf(block(1 to "こんにちは")))

        assertNull(result.untranslatedNotice())
    }

    /** Verifies slot reports reach the listener in order with the correct source text and answered flag. */
    @Test
    fun translate_forwardsReportedBubblesAsTheyArrive() = runTest {
        val slot = FakeTranslationSlot(
            PageTranslation(mapOf(1 to "Hello", 2 to "Goodbye"), "", 1L),
            reports = listOf(1 to "Hello", 2 to "Goodbye")
        )
        val forwarded = mutableListOf<TranslatedBubble>()

        TranslationEngine { slot }
            .translate(listOf(block(1 to "こんにちは", 2 to "さようなら"))) { forwarded += it }

        assertEquals(
            listOf(
                TranslatedBubble(1, "こんにちは", "Hello", answered = true),
                TranslatedBubble(2, "さようなら", "Goodbye", answered = true)
            ),
            forwarded
        )
    }

    /** Verifies refusal text and replies containing Japanese are filtered before reaching the listener. */
    @Test
    fun translate_neverForwardsAnUnusableReply() = runTest {
        val slot = FakeTranslationSlot(
            PageTranslation(mapOf(3 to "Goodbye"), "", 1L),
            reports = listOf(1 to "I'm sorry, but I can't help with that.", 2 to "おはよう, everyone", 3 to "Goodbye")
        )
        val forwarded = mutableListOf<TranslatedBubble>()

        TranslationEngine { slot }
            .translate(listOf(block(1 to "こんにちは", 2 to "おはよう", 3 to "さようなら"))) { forwarded += it }

        assertEquals(listOf(3 to "Goodbye"), forwarded.map { it.bubbleId to it.translatedText })
    }

    /** Verifies duplicate and unknown IDs are ignored and the first usable report remains the final answer. */
    @Test
    fun translate_forwardsABubbleOnceAndNeverOneOffThePage() = runTest {
        val slot = FakeTranslationSlot(
            PageTranslation(mapOf(1 to "Hello"), "", 1L),
            reports = listOf(1 to "Hello", 99 to "Invented", 1 to "Hi there")
        )
        val forwarded = mutableListOf<TranslatedBubble>()

        val result = TranslationEngine { slot }
            .translate(listOf(block(1 to "こんにちは", 2 to "さようなら"))) { forwarded += it }

        assertEquals(listOf(1 to "Hello"), forwarded.map { it.bubbleId to it.translatedText })
        assertEquals("Hello", result.translations.first().translatedText)
    }

    /** Verifies final-only answers and punctuation are flushed after streamed answers, omitting unanswered bubbles. */
    @Test
    fun translate_forwardsTheUnreportedAnsweredBubblesWhenThePageEnds() = runTest {
        val slot = FakeTranslationSlot(
            PageTranslation(mapOf(1 to "Hello", 3 to "Bye"), "", 1L),
            reports = listOf(3 to "Bye")
        )
        val forwarded = mutableListOf<TranslatedBubble>()

        TranslationEngine { slot }
            .translate(listOf(block(1 to "こんにちは", 3 to "またね", 4 to "さようなら"))) { forwarded += it }

        assertEquals(listOf(3, 1), forwarded.map { it.bubbleId })
    }

    /** Verifies the final answered set equals the listener output even when a streamed answer is absent from the reply map. */
    @Test
    fun translate_finishedPageHoldsExactlyTheForwardedBubbles() = runTest {
        val slot = FakeTranslationSlot(
            PageTranslation(mapOf(1 to "Hello"), "", 1L),
            reports = listOf(2 to "Goodbye")
        )
        val forwarded = mutableListOf<TranslatedBubble>()

        val result = TranslationEngine { slot }
            .translate(listOf(block(1 to "こんにちは", 2 to "さようなら", 3 to "またね"))) { forwarded += it }

        assertEquals(result.translations.filter { it.answered }.toSet(), forwarded.toSet())
        assertEquals(listOf(true, true, false), result.translations.map { it.answered })
    }

    /** Verifies unknown reply IDs are discarded and missing answers retain their source text as unanswered. */
    @Test
    fun translate_idNotOnThePageNeverReachesABubble() = runTest {
        val slot = FakeTranslationSlot(
            PageTranslation(mapOf(1 to "Hello", 99 to "Invented", -1 to "Out of range"), "", 1L)
        )

        val result = TranslationEngine { slot }
            .translate(listOf(block(1 to "こんにちは", 2 to "さようなら")))

        assertEquals(listOf(1, 2), result.translations.map { it.bubbleId })
        assertEquals(listOf("Hello", "さようなら"), result.translations.map { it.translatedText })
        assertEquals(listOf(true, false), result.translations.map { it.answered })
    }

    @Test
    fun translate_rejectsNonTranslationWithoutDiscardingOtherIds() = runTest {
        val slot = FakeTranslationSlot(
            PageTranslation(
                mapOf(1 to "Here's the English translation: Hello", 2 to "Goodbye"),
                "",
                1L
            )
        )

        val result = TranslationEngine { slot }
            .translate(listOf(block(1 to "こんにちは", 2 to "さようなら")))

        assertEquals(listOf("こんにちは", "Goodbye"), result.translations.map { it.translatedText })
    }

    @Test
    fun translate_projectsPanelsInReadingOrderWithoutGeometryOrEmptyOcr() = runTest {
        val slot = FakeTranslationSlot(PageTranslation(emptyMap(), "", 1L))
        val first = block(2 to "二", 1 to "一", 3 to "", readingOrder = listOf(1, 3, 2))
        val second = block(4 to "四")

        TranslationEngine { slot }.translate(listOf(first, second))

        assertEquals(listOf(listOf(1 to "一", 2 to "二"), listOf(4 to "四")), slot.pagePairs())
    }

    @Test
    fun translate_limitsSourceTextAtTheSeam() = runTest {
        val slot = FakeTranslationSlot(PageTranslation(emptyMap(), "", 1L))

        TranslationEngine { slot }.translate(listOf(block(1 to "長".repeat(400))))

        assertEquals(300, slot.pages.single().panels.single().single().sourceText.length)
    }

    @Test
    fun translate_resolvesTheSlotForEveryCall() = runTest {
        val first = FakeTranslationSlot(PageTranslation(mapOf(1 to "First"), "", 1L))
        val second = FakeTranslationSlot(PageTranslation(mapOf(1 to "Second"), "", 1L))
        var current = first
        val engine = TranslationEngine { current }

        assertEquals("First", engine.translate(listOf(block(1 to "源"))).translations.single().translatedText)
        current = second
        assertEquals("Second", engine.translate(listOf(block(1 to "源"))).translations.single().translatedText)
    }

    @Test
    fun translate_emptyPageDoesNotCallTheSlot() = runTest {
        val slot = FakeTranslationSlot(PageTranslation(emptyMap(), "", 1L))

        val result = TranslationEngine { slot }.translate(listOf(block(1 to "")))

        assertTrue(result.translations.isEmpty())
        assertTrue(slot.pages.isEmpty())
    }

    @Test
    fun translate_keepsBubblesWhoseTextIsOnlyPunctuationAroundWords() = runTest {
        val slot = FakeTranslationSlot(PageTranslation(mapOf(1 to "Huh?!"), "", 1L))

        val result = TranslationEngine { slot }.translate(listOf(block(1 to "え?!")))

        assertEquals(listOf(listOf(1 to "え?!")), slot.pagePairs())
        assertEquals(listOf("Huh?!"), result.translations.map { it.translatedText })
    }

    @Test
    fun endSessionAndCloseUseTheirSingleLifecycleHooks() {
        val slot = FakeTranslationSlot(PageTranslation(emptyMap(), "", 0L))
        var closeCalls = 0
        val engine = TranslationEngine({ slot }) { closeCalls++ }

        engine.endSession()
        engine.close()

        assertEquals(1, slot.endSessionCalls)
        assertEquals(1, closeCalls)
    }

    @Test
    fun looksLikeNonTranslation_flagsRefusalsEchoesAndLoops() {
        assertTrue(looksLikeNonTranslation("I'm sorry, but I can't help with that."))
        assertTrue(looksLikeNonTranslation("I'm unable to translate this."))
        assertTrue(looksLikeNonTranslation("Translate the following Japanese text into English. foo"))
        assertTrue(looksLikeNonTranslation("As an AI language model, I cannot do this."))
        assertTrue(looksLikeNonTranslation("I'm sorry I'm sorry I'm sorry I'm sorry"))
    }

    @Test
    fun looksLikeNonTranslation_flagsClarificationRequestsAndExplanations() {
        assertTrue(looksLikeNonTranslation("Please provide the Japanese text you want translated."))
        assertTrue(looksLikeNonTranslation("Could you please provide the target Japanese manga text?"))
        assertTrue(looksLikeNonTranslation("Provide me with the complete manga text to translate."))
        assertTrue(looksLikeNonTranslation("It seems the text is missing. Please provide the Japanese manga text."))
        assertTrue(looksLikeNonTranslation("The English translation of the given Japanese text is: Hello"))
        assertTrue(looksLikeNonTranslation("The Japanese text \"こんにちは\" translates to \"Hello\" in English."))
    }

    @Test
    fun deadTranslationReason_acceptsAPlainTranslation() {
        assertEquals(null, deadTranslationReason("Good morning!"))
    }

    @Test
    fun deadTranslationReason_acceptsKatakanaPunctuationInAnEnglishLine() {
        assertEquals(null, deadTranslationReason("Wait・・・"))
        assertEquals(null, deadTranslationReason("Nooー!"))
    }

    @Test
    fun deadTranslationReason_namesEmptyNonTranslationAndResidue() {
        assertEquals("empty", deadTranslationReason(null))
        assertEquals("empty", deadTranslationReason("  "))
        assertEquals("non-translation", deadTranslationReason("Translate the following Japanese text."))
        assertEquals("non-translation", deadTranslationReason("Translate these Japanese lines:"))
        assertEquals("non-translation", deadTranslationReason("Reply with the translation only."))
        assertEquals("non-translation", deadTranslationReason("One per line, numbered."))
        assertEquals("japanese residue", deadTranslationReason("おはよう, everyone"))
        assertEquals("japanese residue", deadTranslationReason("ｶﾀｶﾅ"))
    }

    @Test
    fun deadTranslationReason_acceptsCyrillicTranslation() {
        assertNull(deadTranslationReason("Привет, как дела?"))
        assertFalse("Ёлка — это ёлка.".hasJapaneseScript())
    }

    @Test
    fun hasJapaneseScript_rejectsReaderInterfaceTextAndBarePunctuation() {
        listOf("Sep15.2.50AN", "oloEdit", "00:37", "OK!", "!?", "……", "ー", "・・・").forEach { text ->
            assertFalse(text, text.hasJapaneseScript())
        }
    }

    @Test
    fun hasJapaneseScript_acceptsAnyKanaOrKanji() {
        listOf("こんにちは", "え?!", "ドーン", "ｳｿ", "源", "回00.37").forEach { text ->
            assertTrue(text, text.hasJapaneseScript())
        }
    }

    @Test
    fun looksLikeNonTranslation_keepsLegitDialogue() {
        assertFalse(looksLikeNonTranslation("I'm sorry!"))
        assertFalse(looksLikeNonTranslation("I'm sorry, I can't come with you today."))
        assertFalse(looksLikeNonTranslation("Sorry for being late, let's go back there."))
        assertFalse(looksLikeNonTranslation("This is a picture of a native of the moon."))
        assertFalse(looksLikeNonTranslation("I love you I love you I love you"))
        assertFalse(looksLikeNonTranslation("No no no no no no"))
        assertFalse(looksLikeNonTranslation("Please provide the sword tomorrow."))
        assertFalse(looksLikeNonTranslation("Provide me with your best excuse, then."))
        assertFalse(looksLikeNonTranslation("The Japanese text on that sign scared me."))
    }

    @Test
    fun translate_modelMissingIsANotLoadedResultWithAReaderMessage() = runTest {
        val slot = FakeTranslationSlot(PageTranslation.notLoaded(TranslationStatus.Error("model_missing")))

        val result = TranslationEngine { slot }.translate(listOf(block(1 to "\u3053\u3093\u306b\u3061\u306f")))

        assertEquals(TranslationOutcome.NOT_LOADED, result.outcome)
        assertEquals("model_missing", result.errorCode)
        assertEquals("Translation model is missing \u2014 download it again in Settings", result.readerFailure())
        // The bubble still carries its source text, which is exactly why the reader needs telling.
        assertEquals("\u3053\u3093\u306b\u3061\u306f", result.translations.single().translatedText)
    }

    @Test
    fun readerFailure_isNullForATranslatedPage() = runTest {
        val slot = FakeTranslationSlot(PageTranslation(mapOf(1 to "Hello"), "", 1L))

        val result = TranslationEngine { slot }.translate(listOf(block(1 to "\u3053\u3093\u306b\u3061\u306f")))

        assertNull(result.readerFailure())
    }

    @Test
    fun translate_asksForTheTargetLanguageAndRecordsIt() = runTest {
        val slot = FakeTranslationSlot(PageTranslation(mapOf(1 to "Hola"), "", 1L))

        val result = TranslationEngine { slot }.translate(listOf(block(1 to "\u3053\u3093\u306b\u3061\u306f")), TargetLanguage.SPANISH)

        assertEquals(TargetLanguage.SPANISH, slot.pages.single().targetLanguage)
        assertEquals(TargetLanguage.SPANISH, result.targetLanguage)
    }

    @Test
    fun translate_withoutALanguageIsEnglish() = runTest {
        val slot = FakeTranslationSlot(PageTranslation(mapOf(1 to "Hello"), "", 1L))

        val result = TranslationEngine { slot }.translate(listOf(block(1 to "\u3053\u3093\u306b\u3061\u306f")))

        assertEquals(TargetLanguage.ENGLISH, slot.pages.single().targetLanguage)
        assertEquals(TargetLanguage.ENGLISH, result.targetLanguage)
    }

    @Test
    fun readerFailure_saysAnEnglishOnlyModelCannotWriteTheLanguage() = runTest {
        val slot = FakeTranslationSlot(PageTranslation(emptyMap(), "", 0L, TranslationOutcome.ERROR, ERROR_ENGLISH_ONLY))

        val result = TranslationEngine { slot }.translate(listOf(block(1 to "\u3053\u3093\u306b\u3061\u306f")), TargetLanguage.GERMAN)

        assertEquals("This model is English only \u2014 pick a Multilingual model or English in Settings", result.readerFailure())
    }

    /** Builds a panel with synthetic bounds, OCR text keyed by bubble ID, and the requested reading order. */
    private fun block(
        vararg bubbleTexts: Pair<Int, String>,
        readingOrder: List<Int> = bubbleTexts.map { it.first }
    ): ConversationBlock {
        val bubbles = bubbleTexts.map { (id, _) ->
            Bubble(id, RectF(0f, 0f, 100f, 100f), 0.9f)
        }
        val byId = bubbleTexts.associate { (id, text) ->
            id to OcrResult(text, 1f, floatArrayOf(0f, 0f, 1f, 1f))
        }
        return ConversationBlock(1, bubbles, byId.values.toList(), readingOrder, byId)
    }
}

package com.yomu.ml

import com.yomu.core.ERROR_ENGLISH_ONLY
import com.yomu.core.GenerationParams
import com.yomu.core.ModelProfile
import com.yomu.core.RuntimeLimits
import com.yomu.core.SourceLanguage
import com.yomu.core.TargetLanguage
import com.yomu.core.TranslatableBubble
import com.yomu.core.TranslatablePage
import com.yomu.core.TranslationOutcome
import com.yomu.core.TranslationPromptMode
import com.yomu.core.TranslationStatus
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaTranslationBridgeTest {

    @Test
    fun translatePage_modelCardTranslatesEachBubbleWithoutContext() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { prompt ->
            GenerationResult.Success(if (prompt.endsWith("こんにちは")) "Hello" else "Goodbye", 10L)
        }
        val slot = LlamaTranslationBridge(native, profile(model, TranslationPromptMode.MODEL_CARD))

        val result = slot.translatePage(page(1 to "こんにちは", 2 to "さようなら"))

        assertEquals(mapOf(1 to "Hello", 2 to "Goodbye"), result.byId)
        assertEquals(2, native.prompts.size)
        assertTrue(native.prompts[0].endsWith("こんにちは"))
        assertFalse(native.prompts[0].contains("さようなら"))
        assertEquals(20L, result.durationMs)
        model.delete()
    }

    @Test
    fun translatePage_translationOnlyPromptContainsOnlyTheTarget() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Hello", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, TranslationPromptMode.TRANSLATION_ONLY))

        slot.translatePage(page(1 to "こんにちは", 2 to "さようなら"))

        assertTrue(native.prompts.first().contains("Return only"))
        assertTrue(native.prompts.first().endsWith("こんにちは"))
        assertFalse(native.prompts.first().contains("さようなら"))
        model.delete()
    }

    @Test
    fun translatePage_hyMt2PromptIsTencentsSingleTextTranslationPrompt() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Hello", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, TranslationPromptMode.HY_MT2))

        slot.translatePage(page(1 to "\u3053\u3093\u306b\u3061\u306f", 2 to "\u3055\u3088\u3046\u306a\u3089"))

        assertEquals(
            "Translate the following text into English. Note that you should only output the " +
                "translated result without any additional explanation:\n\n\u3053\u3093\u306b\u3061\u306f",
            native.prompts.first()
        )
        assertEquals(listOf("", ""), native.systemMessages)
        model.delete()
    }

    @Test
    fun translatePage_idKeyedBatchBuildsOnePagePromptAndParsesById() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge {
            GenerationResult.Success("Here are the translations:\n[2] Goodbye\nignored\n[1] Hello\n", 100L)
        }
        val slot = LlamaTranslationBridge(native, profile(model, idKeyedBatch = true))
        val page = TranslatablePage(
            listOf(
                listOf(TranslatableBubble(1, "こんにちは")),
                listOf(TranslatableBubble(2, "さようなら"))
            )
        )

        val result = slot.translatePage(page)

        assertEquals(mapOf(2 to "Goodbye", 1 to "Hello"), result.byId)
        assertEquals(1, native.prompts.size)
        assertTrue(native.prompts.single().contains("[1] こんにちは\n---\n[2] さようなら"))
        assertEquals(100L, result.durationMs)
        model.delete()
    }

    @Test
    fun translatePage_idKeyedBatchOmitsMissingIds() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val slot = LlamaTranslationBridge(
            FakeLlamaBridge { GenerationResult.Success("[1] Hello\n", 1L) },
            profile(model, idKeyedBatch = true)
        )

        val result = slot.translatePage(page(1 to "こんにちは", 2 to "さようなら"))

        assertEquals(mapOf(1 to "Hello"), result.byId)
        model.delete()
    }

    /** A shown bubble cannot be taken back, so a repeated id keeps its first line; the grammar makes repeats unreachable. */
    @Test
    fun translatePage_idKeyedBatchDuplicateIdKeepsTheFirstLine() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val slot = LlamaTranslationBridge(
            FakeLlamaBridge { GenerationResult.Success("[1] Hello\n[2] Goodbye\n[1] Hi there\n", 1L) },
            profile(model, idKeyedBatch = true)
        )
        val reported = mutableListOf<Pair<Int, String>>()

        val result = slot.translatePage(page(1 to "こんにちは", 2 to "さようなら")) { id, text -> reported += id to text }

        assertEquals(listOf(1 to "Hello", 2 to "Goodbye"), reported)
        assertEquals(mapOf(1 to "Hello", 2 to "Goodbye"), result.byId)
        model.delete()
    }

    /** Verifies blank and failed per-line generations leave the returned translation map empty. */
    @Test
    fun translatePage_blankOrFailedLinesAreOmitted() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val results = ArrayDeque<GenerationResult>().apply {
            add(GenerationResult.Blank(1L))
            add(GenerationResult.Error("fail", 2L))
        }
        val slot = LlamaTranslationBridge(
            FakeLlamaBridge { results.removeFirst() },
            profile(model)
        )

        val result = slot.translatePage(page(1 to "一", 2 to "二"))

        assertTrue(result.byId.isEmpty())
        model.delete()
    }

    /** Verifies each generation is followed by its callback before the next bubble is generated. */
    @Test
    fun translatePage_perLineReportsEachBubbleWhenItsCallReturnsInReadingOrder() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val events = mutableListOf<String>()
        val native = FakeLlamaBridge { prompt ->
            val source = if (prompt.endsWith("こんにちは")) "こんにちは" else "さようなら"
            events += "generate $source"
            GenerationResult.Success(if (source == "こんにちは") "Hello" else "Goodbye", 1L)
        }
        val slot = LlamaTranslationBridge(native, profile(model))

        val result = slot.translatePage(page(1 to "こんにちは", 2 to "さようなら")) { id, text ->
            events += "report $id $text"
        }

        assertEquals(
            listOf("generate こんにちは", "report 1 Hello", "generate さようなら", "report 2 Goodbye"),
            events
        )
        assertEquals(mapOf(1 to "Hello", 2 to "Goodbye"), result.byId)
        model.delete()
    }

    /** Verifies blank and timed-out generations emit nothing and successful reports match the returned page. */
    @Test
    fun translatePage_perLineReportsOnlyTheBubblesInTheFinalResultOnceEach() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val results = ArrayDeque<GenerationResult>().apply {
            add(GenerationResult.Success("One", 1L))
            add(GenerationResult.Blank(1L))
            add(GenerationResult.Timeout(1L))
            add(GenerationResult.Success("Four", 1L))
        }
        val slot = LlamaTranslationBridge(FakeLlamaBridge { results.removeFirst() }, profile(model))
        val reported = mutableListOf<Pair<Int, String>>()

        val result = slot.translatePage(page(1 to "一", 2 to "二", 3 to "三", 4 to "四")) { id, text ->
            reported += id to text
        }

        assertEquals(listOf(1 to "One", 4 to "Four"), reported)
        assertEquals(result.byId.toList(), reported)
        model.delete()
    }

    /**
     * Verifies batch generation receives a grammar containing one reply tag for each requested bubble ID,
     * with only the last line's newline optional so the model can end its reply after it (#368).
     */
    @Test
    fun translatePage_batchPinsTheReplyShapeWithAGrammarPerId() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("[1] Hello\n[2] Bye\n", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, idKeyedBatch = true))

        slot.translatePage(page(1 to "こんにちは", 2 to "さようなら"))

        val grammar = native.grammars.single()
        assertTrue(grammar, grammar.startsWith("root ::= \"[1] \" line \"\\n\" \"[2] \" line \"\\n\"?\n"))
        assertTrue(grammar, grammar.contains("line ::= [^\\r\\n\\[]{1,160}"))
        model.delete()
    }

    @Test
    fun translatePage_batchLineAdmitsIdBracketWhenOptedOut() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("[1] Hello", 1L) }
        val slot = LlamaTranslationBridge(
            native,
            profile(model, idKeyedBatch = true).copy(
                generation = GenerationParams(lineExcludesIdBracket = false)
            )
        )

        slot.translatePage(page(1 to "こんにちは"))

        val grammar = native.grammars.single()
        assertTrue(grammar, grammar.startsWith("root ::= \"[1] \" line \"\\n\""))
        assertTrue(grammar, grammar.contains("line ::= [^\\r\\n]{1,160}"))
        model.delete()
    }

    @Test
    fun translatePage_perLineSamplesUnconstrained() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Hello", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model))

        slot.translatePage(page(1 to "こんにちは"))

        assertEquals(listOf(""), native.grammars)
        model.delete()
    }

    @Test
    fun translatePage_batchBudgetIsTheFixedPageCap() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("[1] Hello", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, idKeyedBatch = true))

        slot.translatePage(page(1 to "こんにちは".repeat(50)))

        assertEquals(listOf(768), native.maxTokens)
        model.delete()
    }

    @Test
    fun generate_outOfBoundsSamplerValuesReachNativeAsDefaults() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Hello", 1L) }
        val unsafe = GenerationParams(temperature = Float.NaN, topK = 0, topP = 0.5f, penaltyRepeat = 1.1f)
        val slot = LlamaTranslationBridge(native, profile(model).copy(generation = unsafe))

        slot.translatePage(page(1 to "こんにちは"))

        assertEquals(listOf(GenerationParams(topP = 0.5f, penaltyRepeat = 1.1f)), native.params)
        model.delete()
    }

    @Test
    fun generate_inBoundsSamplerValuesReachNativeUnchanged() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Hello", 1L) }
        val tuned = GenerationParams(temperature = 1f, topK = 1, topP = 0f)
        val slot = LlamaTranslationBridge(native, profile(model).copy(generation = tuned))

        slot.translatePage(page(1 to "こんにちは"))

        assertEquals(listOf(tuned), native.params)
        model.delete()
    }

    @Test
    fun selectModel_switchesTheWholeProfileAndDropsReadiness() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("x", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model))
        assertTrue(slot.ensureReady())

        slot.selectModel(ModelProfile("/other.gguf", true, TranslationPromptMode.TRANSLATION_ONLY))

        assertEquals(TranslationStatus.NotReady, slot.status)
        assertEquals(1, native.releaseCalls)
        model.delete()
    }

    @Test
    fun selectModel_newRuntimeLimitsReloadTheModelWithThem() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("x", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model))
        assertTrue(slot.ensureReady())

        slot.selectModel(profile(model).copy(runtime = RuntimeLimits(threads = 1, contextTokens = 1024)))
        assertEquals(TranslationStatus.NotReady, slot.status)
        slot.translatePage(page(1 to "こんにちは"))

        assertEquals(1, native.releaseCalls)
        assertEquals(listOf(1024 to 1), native.loads)
        model.delete()
    }

    @Test
    fun selectModel_aRepackChangeReloadsTheModelWithIt() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("x", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model))
        assertTrue(slot.ensureReady())

        slot.selectModel(profile(model).copy(repackWeights = false))
        slot.translatePage(page(1 to "こんにちは"))

        assertEquals(1, native.releaseCalls)
        assertEquals(listOf(false), native.repacks)
        model.delete()
    }

    @Test
    fun translatePage_recordsTheLastPageDuration() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("x", 10L) }
        val slot = LlamaTranslationBridge(native, profile(model))
        assertEquals(null, slot.lastPageDurationMs)

        slot.translatePage(page(1 to "こんにちは", 2 to "さようなら"))

        assertEquals(20L, slot.lastPageDurationMs)
        model.delete()
    }

    @Test
    fun selectModel_sameProfileIsANoOp() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("x", 1L) }
        val profile = profile(model)
        val slot = LlamaTranslationBridge(native, profile)
        assertTrue(slot.ensureReady())

        slot.selectModel(profile)

        assertEquals(TranslationStatus.Ready, slot.status)
        assertEquals(0, native.releaseCalls)
        model.delete()
    }

    @Test
    fun endSessionClearsMemoryAndCloseReleasesWeights() {
        val native = FakeLlamaBridge { GenerationResult.Success("x", 1L) }
        val slot = LlamaTranslationBridge(native, ModelProfile("", false, TranslationPromptMode.MODEL_CARD))

        slot.endSession()
        slot.close()

        assertEquals(1, native.clearMemoryCalls)
        assertEquals(1, native.releaseCalls)
        assertEquals(TranslationStatus.NotReady, slot.status)
    }

    @Test
    fun translatePage_cancelMidGenerateAbortsNativeAndStopsThePage() = runTest {
        val model = File.createTempFile("model", ".gguf")
        lateinit var job: Job
        val native = FakeLlamaBridge {
            job.cancel()
            GenerationResult.Blank(1L)
        }
        val slot = LlamaTranslationBridge(native, profile(model))

        job = launch { slot.translatePage(page(1 to "こんにちは", 2 to "さようなら")) }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf(false, true), native.abortRequests)
        assertEquals(1, native.prompts.size)
        model.delete()
    }

    @Test
    fun translatePage_missingModelFileIsANotLoadedOutcome() = runTest {
        val model = File.createTempFile("model", ".gguf")
        model.delete()
        val native = FakeLlamaBridge { GenerationResult.Success("Hello", 1L) }
        native.release() // nothing loaded yet, as on a fresh process
        val slot = LlamaTranslationBridge(native, profile(model))

        val result = slot.translatePage(page(1 to "\u3053\u3093\u306b\u3061\u306f"))

        assertEquals(TranslationOutcome.NOT_LOADED, result.outcome)
        assertEquals("model_missing", result.errorCode)
        assertEquals(TranslationStatus.Error("model_missing"), slot.status)
        assertTrue(native.prompts.isEmpty())
    }

    @Test
    fun translatePage_sendsTheProfilesSystemMessageWithEveryCall() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Hello", 1L) }
        val system = "You are a manga translator."
        val slot = LlamaTranslationBridge(
            native,
            profile(model).copy(systemMessage = system)
        )

        slot.translatePage(page(1 to "\u3053\u3093\u306b\u3061\u306f", 2 to "\u3055\u3088\u3046\u306a\u3089"))

        assertEquals(listOf(system, system), native.systemMessages)
        model.delete()
    }

    @Test
    fun translatePage_sendsNoSystemMessageWhenTheProfileCarriesNone() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Hello", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model))

        slot.translatePage(page(1 to "\u3053\u3093\u306b\u3061\u306f"))

        assertEquals(listOf(""), native.systemMessages)
        model.delete()
    }

    @Test
    fun translatePage_batchPromptNamesTheTargetLanguage() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("[1] Olá\n", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, idKeyedBatch = true, multilingual = true))

        slot.translatePage(page(1 to "こんにちは", language = TargetLanguage.BRAZILIAN_PORTUGUESE))

        assertEquals(
            "Translate each Japanese line to Brazilian Portuguese. Keep the [id] tag before each line. " +
                "Panels are separated by ---.\n[1] こんにちは\n",
            native.prompts.single()
        )
        model.delete()
    }

    @Test
    fun translatePage_batchReplyInCyrillicKeepsItsText() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("[1] Привет!\n[2] Пока.\n", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, idKeyedBatch = true, multilingual = true))

        val result = slot.translatePage(page(1 to "こんにちは", 2 to "さようなら", language = TargetLanguage.RUSSIAN))

        assertEquals(mapOf(1 to "Привет!", 2 to "Пока."), result.byId)
        assertTrue(native.prompts.single().startsWith("Translate each Japanese line to Russian."))
        model.delete()
    }

    @Test
    fun translatePage_batchPromptForAScriptFamilyNamesNoSourceLanguage() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("[1] Куда?\n", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, idKeyedBatch = true, multilingual = true))

        slot.translatePage(page(1 to "Where to?", language = TargetLanguage.RUSSIAN, source = SourceLanguage.LATIN_SCRIPT))

        assertEquals(
            "Translate each line to Russian. Keep the [id] tag before each line. " +
                "Panels are separated by ---.\n[1] Where to?\n",
            native.prompts.single()
        )
        model.delete()
    }

    @Test
    fun translatePage_translationOnlyPromptNamesASingleSourceLanguage() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Куда ты?", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, TranslationPromptMode.TRANSLATION_ONLY, multilingual = true))

        slot.translatePage(page(1 to "어디 가?", language = TargetLanguage.RUSSIAN, source = SourceLanguage.KOREAN))

        assertEquals(
            "Translate the target Korean manga text into natural Russian. Return only the translation.\n" +
                "No introductions, explanations, labels, or added quotes. Preserve meaning and tone.\n" +
                "Target text (translate this only):\n어디 가?",
            native.prompts.single()
        )
        model.delete()
    }

    @Test
    fun translatePage_modelCardPromptNamesAnotherSourceOrNone() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Hello", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, TranslationPromptMode.MODEL_CARD))

        slot.translatePage(page(1 to "你好", source = SourceLanguage.CHINESE))
        slot.translatePage(page(1 to "Hola", source = SourceLanguage.LATIN_SCRIPT))

        assertEquals(
            listOf("Translate the following Chinese text into English.\n\n你好", "Translate the following text into English.\n\nHola"),
            native.prompts
        )
        model.delete()
    }

    @Test
    fun translatePage_englishBatchPromptIsUnchanged() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("[1] Hello\n", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, idKeyedBatch = true))

        slot.translatePage(page(1 to "こんにちは"))

        assertEquals(
            "Translate each Japanese line to English. Keep the [id] tag before each line. " +
                "Panels are separated by ---.\n[1] こんにちは\n",
            native.prompts.single()
        )
        model.delete()
    }

    @Test
    fun translatePage_translationOnlyPromptNamesTheTargetLanguage() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Hola", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, TranslationPromptMode.TRANSLATION_ONLY, multilingual = true))

        slot.translatePage(page(1 to "こんにちは", language = TargetLanguage.SPANISH))

        assertEquals(
            "Translate the target Japanese manga text into natural Spanish. Return only the translation.\n" +
                "No introductions, explanations, labels, or added quotes. Preserve meaning and tone.\n" +
                "Target text (translate this only):\nこんにちは",
            native.prompts.single()
        )
        model.delete()
    }

    @Test
    fun translatePage_hyMt2PromptNamesTheTargetLanguage() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Bonjour", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, TranslationPromptMode.HY_MT2, multilingual = true))

        slot.translatePage(page(1 to "こんにちは", language = TargetLanguage.FRENCH))

        assertEquals(
            "Translate the following text into French. Note that you should only output the " +
                "translated result without any additional explanation:\n\nこんにちは",
            native.prompts.single()
        )
        model.delete()
    }

    @Test
    fun translatePage_translateGemmaPromptIsTheOneItsTemplateRenders() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Привет", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, TranslationPromptMode.TRANSLATEGEMMA, multilingual = true))

        slot.translatePage(page(1 to "こんにちは", 2 to "さようなら", language = TargetLanguage.RUSSIAN))

        assertEquals(
            "You are a professional Japanese (ja) to Russian (ru) translator. Your goal is to accurately convey " +
                "the meaning and nuances of the original Japanese text while adhering to Russian grammar, " +
                "vocabulary, and cultural sensitivities.\nProduce only the Russian translation, without any " +
                "additional explanations or commentary. Please translate the following Japanese text into " +
                "Russian:\n\n\nこんにちは",
            native.prompts.first()
        )
        assertEquals(2, native.prompts.size)
        assertEquals(listOf("", ""), native.systemMessages)
        model.delete()
    }

    @Test
    fun translatePage_translateGemmaNamesBrazilianPortugueseAsItsTemplateDoes() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Olá", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, TranslationPromptMode.TRANSLATEGEMMA, multilingual = true))

        slot.translatePage(page(1 to "안녕", language = TargetLanguage.BRAZILIAN_PORTUGUESE, source = SourceLanguage.KOREAN))

        assertTrue(native.prompts.single().startsWith("You are a professional Korean (ko) to Portuguese (pt-BR) translator."))
        assertTrue(native.prompts.single().contains("Please translate the following Korean text into Portuguese:\n\n\n안녕"))
        model.delete()
    }

    @Test
    fun translatePage_translateGemmaOnAScriptFamilyNamesOnlyTheTarget() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Hi", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, TranslationPromptMode.TRANSLATEGEMMA))

        slot.translatePage(page(1 to "Привет", source = SourceLanguage.CYRILLIC_SCRIPT))

        assertEquals(
            "You are a professional translator into English (en). Your goal is to accurately convey the meaning " +
                "and nuances of the original text while adhering to English grammar, vocabulary, and cultural " +
                "sensitivities.\nProduce only the English translation, without any additional explanations or " +
                "commentary. Please translate the following text into English:\n\n\nПривет",
            native.prompts.single()
        )
        model.delete()
    }

    @Test
    fun translatePage_modelCardPromptIsUnchanged() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Hello", 1L) }
        val slot = LlamaTranslationBridge(native, profile(model, TranslationPromptMode.MODEL_CARD))

        slot.translatePage(page(1 to "こんにちは"))

        assertEquals("Translate the following Japanese text into English.\n\nこんにちは", native.prompts.single())
        model.delete()
    }

    @Test
    fun translatePage_englishOnlyModelRefusesAnotherLanguage() = runTest {
        val model = File.createTempFile("model", ".gguf")
        val native = FakeLlamaBridge { GenerationResult.Success("Hello", 1L) }
        TranslationPromptMode.entries.forEach { mode ->
            listOf(false, true).forEach { batch ->
                val slot = LlamaTranslationBridge(native, profile(model, mode, idKeyedBatch = batch))

                val result = slot.translatePage(page(1 to "こんにちは", language = TargetLanguage.GERMAN))

                assertEquals("$mode batch=$batch", TranslationOutcome.ERROR, result.outcome)
                assertEquals("$mode batch=$batch", ERROR_ENGLISH_ONLY, result.errorCode)
                assertTrue("$mode batch=$batch", result.byId.isEmpty())
            }
        }
        assertTrue(native.prompts.isEmpty())
        model.delete()
    }

    private fun profile(
        model: File,
        promptMode: TranslationPromptMode = TranslationPromptMode.MODEL_CARD,
        idKeyedBatch: Boolean = false,
        multilingual: Boolean = false
    ): ModelProfile = ModelProfile(model.absolutePath, idKeyedBatch, promptMode, multilingual = multilingual)

    private fun page(
        vararg bubbles: Pair<Int, String>,
        language: TargetLanguage = TargetLanguage.ENGLISH,
        source: SourceLanguage = SourceLanguage.JAPANESE
    ): TranslatablePage =
        TranslatablePage(listOf(bubbles.map { (id, text) -> TranslatableBubble(id, text) }), language, source)
}

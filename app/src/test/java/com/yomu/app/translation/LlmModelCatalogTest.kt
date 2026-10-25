package com.yomu.app.translation

import com.yomu.core.Constants
import com.yomu.core.GenerationParams
import com.yomu.core.RuntimeLimits
import com.yomu.core.TranslationPromptMode
import com.yomu.core.withinBounds
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmModelCatalogTest {

    private val twelveGb = 12L * 1024 * 1024 * 1024
    private val eightGb = 8L * 1024 * 1024 * 1024
    private val fourGb = 4L * 1024 * 1024 * 1024
    private val galaxyS23 = 7_242_452L * 1024

    private fun fit(
        totalMemBytes: Long,
        percent: Int = LlmModelCatalog.DEFAULT_FIT_BUDGET_PERCENT,
        contextTokens: Int = RuntimeLimits.DEFAULT_CONTEXT_TOKENS
    ) = FitBudget(totalMemBytes, percent, contextTokens)

    @Test
    fun `default is Qwen2_5 1_5B`() {
        assertEquals(Constants.QWEN25_15B_MODEL_ID, LlmModelCatalog.DEFAULT.id)
        assertEquals(TranslationPromptMode.TRANSLATION_ONLY, LlmModelCatalog.DEFAULT.promptMode)
    }

    @Test
    fun `default is not experimental`() {
        assertFalse(LlmModelCatalog.DEFAULT.experimental)
    }

    @Test
    fun `every Qwen experimental entry is an Apache-2_0 page-batch entry`() {
        val qwen = listOf(
            Constants.QWEN35_08B_MODEL_ID,
            Constants.QWEN35_2B_MODEL_ID,
            Constants.QWEN35_4B_MODEL_ID,
            Constants.QWEN35_9B_MODEL_ID,
            Constants.QWEN3_4B_2507_MODEL_ID
        )

        qwen.forEach { id ->
            val option = LlmModelCatalog.fromId(id)!!
            assertTrue(id, option.experimental)
            assertTrue(id, option.idKeyedBatch)
            assertEquals(id, TranslationPromptMode.TRANSLATION_ONLY, option.promptMode)
            assertEquals(id, "Apache-2.0", option.licence)
        }
    }

    @Test
    fun `Hy-MT2 1_8B is a per-line experimental entry on Tencent's own prompt and sampling`() {
        val hyMt2 = LlmModelCatalog.fromId(Constants.HY_MT2_18B_MODEL_ID)!!

        assertTrue(hyMt2.experimental)
        assertFalse(hyMt2.idKeyedBatch)
        assertEquals(TranslationPromptMode.HY_MT2, hyMt2.promptMode)
        assertEquals("Apache-2.0", hyMt2.licence)
        assertEquals("", hyMt2.systemMessage)
        assertEquals(65_536L, hyMt2.kvCacheBytesPerToken)
        assertEquals(
            GenerationParams(temperature = 0.7f, topK = 20, topP = 0.6f, penaltyRepeat = 1.05f),
            hyMt2.generationDefaults
        )
    }

    @Test
    fun `Ternary-Bonsai 4B and 8B are experimental page-batch entries on PrismML's sampling`() {
        listOf(Constants.TERNARY_BONSAI_4B_MODEL_ID, Constants.TERNARY_BONSAI_8B_MODEL_ID).forEach { id ->
            val option = LlmModelCatalog.fromId(id)!!
            assertTrue(id, option.experimental)
            assertTrue(id, option.idKeyedBatch)
            assertEquals(id, TranslationPromptMode.TRANSLATION_ONLY, option.promptMode)
            assertEquals(id, "Apache-2.0", option.licence)
            assertEquals(id, "Created using Bonsai by Prism ML", option.credit)
            assertEquals(id, 147_456L, option.kvCacheBytesPerToken)
            assertEquals(id, GenerationParams(temperature = 0.5f, topK = 20, topP = 0.85f), option.generationDefaults)
        }
    }

    @Test
    fun `the entries phone-checked before the Experimental tier are not experimental`() {
        val checked = listOf(Constants.QWEN25_15B_MODEL_ID, Constants.CAT_TRANSLATION_MODEL_ID, Constants.CAT_TRANSLATION_14B_MODEL_ID)
        checked.forEach { assertFalse(it, LlmModelCatalog.fromId(it)!!.experimental) }
    }

    @Test
    fun `selectedOrDefault falls back to default for null or unknown id`() {
        assertEquals(LlmModelCatalog.DEFAULT, LlmModelCatalog.selectedOrDefault(null))
        assertEquals(LlmModelCatalog.DEFAULT, LlmModelCatalog.selectedOrDefault("no-such-model"))
    }

    @Test
    fun `selectedOrDefault resolves a known id`() {
        val floor = LlmModelCatalog.selectedOrDefault(Constants.CAT_TRANSLATION_MODEL_ID)
        assertEquals(Constants.CAT_TRANSLATION_MODEL_ID, floor.id)
        assertEquals(TranslationPromptMode.MODEL_CARD, floor.promptMode)
    }

    @Test
    fun `profileFor carries the supplied generation profile unchanged onto every entry`() {
        val generation = GenerationParams(temperature = 0.7f, topK = 12, topP = 0.5f)

        LlmModelCatalog.ALL.forEach { option ->
            assertEquals(option.displayName, generation, LlmModelCatalog.profileFor(option, File("m"), generation, RuntimeLimits(), fit(twelveGb)).generation)
        }
    }

    @Test
    fun `profileFor carries the supplied runtime limits onto the profile`() {
        val runtime = RuntimeLimits(threads = 2, contextTokens = 1024)

        assertEquals(runtime, LlmModelCatalog.profileFor(LlmModelCatalog.DEFAULT, File("m"), GenerationParams(), runtime, fit(twelveGb)).runtime)
    }

    @Test
    fun `default is never gated out even on a tiny device`() {
        assertTrue(LlmModelCatalog.canRunOnDevice(LlmModelCatalog.DEFAULT, fit(1L, percent = 30)))
    }

    @Test
    fun `a lower fit budget gates out a model the default budget offers`() {
        val model = LlmModelCatalog.fromId(Constants.CAT_TRANSLATION_14B_MODEL_ID)!!

        assertTrue(LlmModelCatalog.canRunOnDevice(model, fit(fourGb)))
        assertFalse(LlmModelCatalog.canRunOnDevice(model, fit(fourGb, percent = 30)))
    }

    @Test
    fun `a larger context's KV cache counts against the fit budget`() {
        val model = LlmModelCatalog.fromId(Constants.CAT_TRANSLATION_14B_MODEL_ID)!!

        assertTrue(LlmModelCatalog.canRunOnDevice(model, fit(fourGb, percent = 45, contextTokens = 1536)))
        assertFalse(LlmModelCatalog.canRunOnDevice(model, fit(fourGb, percent = 45, contextTokens = 2816)))
    }

    @Test
    fun `every curated entry carries a KV cache cost`() {
        LlmModelCatalog.ALL.forEach { assertTrue(it.displayName, it.kvCacheBytesPerToken > 0) }
    }

    @Test
    fun `a small model fits an 8GB device`() {
        val floor = LlmModelCatalog.selectedOrDefault(Constants.CAT_TRANSLATION_MODEL_ID)
        assertTrue(LlmModelCatalog.canRunOnDevice(floor, fit(eightGb)))
    }

    @Test
    fun `every shortlist model but the 12GB tier fits an 8GB device`() {
        // None of the rest reach the 7B footprint that OOMs on 8GB (#84). Ministral 3 8B (#287),
        // Gemma 4 E4B (#286) and Qwen3.5 9B (#285) are curated above that line on purpose: they are
        // offered to 12GB phones and gated out below, which is the gate doing its job rather than a
        // mis-sized entry.
        val twelveGbTier = setOf(
            Constants.MINISTRAL3_8B_MODEL_ID,
            Constants.GEMMA4_E4B_MODEL_ID,
            Constants.QWEN35_9B_MODEL_ID,
            Constants.QWEN35_9B_UNCENSORED_MODEL_ID,
            Constants.GEMMA4_E4B_UNCENSORED_MODEL_ID
        )
        LlmModelCatalog.ALL.filterNot { it.id in twelveGbTier }.forEach { option ->
            assertTrue(option.displayName, LlmModelCatalog.canRunOnDevice(option, fit(eightGb)))
        }
    }

    @Test
    fun `Qwen3_5 9B is gated out on 8GB and offered on 12GB`() {
        // ADR-0017's ~9B ceiling: the existing fit gate is what keeps the tier off an 8GB phone.
        val nineB = LlmModelCatalog.fromId(Constants.QWEN35_9B_MODEL_ID)!!

        assertFalse(LlmModelCatalog.canRunOnDevice(nineB, fit(eightGb)))
        assertTrue(LlmModelCatalog.canRunOnDevice(nineB, fit(twelveGb)))
    }

    @Test
    fun `Gemma 4 E4B is gated out on 8GB and offered on 12GB`() {
        val e4b = LlmModelCatalog.fromId(Constants.GEMMA4_E4B_MODEL_ID)!!

        assertFalse(LlmModelCatalog.canRunOnDevice(e4b, fit(eightGb)))
        assertTrue(LlmModelCatalog.canRunOnDevice(e4b, fit(twelveGb)))
    }

    @Test
    fun `both Gemma 4 entries are experimental Apache page-batch entries`() {
        listOf(Constants.GEMMA4_E2B_MODEL_ID, Constants.GEMMA4_E4B_MODEL_ID).forEach { id ->
            val option = LlmModelCatalog.fromId(id)!!

            assertTrue(id, option.experimental)
            assertTrue(id, option.idKeyedBatch)
            assertEquals(id, TranslationPromptMode.TRANSLATION_ONLY, option.promptMode)
            assertEquals(id, "Apache-2.0", option.licence)
            // Google's card sampling, shared by both sizes.
            assertEquals(id, GenerationParams(temperature = 1.0f, topK = 64, topP = 0.95f), option.generationDefaults)
        }
    }

    @Test
    fun `Gemma 4 KV sharing is counted, not the full layer stack`() {
        // 15 of E2B's 35 layers and 24 of E4B's 42 own a cache; the naive figures (35,840 and
        // 86,016 B/token) would gate both out on RAM they never spend.
        assertEquals(18_432L, LlmModelCatalog.fromId(Constants.GEMMA4_E2B_MODEL_ID)!!.kvCacheBytesPerToken)
        assertEquals(57_344L, LlmModelCatalog.fromId(Constants.GEMMA4_E4B_MODEL_ID)!!.kvCacheBytesPerToken)
    }

    private val uncensoredToBase = mapOf(
        Constants.QWEN35_2B_UNCENSORED_MODEL_ID to Constants.QWEN35_2B_MODEL_ID,
        Constants.QWEN35_4B_UNCENSORED_MODEL_ID to Constants.QWEN35_4B_MODEL_ID,
        Constants.QWEN35_9B_UNCENSORED_MODEL_ID to Constants.QWEN35_9B_MODEL_ID,
        Constants.GEMMA4_E2B_UNCENSORED_MODEL_ID to Constants.GEMMA4_E2B_MODEL_ID,
        Constants.GEMMA4_E4B_UNCENSORED_MODEL_ID to Constants.GEMMA4_E4B_MODEL_ID
    )

    @Test
    fun `each uncensored entry is an experimental Apache-2_0 entry named Uncensored`() {
        uncensoredToBase.keys.forEach { id ->
            val option = LlmModelCatalog.fromId(id)!!

            assertTrue(id, option.experimental)
            assertEquals(id, "Apache-2.0", option.licence)
            assertTrue(option.displayName, option.displayName.endsWith("Uncensored"))
        }
    }

    @Test
    fun `each uncensored entry runs exactly like its base`() {
        uncensoredToBase.forEach { (id, baseId) ->
            val option = LlmModelCatalog.fromId(id)!!
            val base = LlmModelCatalog.fromId(baseId)!!

            assertEquals(id, base.promptMode, option.promptMode)
            assertEquals(id, base.idKeyedBatch, option.idKeyedBatch)
            assertEquals(id, base.kvCacheBytesPerToken, option.kvCacheBytesPerToken)
            assertEquals(id, base.generationDefaults, option.generationDefaults)
            assertEquals(id, base.systemMessage, option.systemMessage)
        }
    }

    @Test
    fun `the uncensored 9B and E4B are gated out on 8GB and offered on 12GB`() {
        listOf(Constants.QWEN35_9B_UNCENSORED_MODEL_ID, Constants.GEMMA4_E4B_UNCENSORED_MODEL_ID).forEach { id ->
            val option = LlmModelCatalog.fromId(id)!!

            assertFalse(id, LlmModelCatalog.canRunOnDevice(option, fit(eightGb)))
            assertTrue(id, LlmModelCatalog.canRunOnDevice(option, fit(twelveGb)))
        }
    }

    @Test
    fun `a large model is gated out on a low-RAM 4GB device`() {
        // A ~3GB GGUF plus resident overhead exceeds the usable-RAM budget on 4GB. Synthetic so the
        // test does not depend on a specific large model staying in the shortlist.
        val big = LlmModelCatalog.DEFAULT.copy(id = "synthetic_big", sizeBytes = 3_000_000_000L)
        assertFalse(LlmModelCatalog.canRunOnDevice(big, fit(fourGb)))
    }

    @Test
    fun `the low-storage floor still fits a 4GB device`() {
        val floor = LlmModelCatalog.fromId(Constants.CAT_TRANSLATION_MODEL_ID)!!
        assertTrue(LlmModelCatalog.canRunOnDevice(floor, fit(fourGb)))
    }

    @Test
    fun `the entries curated before ADR-0017 keep today's global sampling`() {
        // AC of #283: a reader already on one of these sees no change when maker defaults arrive.
        listOf(Constants.QWEN25_15B_MODEL_ID, Constants.CAT_TRANSLATION_MODEL_ID, Constants.CAT_TRANSLATION_14B_MODEL_ID)
            .forEach { id ->
                val option = LlmModelCatalog.fromId(id)!!
                assertEquals(option.displayName, GenerationParams(), option.generationDefaults)
            }
    }

    @Test
    fun `each Qwen entry carries its card's sampling, not the shipped floor`() {
        // The card's non-thinking values, from the research addendum's vendor sampling table: the
        // small pair share one row, the 4B/9B pair another, and Qwen3-4B-2507 asks for no presence.
        val expected = mapOf(
            Constants.QWEN35_08B_MODEL_ID to GenerationParams(temperature = 1.0f, topK = 20, topP = 1.0f, penaltyPresent = 2.0f),
            Constants.QWEN35_2B_MODEL_ID to GenerationParams(temperature = 1.0f, topK = 20, topP = 1.0f, penaltyPresent = 2.0f),
            Constants.QWEN35_4B_MODEL_ID to GenerationParams(temperature = 0.7f, topK = 20, topP = 0.8f, penaltyPresent = 1.5f),
            Constants.QWEN35_9B_MODEL_ID to GenerationParams(temperature = 0.7f, topK = 20, topP = 0.8f, penaltyPresent = 1.5f),
            Constants.QWEN3_4B_2507_MODEL_ID to GenerationParams(temperature = 0.7f, topK = 20, topP = 0.8f)
        )

        expected.forEach { (id, sampling) ->
            assertEquals(id, sampling, LlmModelCatalog.fromId(id)!!.generationDefaults)
        }
    }

    @Test
    fun `every entry's maker sampling survives the inference boundary`() {
        // LlamaTranslationBridge re-checks the profile through withinBounds(), which replaces a
        // reader-facing field outside its bound with the shipped default. A maker value outside a
        // bound would be dropped there without a word, so it must not reach the catalog.
        LlmModelCatalog.ALL.forEach {
            assertEquals(it.displayName, it.generationDefaults, it.generationDefaults.withinBounds())
        }
    }

    @Test
    fun `every curated entry carries a redistributable licence`() {
        // ADR-0009/ADR-0014: Yomu hosts every curated model, so a licence it cannot redistribute
        // keeps a model out of the catalog entirely.
        val redistributable = setOf("MIT", "Apache-2.0")
        LlmModelCatalog.ALL.forEach {
            assertTrue("${it.displayName}: ${it.licence}", it.licence in redistributable)
        }
    }

    @Test
    fun `CAT-Translate 0_8B and 1_4B are English only`() {
        listOf(Constants.CAT_TRANSLATION_MODEL_ID, Constants.CAT_TRANSLATION_14B_MODEL_ID).forEach { id ->
            assertFalse(id, LlmModelCatalog.fromId(id)!!.multilingual)
        }
    }

    @Test
    fun `only the entries whose maker names every target language, and Gemma 4, are Multilingual`() {
        val multilingual = setOf(
            Constants.QWEN25_15B_MODEL_ID,
            Constants.QWEN35_08B_MODEL_ID,
            Constants.QWEN35_2B_MODEL_ID,
            Constants.QWEN35_4B_MODEL_ID,
            Constants.QWEN35_9B_MODEL_ID,
            Constants.QWEN35_2B_UNCENSORED_MODEL_ID,
            Constants.QWEN35_4B_UNCENSORED_MODEL_ID,
            Constants.QWEN35_9B_UNCENSORED_MODEL_ID,
            Constants.QWEN3_4B_2507_MODEL_ID,
            Constants.MINISTRAL3_3B_MODEL_ID,
            Constants.MINISTRAL3_8B_MODEL_ID,
            Constants.GEMMA4_E2B_MODEL_ID,
            Constants.GEMMA4_E4B_MODEL_ID,
            Constants.GEMMA4_E2B_UNCENSORED_MODEL_ID,
            Constants.GEMMA4_E4B_UNCENSORED_MODEL_ID,
            Constants.HY_MT2_18B_MODEL_ID
        )
        assertEquals(multilingual, LlmModelCatalog.ALL.filter { it.multilingual }.map { it.id }.toSet())
    }

    @Test
    fun `Ministral 3 3B and 8B are experimental page-batch entries`() {
        listOf(Constants.MINISTRAL3_3B_MODEL_ID, Constants.MINISTRAL3_8B_MODEL_ID).forEach { id ->
            val option = LlmModelCatalog.fromId(id)!!
            assertTrue(option.displayName, option.experimental)
            assertTrue(option.displayName, option.idKeyedBatch)
            assertEquals(option.displayName, TranslationPromptMode.TRANSLATION_ONLY, option.promptMode)
            assertEquals(option.displayName, "Apache-2.0", option.licence)
        }
    }

    @Test
    fun `Ministral 3 carries the card's sub-0_1 temperature`() {
        // The card's only "Recommended Settings" line. Above 0.1 and the entry no longer states what
        // its maker asked for.
        listOf(Constants.MINISTRAL3_3B_MODEL_ID, Constants.MINISTRAL3_8B_MODEL_ID).forEach { id ->
            val option = LlmModelCatalog.fromId(id)!!
            assertTrue(option.displayName, option.generationDefaults.temperature < 0.1f)
        }
    }

    @Test
    fun `Ministral 3 8B is gated off an 8GB phone and fits a 12GB one`() {
        // Its 174,080 B/token KV is what decides it: weights alone would fit either.
        val ministral8b = LlmModelCatalog.fromId(Constants.MINISTRAL3_8B_MODEL_ID)!!

        assertFalse(LlmModelCatalog.canRunOnDevice(ministral8b, fit(eightGb)))
        assertTrue(LlmModelCatalog.canRunOnDevice(ministral8b, fit(twelveGb)))
    }

    @Test
    fun `Ministral 3 states its own role instead of Mistral's default assistant prompt`() {
        // Ministral's template injects Mistral's Le Chat assistant prompt when no system message is
        // sent, so an empty one here would silently send the model to work as a chat assistant (#287).
        listOf(Constants.MINISTRAL3_3B_MODEL_ID, Constants.MINISTRAL3_8B_MODEL_ID).forEach { id ->
            val option = LlmModelCatalog.fromId(id)!!
            assertTrue(option.displayName, option.systemMessage.isNotBlank())
        }
    }

    @Test
    fun `the entries curated before per-entry system messages send no system turn`() {
        listOf(
            Constants.QWEN25_15B_MODEL_ID,
            Constants.CAT_TRANSLATION_MODEL_ID,
            Constants.CAT_TRANSLATION_14B_MODEL_ID,
            Constants.QWEN35_2B_MODEL_ID
        ).forEach { id ->
            assertEquals(id, "", LlmModelCatalog.fromId(id)!!.systemMessage)
        }
    }

    @Test
    fun `profileFor marks the profile multilingual only for a Multilingual entry`() {
        fun profile(id: String) = LlmModelCatalog.profileFor(LlmModelCatalog.fromId(id)!!, File("m"), GenerationParams(), RuntimeLimits(), fit(twelveGb))

        assertTrue(profile(Constants.QWEN25_15B_MODEL_ID).multilingual)
        assertTrue(profile(Constants.HY_MT2_18B_MODEL_ID).multilingual)
        assertFalse(profile(Constants.CAT_TRANSLATION_14B_MODEL_ID).multilingual)
    }

    @Test
    fun `profileFor carries the entry's system message onto every entry`() {
        LlmModelCatalog.ALL.forEach { option ->
            val profile = LlmModelCatalog.profileFor(option, File("m"), GenerationParams(), RuntimeLimits(), fit(twelveGb))
            assertEquals(option.displayName, option.systemMessage, profile.systemMessage)
        }
    }

    @Test
    fun `profileFor repacks weights only when a second copy of them still fits the budget`() {
        listOf(
            Constants.QWEN25_15B_MODEL_ID,
            Constants.QWEN35_2B_MODEL_ID,
            Constants.HY_MT2_18B_MODEL_ID
        ).forEach { id ->
            val profile = LlmModelCatalog.profileFor(LlmModelCatalog.fromId(id)!!, File("m"), GenerationParams(), RuntimeLimits(), fit(galaxyS23))
            assertTrue(id, profile.repackWeights)
        }
        listOf(
            Constants.QWEN35_4B_MODEL_ID,
            Constants.QWEN3_4B_2507_MODEL_ID,
            Constants.MINISTRAL3_3B_MODEL_ID,
            Constants.QWEN35_4B_UNCENSORED_MODEL_ID
        ).forEach { id ->
            val profile = LlmModelCatalog.profileFor(LlmModelCatalog.fromId(id)!!, File("m"), GenerationParams(), RuntimeLimits(), fit(galaxyS23))
            assertFalse(id, profile.repackWeights)
        }
    }

    @Test
    fun `profileFor counts the reader's context size against the repacked copy`() {
        val qwen2b = LlmModelCatalog.fromId(Constants.QWEN35_2B_MODEL_ID)!!
        val twiceAt1536 = qwen2b.sizeBytes * 2 + LlmModelCatalog.RESIDENT_OVERHEAD_BYTES + qwen2b.kvCacheBytesPerToken * 1536
        val percent = LlmModelCatalog.DEFAULT_FIT_BUDGET_PERCENT
        val budget = fit((twiceAt1536 + percent - 1) / percent * 100, contextTokens = 1536)

        assertTrue(LlmModelCatalog.profileFor(qwen2b, File("m"), GenerationParams(), RuntimeLimits(contextTokens = 1536), budget).repackWeights)
        assertFalse(LlmModelCatalog.profileFor(qwen2b, File("m"), GenerationParams(), RuntimeLimits(contextTokens = 2048), budget.copy(contextTokens = 2048)).repackWeights)
    }

    @Test
    fun `profileFor keeps a 4B entry unrepacked when the reader raises the fit budget`() {
        val qwen4b = LlmModelCatalog.fromId(Constants.QWEN35_4B_MODEL_ID)!!

        assertFalse(LlmModelCatalog.profileFor(qwen4b, File("m"), GenerationParams(), RuntimeLimits(), fit(galaxyS23, percent = 90)).repackWeights)
    }

    @Test
    fun `profileFor repacks when the device memory is unknown`() {
        LlmModelCatalog.ALL.forEach { option ->
            assertTrue(option.displayName, LlmModelCatalog.profileFor(option, File("m"), GenerationParams(), RuntimeLimits(), fit(0L)).repackWeights)
        }
    }
}

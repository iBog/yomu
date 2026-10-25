package com.yomu.app.translation

import android.content.SharedPreferences
import com.yomu.core.Constants
import com.yomu.core.GenerationParams
import com.yomu.core.ModelProfile
import com.yomu.core.RuntimeLimits
import com.yomu.core.TranslationPromptMode
import java.io.File

/**
 * One curated LLM the user may put in the translation slot (ADR-0009, ADR-0016). The catalog
 * is the single source of truth linking a persisted model id to the GGUF
 * that drives [com.yomu.ml.LlamaTranslationBridge] and to its per-model [idKeyedBatch] capability
 * (#84) — killing the old hardcoded 0.8b path.
 */
data class LlmModelOption(
    val id: String,
    val displayName: String,
    val ggufFileName: String,
    val sizeBytes: Long,
    /**
     * Confirmed licence — the evidence an entry may be redistributed, since Yomu hosts every curated
     * model through the pinned-URL [ModelManager] flow (ADR-0014). Checked 2026-08-19:
     * Qwen2.5 = Apache-2.0; CAT-Translate 0.8b/1.4b = MIT (cyberagent, finetunes of sbintuitions
     * sarashina2.2, both declared MIT). Qwen3.5 = Apache-2.0 on the card and the unsloth GGUF repo
     * (checked 2026-09-16). Ministral 3 3B/8B Instruct 2512 = Apache-2.0 on the card and the
     * first-party GGUF repo (checked 2026-09-18). Gemma 4 = Apache-2.0 on the card and on Google's
     * QAT GGUF repos, a change from the Gemma Terms the older Gemma models carry (checked
     * 2026-09-16). Hy-MT2 1.8B = Apache-2.0 on the card, in the base repo's LICENSE.txt and on the
     * first-party GGUF repo, unlike the earlier Hunyuan-MT's Tencent licence (checked 2026-09-21).
     * Ternary-Bonsai 4B/8B = Apache-2.0 on the cards and in the GGUF headers (checked 2026-09-18).
     * Also the "governed by its own licence" notice ADR-0009 asks
     * Yomu to surface.
     */
    val licence: String,
    /**
     * The language label (#336): true only when the model card or the maker's own documentation names
     * every target language Yomu offers (English, Portuguese, Spanish, French, German). A count alone
     * ("201 languages", "35+ languages") names none of them, so its entry is English only. Checked 2026-10-20.
     */
    val multilingual: Boolean,
    /** Per-model (#84): a model that can emit one id-keyed reply for the whole page carries true and
     *  routes to the grammar-constrained page-level batch call (ADR-0013); CAT-Translate 0.8b cannot
     *  and stays per-line. Read by the slot, and by the settings screen, which hides the
     *  per-line-only capture-context switch when it is true. */
    val idKeyedBatch: Boolean,
    val promptMode: TranslationPromptMode,
    /**
     * f16 KV cache per context token: layers × 2 (K and V) × KV heads × head dim × 2 bytes, from each model's
     * config.json (checked 2026-09-15). Counted against the fit budget so a larger context can gate a model out (#79).
     */
    val kvCacheBytesPerToken: Long,
    /**
     * The sampling this model's maker recommends (ADR-0017): the floor the reader's Advanced
     * settings overlay field by field in [GenerationProfileStore], so a reader who changed nothing
     * gets the maker's values and one who moved a slider keeps it across models. The three entries
     * curated before ADR-0017 carry the shipped values, so nothing changes for a reader on them.
     */
    val generationDefaults: GenerationParams,
    /** Added on desk research and not yet run on the reference phone; the picker tags it (ADR-0017). */
    val experimental: Boolean = false,
    /**
     * The system turn this entry is rendered with (#287). Empty — the default, and what every entry
     * curated before #287 carries — sends no system turn at all, which is what CAT-Translate needs
     * (#68) and what Qwen's template already handles on its own. It exists because Ministral 3's
     * template injects Mistral's Le Chat assistant prompt when no system message is sent, so an
     * entry rendered through it must state its own role or be told it is a chat assistant.
     */
    val systemMessage: String = "",
    /** The attribution a maker's NOTICE asks for, shown beside [licence] wherever the app lists it (ADR-0017). */
    val credit: String = "",
    /** Replaces the size-derived speed label where a measurement contradicts it (ADR-0017); null keeps the size rule. */
    val speed: DeliverableSpeed? = null
) {
    /** The licence as the picker lists it, with any [credit] beside it. */
    val licenceWithCredit: String get() = if (credit.isEmpty()) licence else "$licence ($credit)"
}

/** The speed label a deliverable shows in the picker (ADR-0017). */
enum class DeliverableSpeed(val label: String) { FAST("Fast"), MEDIUM("Medium"), SLOW("Slow") }

/** The RAM a translation model must fit inside on this device: [percent] of [totalMemBytes], at [contextTokens]. */
data class FitBudget(val totalMemBytes: Long, val percent: Int, val contextTokens: Int)

/** The persisted model id, or null when absent or stored as another type (which would otherwise throw). */
internal fun SharedPreferences.storedLlmModelId(): String? =
    runCatching { getString(Constants.PREF_LLM_MODEL, null) }.getOrNull()

object LlmModelCatalog {

    // Resident cost of everything held alongside the LLM (detection + OCR + app), added to the GGUF
    // file size to predict peak footprint. A fixed estimate, not GGUF-parsed; ADR-0001's
    // exact param×quant math can replace it if the coarse fit gate proves wrong on a real device.
    const val RESIDENT_OVERHEAD_BYTES = 800L * 1024 * 1024

    // Default share of device totalMem an app can realistically use for weights before the OS OOM-kills
    // it. Calibrated to #84: Hunyuan-7B Q4 (4.6GB) is killed on an 8GB phone, its Q3 (3.8GB) fits.
    // The reader may move it (#79) — raise if a capable device wrongly hides a model, lower if one
    // that OOMs still shows.
    const val DEFAULT_FIT_BUDGET_PERCENT = 60

    /** Google's recommended sampling for Gemma 4, shared by E2B and E4B (model card). */
    private val GEMMA4_SAMPLING = GenerationParams(temperature = 1.0f, topK = 64, topP = 0.95f)

    /** ADR-0010: phone-confirmed default. Picking nothing keeps this; it is never gated out (part D). */
    val DEFAULT: LlmModelOption = LlmModelOption(
        id = Constants.QWEN25_15B_MODEL_ID,
        displayName = "Qwen2.5 1.5B Instruct",
        ggufFileName = Constants.QWEN25_15B_MODEL,
        sizeBytes = Constants.QWEN25_15B_SIZE,
        licence = "Apache-2.0",
        // The card's language list names all six target languages.
        multilingual = true,
        idKeyedBatch = true,
        promptMode = TranslationPromptMode.TRANSLATION_ONLY,
        kvCacheBytesPerToken = 28L * 2 * 2 * 128 * 2,
        generationDefaults = GenerationParams()
    )

    /**
     * Ministral's replacement for the Le Chat default: the role, and nothing about the reply's shape.
     * What to translate and how to lay the answer out stays in the user turn, which is the one the
     * batch grammar and the per-line prompts are written against (ADR-0013).
     */
    private const val MINISTRAL_SYSTEM_MESSAGE =
        "You are a manga translator. You translate Japanese into natural English."

    /**
     * PrismML's ternary Qwen3-4B and Qwen3-8B (#301, spike #299): 2.25 bits a weight, so a bigger model
     * in the same memory. The template always closes an empty think block, so they never think. The
     * cards give no sampling; the GGUF headers carry PrismML's own, not Qwen3's. Q2_0 has no repacked
     * ARM kernel, so the 4B ran slower than the 2.5 GB Qwen3-4B-2507 on the spike's desktop run and is
     * labelled Medium rather than the Fast its size reads as.
     */
    private fun ternaryBonsai(id: String, displayName: String, ggufFileName: String, sizeBytes: Long) = LlmModelOption(
        id = id,
        displayName = displayName,
        ggufFileName = ggufFileName,
        sizeBytes = sizeBytes,
        licence = "Apache-2.0",
        multilingual = false,
        idKeyedBatch = true,
        promptMode = TranslationPromptMode.TRANSLATION_ONLY,
        // Both are plain Qwen3 GQA: 36 layers, 8 KV heads, head dim 128.
        kvCacheBytesPerToken = 36L * 2 * 8 * 128 * 2,
        generationDefaults = GenerationParams(temperature = 0.5f, topK = 20, topP = 0.85f),
        experimental = true,
        credit = "Created using Bonsai by Prism ML",
        speed = DeliverableSpeed.MEDIUM
    )

    /**
     * The curated selectable shortlist. Every entry is redistributable and hosted.
     *
     * Gemma 4 is Apache-2.0 and ungated, so it needs none of that and is curated like any other
     * entry. What follows is about Gemma 2/3 and TranslateGemma, which stay out:
     *
     * Gemma / TranslateGemma were the intended gated-download members, but there is no usable
     * licence-clean gated GGUF for them: the official gated repo ships only a 10.5 GB f32 file, and
     * the small Q4_K_M quants live only on *ungated* public re-hosts, where the gate — hence the
     * whole "user accepts the licence under their own account" premise — cannot apply. So they are
     * dropped rather than offered through a gate that does not exist, and the HF-auth mechanism was
     * deleted (#187; ADR-0014 names the commit to revive it from). The open "Custom — unsupported"
     * slot (ADR-0001) is a separate hatch.
     */
    private val CURATED: List<LlmModelOption> = listOf(
        DEFAULT,
        LlmModelOption(
            id = Constants.CAT_TRANSLATION_MODEL_ID,
            displayName = "CAT-Translate 0.8B",
            ggufFileName = Constants.TRANSLATION_MODEL_4BIT,
            sizeBytes = Constants.TRANSLATION_MODEL_4BIT_SIZE,
            licence = "MIT",
            multilingual = false,
            idKeyedBatch = false,
            promptMode = TranslationPromptMode.MODEL_CARD,
            kvCacheBytesPerToken = 24L * 2 * 8 * 80 * 2,
            generationDefaults = GenerationParams()
        ),
        LlmModelOption(
            id = Constants.CAT_TRANSLATION_14B_MODEL_ID,
            displayName = "CAT-Translate 1.4B",
            ggufFileName = Constants.CAT_TRANSLATION_14B_MODEL,
            sizeBytes = Constants.CAT_TRANSLATION_14B_SIZE,
            licence = "MIT",
            multilingual = false,
            idKeyedBatch = true,
            promptMode = TranslationPromptMode.MODEL_CARD,
            kvCacheBytesPerToken = 24L * 2 * 8 * 112 * 2,
            generationDefaults = GenerationParams()
        ),
        LlmModelOption(
            id = Constants.QWEN35_08B_MODEL_ID,
            displayName = "Qwen3.5 0.8B",
            ggufFileName = Constants.QWEN35_08B_MODEL,
            sizeBytes = Constants.QWEN35_08B_SIZE,
            licence = "Apache-2.0",
            // Every Qwen3.5 card gives only "201 languages"; the family's launch post
            // (qwen.ai/blog?id=qwen3.5, "Multilingual Support") names all six.
            multilingual = true,
            idKeyedBatch = true,
            promptMode = TranslationPromptMode.TRANSLATION_ONLY,
            // 6 of its 24 layers hold a KV cache; the 18 DeltaNet layers keep a fixed ~19 MiB state instead.
            kvCacheBytesPerToken = 6L * 2 * 2 * 256 * 2,
            generationDefaults = GenerationParams(temperature = 1.0f, topK = 20, topP = 1.0f, penaltyPresent = 2.0f),
            experimental = true
        ),
        LlmModelOption(
            id = Constants.QWEN35_2B_MODEL_ID,
            displayName = "Qwen3.5 2B",
            ggufFileName = Constants.QWEN35_2B_MODEL,
            sizeBytes = Constants.QWEN35_2B_SIZE,
            licence = "Apache-2.0",
            multilingual = true,
            idKeyedBatch = true,
            promptMode = TranslationPromptMode.TRANSLATION_ONLY,
            // Only its 6 full-attention layers hold a KV cache; the 18 DeltaNet layers keep a fixed ~19 MiB state instead.
            kvCacheBytesPerToken = 6L * 2 * 2 * 256 * 2,
            // The card's "Non-thinking mode for text tasks" values, from the research document's
            // sampling table: temperature 1.0, top-p 1.00, top-k 20, presence 2.0, repetition 1.0.
            // The first entry whose sampling differs from the shipped floor, which is what ADR-0017
            // reversed #139 for.
            generationDefaults = GenerationParams(temperature = 1.0f, topK = 20, topP = 1.0f, penaltyPresent = 2.0f),
            experimental = true
        ),
        LlmModelOption(
            id = Constants.QWEN35_4B_MODEL_ID,
            displayName = "Qwen3.5 4B",
            ggufFileName = Constants.QWEN35_4B_MODEL,
            sizeBytes = Constants.QWEN35_4B_SIZE,
            licence = "Apache-2.0",
            multilingual = true,
            idKeyedBatch = true,
            promptMode = TranslationPromptMode.TRANSLATION_ONLY,
            // 8 of its 32 layers hold a KV cache; the DeltaNet layers keep a fixed ~50 MiB state instead.
            kvCacheBytesPerToken = 8L * 2 * 4 * 256 * 2,
            // The card's "Instruct (or non-thinking) mode for general tasks" values, shared with the 9B.
            generationDefaults = GenerationParams(temperature = 0.7f, topK = 20, topP = 0.8f, penaltyPresent = 1.5f),
            experimental = true
        ),
        LlmModelOption(
            id = Constants.QWEN35_9B_MODEL_ID,
            displayName = "Qwen3.5 9B",
            ggufFileName = Constants.QWEN35_9B_MODEL,
            sizeBytes = Constants.QWEN35_9B_SIZE,
            licence = "Apache-2.0",
            multilingual = true,
            idKeyedBatch = true,
            promptMode = TranslationPromptMode.TRANSLATION_ONLY,
            // 8 of its 32 layers hold a KV cache; the DeltaNet layers keep a fixed ~50 MiB state instead.
            // At 6.2 GiB it is the first entry the fit gate keeps off an 8 GB phone (ADR-0017's ~9B ceiling).
            kvCacheBytesPerToken = 8L * 2 * 4 * 256 * 2,
            generationDefaults = GenerationParams(temperature = 0.7f, topK = 20, topP = 0.8f, penaltyPresent = 1.5f),
            experimental = true
        ),
        LlmModelOption(
            id = Constants.QWEN3_4B_2507_MODEL_ID,
            displayName = "Qwen3 4B Instruct 2507",
            ggufFileName = Constants.QWEN3_4B_2507_MODEL,
            sizeBytes = Constants.QWEN3_4B_2507_SIZE,
            licence = "Apache-2.0",
            // The card names no languages; the Qwen3 technical report (arXiv 2505.09388, §A.1.2 and
            // its per-language tables) names all six, with Qwen3-4B rows.
            multilingual = true,
            idKeyedBatch = true,
            promptMode = TranslationPromptMode.TRANSLATION_ONLY,
            // Plain GQA over all 36 layers, so every one holds a KV cache — the heaviest of the Qwen line.
            kvCacheBytesPerToken = 36L * 2 * 8 * 128 * 2,
            // Card and generation_config.json agree. It is non-thinking only, so no presence penalty is asked for.
            generationDefaults = GenerationParams(temperature = 0.7f, topK = 20, topP = 0.8f),
            experimental = true
        ),
        // Ministral 3 Instruct 2512, first-party Q4_K_M (research addendum pins, checked 2026-09-18).
        // Both carry the same template up to the model name inside its default system prompt, so both
        // need MINISTRAL_SYSTEM_MESSAGE: with no system turn the template renders Mistral's Le Chat
        // assistant prompt instead of any translation instruction.
        LlmModelOption(
            id = Constants.MINISTRAL3_3B_MODEL_ID,
            displayName = "Ministral 3 3B Instruct",
            ggufFileName = Constants.MINISTRAL3_3B_MODEL,
            sizeBytes = Constants.MINISTRAL3_3B_SIZE,
            licence = "Apache-2.0",
            // The card names five of the six; Mistral's own language list
            // (docs.mistral.ai/resources/languages) adds Russian for its language models.
            multilingual = true,
            idKeyedBatch = true,
            promptMode = TranslationPromptMode.TRANSLATION_ONLY,
            // 26 layers, 8 KV heads, head dim 128: 106,496 B/token, the heaviest of the sub-4B entries.
            kvCacheBytesPerToken = 26L * 2 * 8 * 128 * 2,
            // The card's only "Recommended Settings" line is temperature below 0.1; every other knob
            // stays on the shipped floor.
            generationDefaults = GenerationParams(temperature = 0.05f),
            experimental = true,
            systemMessage = MINISTRAL_SYSTEM_MESSAGE
        ),
        LlmModelOption(
            id = Constants.MINISTRAL3_8B_MODEL_ID,
            displayName = "Ministral 3 8B Instruct",
            ggufFileName = Constants.MINISTRAL3_8B_MODEL,
            sizeBytes = Constants.MINISTRAL3_8B_SIZE,
            licence = "Apache-2.0",
            multilingual = true,
            idKeyedBatch = true,
            promptMode = TranslationPromptMode.TRANSLATION_ONLY,
            // 34 layers, 8 KV heads, head dim 160 (hidden 5120 / 32 heads). The addendum's shortlist
            // row mistypes the head dim as 128; 174,080 is the figure its fit verdicts were computed
            // from, and it is what keeps this entry off an 8 GiB phone.
            kvCacheBytesPerToken = 34L * 2 * 8 * 160 * 2,
            generationDefaults = GenerationParams(temperature = 0.05f),
            experimental = true,
            systemMessage = MINISTRAL_SYSTEM_MESSAGE
        ),
        LlmModelOption(
            id = Constants.GEMMA4_E2B_MODEL_ID,
            displayName = "Gemma 4 E2B",
            ggufFileName = Constants.GEMMA4_E2B_MODEL,
            sizeBytes = Constants.GEMMA4_E2B_SIZE,
            licence = "Apache-2.0",
            multilingual = false,
            idKeyedBatch = true,
            promptMode = TranslationPromptMode.TRANSLATION_ONLY,
            // KV sharing: only the first 35 − 20 = 15 layers own a KV cache, and of those 12 slide
            // (1 KV head × 256) and 3 are global (× 512). Counting all 35 would gate the model out
            // on RAM it never spends.
            kvCacheBytesPerToken = 12L * 2 * 1 * 256 * 2 + 3L * 2 * 1 * 512 * 2,
            generationDefaults = GEMMA4_SAMPLING,
            experimental = true
        ),
        LlmModelOption(
            id = Constants.GEMMA4_E4B_MODEL_ID,
            displayName = "Gemma 4 E4B",
            ggufFileName = Constants.GEMMA4_E4B_MODEL,
            sizeBytes = Constants.GEMMA4_E4B_SIZE,
            licence = "Apache-2.0",
            multilingual = false,
            idKeyedBatch = true,
            promptMode = TranslationPromptMode.TRANSLATION_ONLY,
            // 42 layers, 18 KV-shared, so 24 own a cache: 20 sliding (2 KV heads × 256) + 4 global (× 512).
            kvCacheBytesPerToken = 20L * 2 * 2 * 256 * 2 + 4L * 2 * 2 * 512 * 2,
            generationDefaults = GEMMA4_SAMPLING,
            experimental = true
        ),
        // Tencent's translation-specialised 1.8B, first-party Q4_K_M (research addendum pin). The
        // only entry outside the brief's model families, and the only one curated per-line: the
        // card documents one source text per user turn and says nothing about emitting an id-keyed
        // multi-segment reply, so it stays off the page-batch path until a run shows it holds the
        // grammar.
        LlmModelOption(
            id = Constants.HY_MT2_18B_MODEL_ID,
            displayName = "Hy-MT2 1.8B",
            ggufFileName = Constants.HY_MT2_18B_MODEL,
            sizeBytes = Constants.HY_MT2_18B_SIZE,
            licence = "Apache-2.0",
            multilingual = true,
            idKeyedBatch = false,
            promptMode = TranslationPromptMode.HY_MT2,
            // 32 layers, 4 KV heads, head dim 128 — config.json and the GGUF header agree
            // (block_count 32, attention.head_count_kv 4, key_length = value_length = 128). That is
            // 65,536 B/token. The research addendum "corrects" this to 131,072 off the same
            // multiplication, and #288 repeats it; the arithmetic, not the correction, is right, and
            // the shortlist row's original 2.09 GiB at 4096 tokens is the fit this gives.
            kvCacheBytesPerToken = 32L * 2 * 4 * 128 * 2,
            // The card's values for the 1.8B and 7B. Its generation_config.json and the GGUF header
            // both say top_p 0.8; the card wins, because the card is also where the prompt comes from.
            generationDefaults = GenerationParams(temperature = 0.7f, topK = 20, topP = 0.6f, penaltyRepeat = 1.05f),
            experimental = true
        ),
        ternaryBonsai(Constants.TERNARY_BONSAI_4B_MODEL_ID, "Ternary-Bonsai 4B", Constants.TERNARY_BONSAI_4B_MODEL, Constants.TERNARY_BONSAI_4B_SIZE),
        ternaryBonsai(Constants.TERNARY_BONSAI_8B_MODEL_ID, "Ternary-Bonsai 8B", Constants.TERNARY_BONSAI_8B_MODEL, Constants.TERNARY_BONSAI_8B_SIZE)
    )

    /**
     * A huihui-ai abliteration of the curated [baseId], for readers of mature manga (#289). Abliteration
     * edits the weights only, so the architecture, chat template and card sampling are the base's and
     * the entry runs exactly as its base does. The cards call the method "crude, proof-of-concept" and
     * report no translation quality, hence Experimental like every base it copies.
     */
    private fun uncensored(baseId: String, id: String, ggufFileName: String, sizeBytes: Long): LlmModelOption {
        val base = CURATED.single { it.id == baseId }
        return base.copy(id = id, displayName = "${base.displayName} Uncensored", ggufFileName = ggufFileName, sizeBytes = sizeBytes)
    }

    val ALL: List<LlmModelOption> = CURATED + listOf(
        uncensored(Constants.QWEN35_2B_MODEL_ID, Constants.QWEN35_2B_UNCENSORED_MODEL_ID, Constants.QWEN35_2B_UNCENSORED_MODEL, Constants.QWEN35_2B_UNCENSORED_SIZE),
        uncensored(Constants.QWEN35_4B_MODEL_ID, Constants.QWEN35_4B_UNCENSORED_MODEL_ID, Constants.QWEN35_4B_UNCENSORED_MODEL, Constants.QWEN35_4B_UNCENSORED_SIZE),
        uncensored(Constants.QWEN35_9B_MODEL_ID, Constants.QWEN35_9B_UNCENSORED_MODEL_ID, Constants.QWEN35_9B_UNCENSORED_MODEL, Constants.QWEN35_9B_UNCENSORED_SIZE),
        uncensored(Constants.GEMMA4_E2B_MODEL_ID, Constants.GEMMA4_E2B_UNCENSORED_MODEL_ID, Constants.GEMMA4_E2B_UNCENSORED_MODEL, Constants.GEMMA4_E2B_UNCENSORED_SIZE),
        uncensored(Constants.GEMMA4_E4B_MODEL_ID, Constants.GEMMA4_E4B_UNCENSORED_MODEL_ID, Constants.GEMMA4_E4B_UNCENSORED_MODEL, Constants.GEMMA4_E4B_UNCENSORED_SIZE)
    )

    fun fromId(id: String?): LlmModelOption? = id?.let { key -> ALL.firstOrNull { it.id == key } }

    /** The selected option, or the default when nothing (or an unknown id) is persisted. */
    fun selectedOrDefault(id: String?): LlmModelOption = fromId(id) ?: DEFAULT

    fun profileFor(
        option: LlmModelOption,
        modelsDir: File,
        generation: GenerationParams,
        runtime: RuntimeLimits,
        budget: FitBudget
    ): ModelProfile =
        ModelProfile(
            modelPath = File(modelsDir, option.ggufFileName).absolutePath,
            idKeyedBatch = option.idKeyedBatch,
            promptMode = option.promptMode,
            // The option's maker sampling with the reader's global Advanced settings over it
            // (ADR-0017, reversing #139's "no per-model values before a measurement"); resolved by
            // the caller, which is the layer that owns the store.
            generation = generation,
            runtime = runtime,
            systemMessage = option.systemMessage,
            repackWeights = repacksWeights(option, budget),
            multilingual = option.multilingual
        )

    // The repacked copy is anonymous memory lmkd counts against the app, so repack only when a second
    // copy of the weights fits (#319). Capped at the shipped percent: raising the budget shows more
    // entries, but the S23 kills that measured this threshold do not move with it.
    private fun repacksWeights(option: LlmModelOption, budget: FitBudget): Boolean {
        if (budget.totalMemBytes <= 0L) return true
        val capped = budget.copy(percent = minOf(budget.percent, DEFAULT_FIT_BUDGET_PERCENT))
        return neededBytes(option, capped) + option.sizeBytes <= budgetBytes(capped)
    }

    /**
     * Whether [option] — weights, resident overhead and its KV cache at the budget's context size — fits inside
     * [budget] (part D, #79). The default is never gated out — it must stay usable on the mid-range floor.
     */
    fun canRunOnDevice(option: LlmModelOption, budget: FitBudget): Boolean {
        if (option.id == DEFAULT.id) return true
        return neededBytes(option, budget) <= budgetBytes(budget)
    }

    private fun neededBytes(option: LlmModelOption, budget: FitBudget): Long =
        option.sizeBytes + RESIDENT_OVERHEAD_BYTES + option.kvCacheBytesPerToken * budget.contextTokens

    private fun budgetBytes(budget: FitBudget): Long = budget.totalMemBytes / 100 * budget.percent
}

package com.yomu.core

sealed class TranslationStatus {
    data object NotReady : TranslationStatus()
    data object Downloading : TranslationStatus()
    data object Ready : TranslationStatus()
    data class Error(val reason: String) : TranslationStatus()
}

enum class TranslationPromptMode { MODEL_CARD, TRANSLATION_ONLY, HY_MT2 }

data class TranslatableBubble(val bubbleId: Int, val sourceText: String)

data class TranslatablePage(
    val panels: List<List<TranslatableBubble>>,
    val targetLanguage: TargetLanguage = TargetLanguage.ENGLISH,
    val sourceLanguage: SourceLanguage = SourceLanguage.JAPANESE
)

/**
 * Every knob the sampler and the generation loop read, in one place.
 *
 * Before this existed the values were split three ways: temperature crossed the JNI as an
 * argument, top_k and top_p were literals inside `rebuild_sampler`, and the token cap was a
 * private constant in `LlamaTranslationBridge`. ADR-0008 spent a whole cycle blaming model
 * quality for what turned out to be an undocumented `MAX_TOKENS = 64` in that constant pile, so
 * each default below records where its value came from. #139 declined a further ADR on the
 * grounds that this comment is the deliverable that prevents the repeat.
 *
 * Defaults are byte-identical to the behaviour that shipped before they moved here. Changing one
 * is a measurement, not a refactor: #137 owns the control row every candidate value is scored
 * against.
 *
 * The defaults below are the shipped floor; a deliverable may carry its maker's own values instead
 * (`LlmModelOption.generationDefaults`, ADR-0017, which reverses #139's "no per-model values").
 */
data class GenerationParams(
    /** Was `LlamaTranslationBridge.TEMPERATURE`. Low: translation wants the likely token. */
    val temperature: Float = 0.2f,
    /** Was a literal in `rebuild_sampler`. */
    val topK: Int = 40,
    /** Was a literal in `rebuild_sampler`. */
    val topP: Float = 0.9f,
    /**
     * 1.0 disables the repeat penalty. `1.1` was tried and not shipped (#153): it did not make
     * translations cleaner, and it harmed intentional repetition in manga dialogue — laughter and
     * stammers like `ふふっ` and `キヒヒッ` — that a translation must keep. Its only gain was latency,
     * from runaway loops stopping early. Re-open only with a new job for it.
     */
    val penaltyRepeat: Float = 1.0f,
    /**
     * Deliberately shorter than the prompt: a longer window would penalise tokens the model is
     * meant to echo back (the `[id]` tags, names repeated across bubbles). Inert while
     * [penaltyRepeat] is 1.0; kept because it is the window the next candidate would be measured
     * with (#153).
     */
    val penaltyLastN: Int = 64,
    /** No proposed job. Present so a run can try it without another JNI change. */
    val penaltyFreq: Float = 0.0f,
    /** 0.0 disables it. Off for the entries curated before ADR-0017; some makers (Qwen3.5) ask for it. */
    val penaltyPresent: Float = 0.0f,
    /** Was `LlamaTranslationBridge.MAX_TOKENS`. Not a sampler knob; read by the decode loop. */
    val maxTokens: Int = 256,
    /**
     * `LLAMA_DEFAULT_SEED` (0xFFFFFFFF as a uint32), made explicit rather than implied by the
     * C++ default. Kept out of [samplerArray] because a Float cannot hold 0xFFFFFFFF exactly.
     */
    val seed: Int = -1,
    /**
     * Excludes `[` from the batch grammar's `line` rule, making a fake `[id=2]` tag written inside
     * one line unreachable (#214). Without it the model sometimes invents further id tags inside
     * the last line and keeps writing, often in Japanese. Treated as structural under ADR-0013
     * because an id tag is structure, but it does bar a translation like `[laughs]`. Plain run-on
     * past the last id survives it, so the 160-char line bound stays. Not a sampler knob, so not in
     * [samplerArray]; it lives here so the run record's `generation` block carries it and the
     * scorer checks it per arm.
     */
    val lineExcludesIdBracket: Boolean = true
) {
    /**
     * The sampler knobs as a flat array, in [SAMPLER_INDEX] order. C++ reads the same order by
     * index; the two lists must be edited together. `maxTokens` and `seed` are not here — they
     * are not sampler knobs and cross the JNI as their own arguments.
     */
    fun samplerArray(): FloatArray = floatArrayOf(
        temperature,
        topK.toFloat(),
        topP,
        penaltyLastN.toFloat(),
        penaltyRepeat,
        penaltyFreq,
        penaltyPresent
    )

    companion object {
        /** Index order of [samplerArray]. Mirrored by `SamplerIndex` in `llama_jni.cpp`. */
        val SAMPLER_INDEX = listOf(
            "temperature",
            "topK",
            "topP",
            "penaltyLastN",
            "penaltyRepeat",
            "penaltyFreq",
            "penaltyPresent"
        )
    }
}

/**
 * The reader-facing subset of [GenerationParams] (#192, decided in #144): allowed range, slider
 * step and shipped default for each field Settings exposes. The one place these are stated — the
 * Settings panel renders from it, `GenerationProfileStore` validates against it, and the inference
 * boundary re-checks through [withinBounds]. Every other field stays a constant; repeat-penalty in
 * particular stays out of the reader's hands (#153).
 *
 * Values cross as Float so one persistence path serves all fields; [TOP_K] rounds on [write].
 */
enum class GenerationBound(
    val min: Float,
    val max: Float,
    val step: Float,
    val read: (GenerationParams) -> Float,
    val write: (GenerationParams, Float) -> GenerationParams
) {
    TEMPERATURE(0f, 1f, 0.1f, { it.temperature }, { p, v -> p.copy(temperature = v) }),
    TOP_K(1f, 100f, 1f, { it.topK.toFloat() }, { p, v -> p.copy(topK = Math.round(v)) }),
    TOP_P(0f, 1f, 0.05f, { it.topP }, { p, v -> p.copy(topP = v) });

    val default: Float get() = read(GenerationParams())

    fun accepts(value: Float): Boolean = value.isFinite() && value in min..max
}

/** This profile with every reader-facing field outside its [GenerationBound] reset to the default. */
fun GenerationParams.withinBounds(): GenerationParams = GenerationBound.entries.fold(this) { params, bound ->
    if (bound.accepts(bound.read(params))) params else bound.write(params, bound.default)
}

data class ModelProfile(
    val modelPath: String,
    val idKeyedBatch: Boolean,
    val promptMode: TranslationPromptMode,
    val generation: GenerationParams = GenerationParams(),
    val runtime: RuntimeLimits = RuntimeLimits(),
    /** The deliverable's own system turn (#287); empty sends none, which is what every pre-#287 entry does. */
    val systemMessage: String = "",
    /**
     * Whether llama.cpp may repack the weights into CPU_REPACK buffers (#319). Repacked weights are
     * anonymous memory the kernel cannot drop, unlike the mmapped GGUF, so a model repacked on a
     * phone it barely fits is killed by lmkd.
     */
    val repackWeights: Boolean = true,
    /** Whether the deliverable writes every [TargetLanguage]; an English-only one refuses any other (#337). */
    val multilingual: Boolean = false
)

/** How much of the device the loaded model may use (#79). Changing either means reloading the model. */
data class RuntimeLimits(
    val threads: Int = DEFAULT_THREADS,
    val contextTokens: Int = DEFAULT_CONTEXT_TOKENS
) {
    companion object {
        val MAX_THREADS: Int = Runtime.getRuntime().availableProcessors()
        val DEFAULT_THREADS: Int = MAX_THREADS.coerceAtMost(4)
        const val DEFAULT_CONTEXT_TOKENS = 2048
        // A batch page reserves 776 tokens for its reply and llama_jni.cpp caps the prompt at n_batch=2048, so 2816
        // is the largest context a prompt can fill and below 1536 almost every page overflows to per-line.
        val CONTEXT_TOKEN_OPTIONS = listOf(1536, 2048, 2816)
    }
}

/**
 * How a page-level call ended, typed at the boundary that knows the cause.
 *
 * [TIMEOUT] and [OVERFLOW] in particular must be reported by the layer that actually hit the
 * deadline or the decode budget, not inferred later from log or exception text.
 *
 * [TIMEOUT] and [OVERFLOW] are typed at `llama_jni.cpp` (#149) and carried up through
 * `GenerationResult`: an empty reply means the prompt was refused or the deadline hit, never a
 * silent blank.
 */
enum class TranslationOutcome { SUCCESS, BLANK, TIMEOUT, OVERFLOW, ERROR, NOT_LOADED }

/** The slot could not be made ready because its weights are not on disk (#308). */
const val ERROR_MODEL_MISSING = "model_missing"

/** An English-only model was asked for another [TargetLanguage] and refused, rather than write English (#337). */
const val ERROR_ENGLISH_ONLY = "english_only"

data class PageTranslation(
    val byId: Map<Int, String>,
    val rawResponse: String,
    val durationMs: Long,
    val outcome: TranslationOutcome = TranslationOutcome.SUCCESS,
    /** Machine-readable cause, never prose: `load_failed`, `model_missing`, an exception class. */
    val errorCode: String? = null,
    /**
     * The batch prompt overflowed and the page was answered per-line instead. Kept apart from
     * [errorCode] because which call shape ran and what went wrong are independent (#202).
     */
    val batchOverflowFallback: Boolean = false
) {
    companion object {
        /** The empty page every slot returns when it could not be made ready, with its reason. */
        fun notLoaded(status: TranslationStatus): PageTranslation = PageTranslation(
            emptyMap(),
            "",
            0L,
            TranslationOutcome.NOT_LOADED,
            (status as? TranslationStatus.Error)?.reason ?: "not_ready"
        )
    }
}

interface TranslationSlot {
    val status: TranslationStatus

    /** Attempts to prepare the selected model for translation and returns whether the slot is ready. */
    suspend fun ensureReady(): Boolean

    /**
     * [onBubble] hears each bubble at most once, as soon as its text is final, and never with text
     * that contradicts the returned [PageTranslation.byId].
     */
    suspend fun translatePage(
        page: TranslatablePage,
        onBubble: (bubbleId: Int, text: String) -> Unit = { _, _ -> }
    ): PageTranslation

    /** Ends the current reading session so session-specific state can be cleared. */
    fun endSession()
    /** Releases resources held by the translation slot. */
    fun close()
}

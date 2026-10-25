package com.yomu.ml

import android.util.Log
import com.yomu.core.ERROR_ENGLISH_ONLY
import com.yomu.core.ERROR_MODEL_MISSING
import com.yomu.core.ModelProfile
import com.yomu.core.PageTranslation
import com.yomu.core.SourceLanguage
import com.yomu.core.TargetLanguage
import com.yomu.core.TranslatablePage
import com.yomu.core.TranslationOutcome
import com.yomu.core.TranslationPromptMode
import com.yomu.core.TranslationSlot
import com.yomu.core.TranslationStatus
import com.yomu.core.withinBounds
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LlamaTranslationBridge(
    private val llamaBridge: LlamaBridge,
    profile: ModelProfile
) : TranslationSlot {

    @Volatile
    private var profile: ModelProfile = profile

    /**
     * The profile this slot is actually running, read at the execution boundary.
     *
     * The eval records the call shape and generation settings from here rather than from whatever
     * the harness believes it configured: manifest-only call-shape metadata is the provenance
     * mistake ADR-0010 corrected, and #142 requires expected and observed to be independent.
     */
    val activeProfile: ModelProfile get() = profile

    suspend fun selectModel(newProfile: ModelProfile) = readinessMutex.withLock {
        if (newProfile == profile) return@withLock
        if (newProfile.modelPath != profile.modelPath ||
            newProfile.runtime != profile.runtime ||
            newProfile.repackWeights != profile.repackWeights
        ) {
            llamaBridge.release()
            status = TranslationStatus.NotReady
        }
        profile = newProfile
        Log.i(TAG, "selectModel switched idKeyedBatch=${newProfile.idKeyedBatch}")
    }

    companion object {
        private const val TAG = "LlamaTranslationBridge"
        private const val N_GPU_LAYERS = 0
        /**
         * The whole token budget a page's reply may use, fixed rather than derived from what the
         * prompt leaves over. The old "all of the context the prompt does not use" arithmetic was handed
         * to the native `prompt_fits` as `output` and therefore *shrank* the prompt allowance,
         * which is the self-refusal #136 measured. Deliberately separate from
         * `profile.generation.maxTokens`: a page's completion is roughly 8x a bubble's, and one
         * shared field would force every catalog entry to pick a compromise.
         */
        private const val MAX_BATCH_OUTPUT = 768

        /**
         * Longest line the batch grammar will let the model write, a backstop for a line that loops.
         * 160 is ~2x the longest human reference line on the fixture pages. A knob co-owned with the
         * sampler (ADR-0013), not a pinned constant.
         */
        private const val MAX_LINE_CHARS = 160

        private val ID_KEYED_LINE = "^\\s*\\[(\\d+)]\\s*(.*)$".toRegex()

        private const val TIMEOUT_MS = 15_000
        private const val BATCH_TIMEOUT_MS = 120_000
    }

    private val readinessMutex = Mutex()

    @Volatile
    override var status: TranslationStatus = TranslationStatus.NotReady
        private set

    override suspend fun ensureReady(): Boolean = readinessMutex.withLock {
        if (status is TranslationStatus.Ready) return@withLock true
        if (!llamaBridge.isNativeAvailable) {
            status = TranslationStatus.Error("native_unavailable")
            return@withLock false
        }
        if (llamaBridge.isModelLoaded) {
            status = TranslationStatus.Ready
            return@withLock true
        }
        if (!File(profile.modelPath).exists()) {
            status = TranslationStatus.Error(ERROR_MODEL_MISSING)
            return@withLock false
        }
        val runtime = profile.runtime
        val loaded = llamaBridge.loadModel(profile.modelPath, runtime.contextTokens, N_GPU_LAYERS, runtime.threads, profile.repackWeights)
        status = if (loaded) TranslationStatus.Ready else TranslationStatus.Error("load_failed")
        loaded
    }

    /** How long the model took on the most recent page, for the in-app resource readout (#79); null before any. */
    @Volatile
    var lastPageDurationMs: Long? = null
        private set

    /**
     * Prepares the model and translates with its configured call shape, recording the page duration.
     * An English-only profile asked for another language returns an ERROR page without generating.
     * [onBubble] receives each successful per-line result, including overflow fallback, and each finished batch line.
     */
    override suspend fun translatePage(
        page: TranslatablePage,
        onBubble: (bubbleId: Int, text: String) -> Unit
    ): PageTranslation {
        if (page.panels.flatten().isEmpty()) return PageTranslation(emptyMap(), "", 0L)
        if (!profile.multilingual && page.targetLanguage != TargetLanguage.ENGLISH) {
            return PageTranslation(emptyMap(), "", 0L, TranslationOutcome.ERROR, ERROR_ENGLISH_ONLY)
        }
        if (status !is TranslationStatus.Ready && !ensureReady()) {
            return PageTranslation.notLoaded(status)
        }
        val result = if (profile.idKeyedBatch) translateBatch(page, onBubble) else translatePerLine(page, onBubble)
        lastPageDurationMs = result.durationMs
        return result
    }

    /**
     * Generates in panel and bubble order, reporting each successful result before starting the next bubble.
     * The returned page includes successes and records the first failure, if any.
     */
    private suspend fun translatePerLine(
        page: TranslatablePage,
        onBubble: (bubbleId: Int, text: String) -> Unit
    ): PageTranslation {
        val bubbles = page.panels.flatten()
        val generated = bubbles.map { bubble ->
            val prompt = when (profile.promptMode) {
                TranslationPromptMode.MODEL_CARD -> modelCardPrompt(bubble.sourceText, page.sourceLanguage)
                TranslationPromptMode.TRANSLATION_ONLY -> translationOnlyPrompt(bubble.sourceText, page)
                TranslationPromptMode.HY_MT2 -> hyMt2Prompt(bubble.sourceText, page.targetLanguage)
            }
            val output = generate(prompt, profile.generation.maxTokens, TIMEOUT_MS)
            if (output.outcome == TranslationOutcome.SUCCESS) onBubble(bubble.bubbleId, output.text)
            bubble to output
        }
        val outputs = generated.filter { it.second.outcome == TranslationOutcome.SUCCESS }
        // The page's outcome is the first bubble-level failure, or SUCCESS when none failed. A
        // per-line page that answered some bubbles and dropped others is still a measured partial:
        // coverage is what reports it, and the outcome names why (#142).
        val failure = generated.firstOrNull { it.second.outcome != TranslationOutcome.SUCCESS }?.second
        return PageTranslation(
            byId = outputs.associate { (bubble, output) -> bubble.bubbleId to output.text },
            rawResponse = outputs.joinToString("\n") { (bubble, output) ->
                "[${bubble.bubbleId}] ${output.text}"
            },
            // Successes only, unchanged: #137 and #153 published latency rows on this definition,
            // and widening it to include failed generations would break comparability with them.
            durationMs = outputs.sumOf { it.second.durationMs },
            outcome = failure?.outcome ?: TranslationOutcome.SUCCESS,
            errorCode = failure?.errorCode
        )
    }

    /**
     * Generates one ID-keyed batch reply, reporting each finished `[id] text` line to [onBubble] as it
     * is decoded; on prompt overflow, retries per line and streams through [onBubble].
     *
     * The page is the reported lines, read only through the partial-output listener: a shown bubble
     * cannot be taken back, so an id repeated later keeps its first line, and a line the deadline cut
     * off never ends, so it stays unanswered.
     */
    private suspend fun translateBatch(
        page: TranslatablePage,
        onBubble: (bubbleId: Int, text: String) -> Unit
    ): PageTranslation {
        val pageIds = page.panels.flatten().mapTo(mutableSetOf()) { it.bubbleId }
        val byId = linkedMapOf<Int, String>()
        val splitter = ReplyLineSplitter { line ->
            val (id, text) = parseIdKeyedLine(line) ?: return@ReplyLineSplitter
            if (id in pageIds && byId.putIfAbsent(id, text) == null) onBubble(id, text)
        }
        val output = generate(
            buildBatchPrompt(page),
            MAX_BATCH_OUTPUT,
            BATCH_TIMEOUT_MS,
            buildBatchGrammar(page),
            splitter::feed
        )
        if (output.endedByModel) splitter.finish()
        if (output.outcome == TranslationOutcome.OVERFLOW) {
            // Loud, and typed: the old behaviour was an empty PageTranslation and a silently
            // untranslated page (ADR-0013). The page still renders, but it renders through the
            // other call shape, so the degraded run stays readable from the result rather than
            // from a log line (#142/#165) — hence the marker on the result.
            Log.w(
                TAG,
                "translateBatch overflow bubbles=${pageIds.size} " +
                    "falling back to per-line"
            )
            return translatePerLine(page, onBubble).copy(batchOverflowFallback = true)
        }
        return PageTranslation(
            byId = byId,
            rawResponse = output.text,
            durationMs = output.durationMs,
            outcome = output.outcome,
            errorCode = output.errorCode
        )
    }

    /** CAT-Translate's model-card prompt, byte-identical and English only; another source drops "Japanese". */
    private fun modelCardPrompt(target: String, source: SourceLanguage): String =
        "Translate the following ${source.promptPrefix("Japanese ")}text into English.\n\n$target"

    private fun translationOnlyPrompt(target: String, page: TranslatablePage): String = buildString {
        appendLine(
            "Translate the target ${page.sourceLanguage.promptPrefix()}manga text into natural ${page.targetLanguage.promptName}. " +
                "Return only the translation."
        )
        appendLine("No introductions, explanations, labels, or added quotes. Preserve meaning and tone.")
        appendLine("Target text (translate this only):")
        append(target)
    }

    /** Tencent's documented "Default Translation" prompt, verbatim, with the full target language name. */
    private fun hyMt2Prompt(target: String, language: TargetLanguage): String =
        "Translate the following text into ${language.promptName}. Note that you should only output the " +
            "translated result without any additional explanation:\n\n$target"

    private fun buildBatchPrompt(page: TranslatablePage): String = buildString {
        appendLine(
            "Translate each ${page.sourceLanguage.promptPrefix()}line to ${page.targetLanguage.promptName}. Keep the [id] tag before each line. " +
                "Panels are separated by ---."
        )
        page.panels.forEachIndexed { index, panel ->
            if (index > 0) appendLine("---")
            panel.forEach { bubble -> appendLine("[${bubble.bubbleId}] ${bubble.sourceText}") }
        }
    }

    /**
     * GBNF pinning the reply to exactly one `[id] text` line per bubble, in prompt order, with the
     * ids as literals. Two rules regardless of bubble count. Preamble, refusal, a dropped id, a
     * merged pair and a re-ordered reply are all structurally unreachable — which is what
     * [parseIdKeyedLine] and the post-hoc guards were catching after the fact.
     *
     * Structural only: nothing here forbids Japanese characters or constrains content, so a model
     * that fails to translate still shows it rather than having the grammar hide it.
     *
     * The last newline is optional: the model ends its reply with end-of-generation, not a newline,
     * and a grammar still waiting for one masks that token, so the model writes on to the line cap (#368).
     */
    private fun buildBatchGrammar(page: TranslatablePage): String {
        val root = page.panels.flatten().joinToString(" ") { "\"[${it.bubbleId}] \" line \"\\n\"" } + "?"
        val excluded = if (profile.generation.lineExcludesIdBracket) "\\r\\n\\[" else "\\r\\n"
        return "root ::= $root\nline ::= [^$excluded]{1,$MAX_LINE_CHARS}\n"
    }

    private fun parseIdKeyedLine(line: String): Pair<Int, String>? {
        val match = ID_KEYED_LINE.matchEntire(line) ?: return null
        val id = match.groupValues[1].toIntOrNull() ?: return null
        return id to match.groupValues[2].trim()
    }

    /**
     * Generate once, carrying the typed terminal cause out with the text.
     *
     * The `when` is exhaustive on purpose: the outcome the eval records must be mapped from the
     * result type, never recovered from a log line (#142).
     */
    private suspend fun generate(
        prompt: String,
        maxTokens: Int,
        timeoutMs: Int,
        grammar: String = "",
        onPartial: ((ByteArray) -> Unit)? = null
    ): GeneratedText = readinessMutex.withLock {
        // Cancelling the page cannot interrupt the blocking JNI call, so it raises the native abort
        // flag instead (#76). Cleared first: the handler runs at once if the job is already cancelling.
        llamaBridge.setAbortRequested(false)
        @OptIn(InternalCoroutinesApi::class)
        val abortOnCancel = coroutineContext.job.invokeOnCompletion(onCancelling = true) {
            llamaBridge.setAbortRequested(true)
        }
        val result = try {
            // Re-checked here, not trusted from Settings: a caller that bypasses the store must not
            // push an unsafe value into the sampler (#192).
            llamaBridge.generate(
                prompt,
                profile.generation.withinBounds(),
                maxTokens,
                timeoutMs,
                grammar,
                profile.systemMessage,
                onPartial
            )
        } finally {
            abortOnCancel.dispose()
        }
        coroutineContext.ensureActive()
        return@withLock when (result) {
            is GenerationResult.Success -> {
                val text = result.text.trim()
                if (text.isBlank()) {
                    Log.w(TAG, "generate blank promptLength=${prompt.length}")
                    GeneratedText("", result.durationMs, TranslationOutcome.BLANK)
                } else {
                    Log.i(
                        TAG,
                        "generate success promptLength=${prompt.length} translatedLength=${text.length} " +
                            "maxTokens=$maxTokens durationMs=${result.durationMs}"
                    )
                    Log.i(TAG, "generate raw=${text.replace("\n", "\\n")}")
                    GeneratedText(
                        text,
                        result.durationMs,
                        TranslationOutcome.SUCCESS,
                        endedByModel = result.endedByModel
                    )
                }
            }
            is GenerationResult.Blank ->
                GeneratedText("", result.durationMs, TranslationOutcome.BLANK)
            is GenerationResult.Overflow ->
                GeneratedText("", result.durationMs, TranslationOutcome.OVERFLOW, "prompt_overflow")
            is GenerationResult.Timeout ->
                GeneratedText("", result.durationMs, TranslationOutcome.TIMEOUT, "deadline")
            is GenerationResult.Error ->
                GeneratedText("", result.durationMs, TranslationOutcome.ERROR, result.reason)
            is GenerationResult.NotLoaded ->
                GeneratedText("", result.durationMs, TranslationOutcome.NOT_LOADED, "model_not_loaded")
        }
    }

    override fun endSession() {
        llamaBridge.clearMemory()
    }

    override fun close() {
        llamaBridge.release()
        status = TranslationStatus.NotReady
        Log.i(TAG, "close completed")
    }

    private data class GeneratedText(
        val text: String,
        val durationMs: Long,
        val outcome: TranslationOutcome,
        val errorCode: String? = null,
        val endedByModel: Boolean = false
    )
}

/** The source named before a noun in a prompt, "Korean ", or nothing for a script family; [japanese] keeps a prompt's own wording. */
private fun SourceLanguage.promptPrefix(japanese: String = "Japanese "): String = when {
    this == SourceLanguage.JAPANESE -> japanese
    promptName == null -> ""
    else -> "$promptName "
}

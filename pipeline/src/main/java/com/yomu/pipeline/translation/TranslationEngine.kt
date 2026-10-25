package com.yomu.pipeline.translation

import com.yomu.core.ERROR_ENGLISH_ONLY
import com.yomu.core.ERROR_MODEL_MISSING
import com.yomu.core.GlossaryEntry
import com.yomu.core.SourceLanguage
import com.yomu.core.TargetLanguage
import com.yomu.core.TranslatableBubble
import com.yomu.core.TranslatablePage
import com.yomu.core.TranslationOutcome
import com.yomu.core.TranslationSlot
import com.yomu.core.applyTo
import com.yomu.core.hasHanOrKana
import com.yomu.pipeline.context.ConversationBlock

private val NON_TRANSLATION_PATTERNS = listOf(
    Regex("""(?i)^\s*(?:sure[!,.]?\s*)?(?:here(?:['’]s| is| are)\s+(?:the |an? )?(?:english )?translations?\b|(?:english )?translation\s*:)"""),
    Regex("""(?i)translate the following|translate these japanese|reply with the translation|one per line, numbered"""),
    Regex("""(?i)\bas an ai\b"""),
    Regex("""(?i)\bi\s*['’]?m unable to\b"""),
    Regex("""(?i)\bi\s+(?:can['’]?t|cannot|can not|will not|won['’]?t)\s+(?:help|assist|translate|provide|comply|fulfill|do that|with that)"""),
    Regex("""(?i)\b(?:please provide|provide me with)\b.{0,80}\b(?:japanese|manga|target|complete)\s+(?:manga\s+)?text\b"""),
    Regex("""(?i)^\s*the (?:(?:english )?translation of (?:the )?(?:given )?japanese text\b|japanese text\b.{0,150}\btranslates? to\b)""")
)

private val TOKEN_SPLIT = Regex("""\s+""")
private const val MIN_LOOP_TOKENS = 8
private const val LOOP_UNIQUE_DIVISOR = 4

internal fun looksLikeNonTranslation(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    if (NON_TRANSLATION_PATTERNS.any { it.containsMatchIn(trimmed) }) return true
    val tokens = trimmed.split(TOKEN_SPLIT).filter { it.isNotBlank() }
    if (tokens.size < MIN_LOOP_TOKENS) return false
    return tokens.map { it.lowercase() }.toSet().size * LOOP_UNIQUE_DIVISOR <= tokens.size
}

/**
 * Why [text] cannot be a live translation out of [source], or null when it can: empty, a
 * non-translation, or residue of the source's own script. It is a shape check, not a quality bar.
 */
fun deadTranslationReason(text: String?, source: SourceLanguage = SourceLanguage.JAPANESE): String? = when {
    text.isNullOrBlank() -> "empty"
    looksLikeNonTranslation(text) -> "non-translation"
    source.leftUntranslated(text) -> if (source == SourceLanguage.JAPANESE) "japanese residue" else "source residue"
    else -> null
}

/** Whether the text has any kana or kanji, which reader-app labels, dates and a lone "!?" never do. */
internal fun String.hasJapaneseScript(): Boolean = hasHanOrKana()

private fun String?.usableTranslation(source: SourceLanguage): String? = this?.takeIf { deadTranslationReason(it, source) == null }

/**
 * [originalText] is the bubble's OCR text as read; [sourceText] is what the model was given, which
 * differs only where the name glossary replaced a term. [answered] is false when the model gave this
 * bubble nothing usable; [translatedText] is then its OCR text.
 */
data class TranslatedBubble(
    val bubbleId: Int,
    val originalText: String,
    val translatedText: String,
    val answered: Boolean,
    val sourceText: String = originalText
)

data class TranslationResult(
    val translations: List<TranslatedBubble>,
    val rawResponse: String,
    val translationTimeMs: Long,
    val outcome: TranslationOutcome = TranslationOutcome.SUCCESS,
    val errorCode: String? = null,
    val batchOverflowFallback: Boolean = false,
    /** The language the page was asked for, which is what history records. */
    val targetLanguage: TargetLanguage = TargetLanguage.ENGLISH
)

/**
 * What to tell the reader when the page came back untranslated because the slot could not load or
 * its English-only model refused the target language, or null when the model ran. Such a page still
 * renders every bubble's source text, which reads as a capture that silently did nothing (#308) —
 * the caller shows this instead.
 */
fun TranslationResult.readerFailure(): String? = when {
    errorCode == ERROR_ENGLISH_ONLY -> "This model is English only — pick a Multilingual model or English in Settings"
    outcome != TranslationOutcome.NOT_LOADED -> null
    errorCode == ERROR_MODEL_MISSING -> "Translation model is missing — download it again in Settings"
    else -> "Translation model could not be loaded (${errorCode ?: "not_ready"})"
}

fun TranslationResult.untranslatedNotice(): String? {
    val unanswered = translations.count { !it.answered }
    return if (unanswered == 0) null else "$unanswered of ${translations.size} bubbles not translated"
}

class TranslationEngine(
    private val slotProvider: () -> TranslationSlot,
    private val closeSlots: () -> Unit
) {

    constructor(slotProvider: () -> TranslationSlot) : this(
        slotProvider,
        { slotProvider().close() }
    )

    companion object {
        private const val MAX_SOURCE_CHARS = 300
    }

    /**
     * [onBubble] hears every answered bubble exactly once: a reported bubble as it arrives, once it
     * passes the same check as the final result, and the rest when the page ends. The returned
     * page's answered bubbles are exactly the forwarded ones. The model reads each bubble's OCR text
     * with [glossary] applied.
     */
    suspend fun translate(
        blocks: List<ConversationBlock>,
        targetLanguage: TargetLanguage = TargetLanguage.ENGLISH,
        glossary: List<GlossaryEntry> = emptyList(),
        sourceLanguage: SourceLanguage = SourceLanguage.JAPANESE,
        onBubble: (TranslatedBubble) -> Unit = {}
    ): TranslationResult {
        val ocrById = blocks.flatMap { it.textByBubbleId.entries }.associate { (id, read) -> id to read.text.take(MAX_SOURCE_CHARS) }
        val page = project(ocrById, blocks, targetLanguage, glossary, sourceLanguage)
        val bubbles = page.panels.flatten()
        if (bubbles.isEmpty()) return TranslationResult(emptyList(), "", 0L, targetLanguage = targetLanguage)

        val bubbleById = bubbles.associateBy { it.bubbleId }
        fun translated(bubble: TranslatableBubble, text: String?): TranslatedBubble {
            val ocrText = ocrById.getValue(bubble.bubbleId)
            return TranslatedBubble(bubble.bubbleId, ocrText, text ?: ocrText, answered = text != null, sourceText = bubble.sourceText)
        }
        val forwarded = mutableMapOf<Int, String>()
        val output = slotProvider().translatePage(page) { bubbleId, text ->
            val bubble = bubbleById[bubbleId] ?: return@translatePage
            val usable = text.usableTranslation(sourceLanguage) ?: return@translatePage
            if (forwarded.putIfAbsent(bubbleId, usable) == null) onBubble(translated(bubble, usable))
        }
        val translations = bubbles.map { bubble ->
            translated(bubble, forwarded[bubble.bubbleId] ?: output.byId[bubble.bubbleId].usableTranslation(sourceLanguage))
        }
        translations.filter { it.answered && it.bubbleId !in forwarded }.forEach(onBubble)
        return TranslationResult(
            translations = translations,
            rawResponse = output.rawResponse,
            translationTimeMs = output.durationMs,
            outcome = output.outcome,
            errorCode = output.errorCode,
            batchOverflowFallback = output.batchOverflowFallback,
            targetLanguage = targetLanguage
        )
    }

    /** Every prompt shape reads the page from here, so the glossary is applied once and no template changes. */
    private fun project(
        ocrById: Map<Int, String>,
        blocks: List<ConversationBlock>,
        targetLanguage: TargetLanguage,
        glossary: List<GlossaryEntry>,
        sourceLanguage: SourceLanguage
    ): TranslatablePage = TranslatablePage(
        blocks.map { block ->
            block.readingOrder.mapNotNull { bubbleId ->
                ocrById[bubbleId]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { TranslatableBubble(bubbleId, glossary.applyTo(it)) }
            }
        }.filter { it.isNotEmpty() },
        targetLanguage,
        sourceLanguage
    )

    fun endSession() {
        slotProvider().endSession()
    }

    fun close() {
        closeSlots()
    }
}

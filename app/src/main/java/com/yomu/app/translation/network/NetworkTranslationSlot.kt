package com.yomu.app.translation.network

import com.yomu.core.PageTranslation
import com.yomu.core.TranslatablePage
import com.yomu.core.TranslationOutcome
import com.yomu.core.TranslationSlot
import com.yomu.core.TranslationStatus
import com.yomu.ml.batchPrompt
import com.yomu.ml.parseIdKeyedLine
import javax.inject.Inject
import javax.inject.Singleton

const val ERROR_NETWORK_NOT_CONFIGURED = "network_not_configured"

/**
 * Translates a page on a server instead of the phone: the whole page in one chat request, answered as `[id] text`
 * lines like the on-device batch, read as the reply streams. The server is not grammar-constrained, so a reply that
 * skips or garbles an id is followed by one more request for those ids alone; what that misses stays unanswered.
 */
@Singleton
class NetworkTranslationSlot @Inject constructor(
    private val client: NetworkModelClient,
    private val store: NetworkModelStore
) : TranslationSlot {

    override val status: TranslationStatus
        get() = if (store.config().complete) TranslationStatus.Ready else TranslationStatus.Error(ERROR_NETWORK_NOT_CONFIGURED)

    override suspend fun ensureReady(): Boolean = status is TranslationStatus.Ready

    override suspend fun translatePage(page: TranslatablePage, onBubble: (bubbleId: Int, text: String) -> Unit): PageTranslation {
        val bubbles = page.panels.flatten()
        val ids = bubbles.mapTo(mutableSetOf()) { it.bubbleId }
        if (ids.isEmpty()) return PageTranslation(emptyMap(), "", 0L)
        if (!ensureReady()) return PageTranslation.notLoaded(status)
        val startedAt = System.currentTimeMillis()
        val config = store.config()
        val byId = linkedMapOf<Int, String>()
        val raw = StringBuilder()

        /** One request for [asked]; each line is reported as soon as it is whole, so bubbles appear while the rest is written. */
        suspend fun ask(asked: TranslatablePage): ChatResult {
            val lines = LineBuffer { line ->
                parseIdKeyedLine(line)?.let { (id, text) -> if (id in ids && byId.putIfAbsent(id, text) == null) onBubble(id, text) }
            }
            val result = client.chat(config, SYSTEM_MESSAGE, asked.batchPrompt(), TEMPERATURE, lines::feed)
            if (result is ChatResult.Success) {
                lines.finish()
                raw.appendLine(result.text)
            }
            return result
        }

        val first = ask(page)
        if (first is ChatResult.Failed) {
            // What was already heard stays: it was reported, so the result has to agree with it.
            val outcome = if (first.code == NETWORK_TIMEOUT) TranslationOutcome.TIMEOUT else TranslationOutcome.ERROR
            return PageTranslation(byId, raw.toString().trim(), System.currentTimeMillis() - startedAt, outcome, first.code)
        }
        // A reply that skipped some ids but understood the format is asked once more for just those, as lines of their own.
        // One try only: what that misses stays unanswered, and a failed retry keeps what the first reply gave.
        val missing = bubbles.filter { it.bubbleId !in byId }
        if (byId.isNotEmpty() && missing.isNotEmpty()) ask(page.copy(panels = listOf(missing)))
        val outcome = if (byId.isEmpty()) TranslationOutcome.BLANK else TranslationOutcome.SUCCESS
        return PageTranslation(byId, raw.toString().trim(), System.currentTimeMillis() - startedAt, outcome)
    }

    override fun endSession() = Unit

    override fun close() = Unit

    private companion object {
        const val TEMPERATURE = 0.2f
        const val SYSTEM_MESSAGE = "You are a translator of manga, comics and visual novels. Translate every line faithfully " +
            "into the requested language. Reply with the translated lines only, each keeping its [id] tag, and nothing else."
    }
}

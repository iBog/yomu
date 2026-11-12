package com.yomu.app.translation.network

import com.yomu.core.PageTranslation
import com.yomu.core.TranslatablePage
import com.yomu.core.TranslationOutcome
import com.yomu.core.TranslationSlot
import com.yomu.core.TranslationStatus
import com.yomu.ml.batchPrompt
import com.yomu.ml.parseIdKeyedReply
import javax.inject.Inject
import javax.inject.Singleton

const val ERROR_NETWORK_NOT_CONFIGURED = "network_not_configured"

/**
 * Translates a page on a server instead of the phone: the whole page in one chat request, answered as `[id] text`
 * lines like the on-device batch. The server is not grammar-constrained, so a reply that skips or garbles an id
 * leaves that bubble unanswered.
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
        val ids = page.panels.flatten().mapTo(mutableSetOf()) { it.bubbleId }
        if (ids.isEmpty()) return PageTranslation(emptyMap(), "", 0L)
        if (!ensureReady()) return PageTranslation.notLoaded(status)
        val startedAt = System.currentTimeMillis()
        val result = client.chat(store.config(), SYSTEM_MESSAGE, page.batchPrompt(), TEMPERATURE)
        val elapsed = System.currentTimeMillis() - startedAt
        return when (result) {
            is ChatResult.Failed -> PageTranslation(
                emptyMap(), "", elapsed,
                if (result.code == NETWORK_TIMEOUT) TranslationOutcome.TIMEOUT else TranslationOutcome.ERROR,
                result.code
            )
            is ChatResult.Success -> {
                val byId = parseIdKeyedReply(result.text, ids)
                byId.forEach { (id, text) -> onBubble(id, text) }
                PageTranslation(byId, result.text, elapsed, if (byId.isEmpty()) TranslationOutcome.BLANK else TranslationOutcome.SUCCESS)
            }
        }
    }

    override fun endSession() = Unit

    override fun close() = Unit

    private companion object {
        const val TEMPERATURE = 0.2f
        const val SYSTEM_MESSAGE = "You are a translator of manga, comics and visual novels. Translate every line faithfully " +
            "into the requested language. Reply with the translated lines only, each keeping its [id] tag, and nothing else."
    }
}

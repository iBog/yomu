package com.yomu.app.translation.network

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okio.Buffer
import okio.BufferedSource

/** How a chat reply is laid out: server-sent events (OpenAI style), Ollama's newline-delimited JSON, or one JSON body. */
internal enum class ReplyFormat { SSE, NDJSON, JSON }

internal fun replyFormat(contentType: String?, ollama: Boolean): ReplyFormat = when {
    ollama || contentType.orEmpty().contains("ndjson") -> ReplyFormat.NDJSON
    contentType.orEmpty().contains("event-stream") -> ReplyFormat.SSE
    else -> ReplyFormat.JSON
}

/**
 * Reads a chat reply from [source], handing each piece of visible text to [onDelta] as it is read, and returns the whole
 * text. A reasoning model's `<think>` block is left out. A server that ignored `stream` and sent one JSON body is read
 * the same way, as one piece.
 */
internal fun readChatStream(source: BufferedSource, format: ReplyFormat, onDelta: (String) -> Unit): ChatResult {
    val stripper = ThinkStripper()
    val text = StringBuilder()
    fun emit(raw: String?) {
        val visible = stripper.feed(raw.orEmpty())
        if (visible.isNotEmpty()) {
            text.append(visible)
            onDelta(visible)
        }
    }
    when (format) {
        ReplyFormat.JSON -> {
            val body = source.readUtf8()
            // A server that streams but says it is JSON.
            if (body.trimStart().startsWith("data:")) return readChatStream(Buffer().writeUtf8(body), ReplyFormat.SSE, onDelta)
            emit(wholeReplyText(body) ?: return ChatResult.Failed(NETWORK_BAD_REPLY))
        }
        ReplyFormat.SSE -> while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") break
            val chunk = jsonObject(payload) ?: continue
            if (chunk.has("error")) return ChatResult.Failed(NETWORK_BAD_REPLY)
            emit(chunk.path("choices")?.takeIf { it.isJsonArray && it.asJsonArray.size() > 0 }?.asJsonArray?.get(0)
                ?.asJsonObject?.path("delta")?.asJsonObject?.path("content")?.asStringOrNull())
        }
        ReplyFormat.NDJSON -> while (true) {
            val line = source.readUtf8Line() ?: break
            val chunk = jsonObject(line) ?: continue
            if (chunk.has("error")) return ChatResult.Failed(NETWORK_BAD_REPLY)
            emit(chunk.path("message")?.asJsonObject?.path("content")?.asStringOrNull())
            if (chunk.path("done")?.asBooleanOrNull() == true) break
        }
    }
    stripper.finish().takeIf { it.isNotEmpty() }?.let {
        text.append(it)
        onDelta(it)
    }
    return ChatResult.Success(text.toString().trim())
}

/** The text of a non-streamed reply: `choices[0].message.content`, or Ollama's `message.content`. */
private fun wholeReplyText(body: String): String? = jsonObject(body)?.let { root ->
    root.path("choices")?.takeIf { it.isJsonArray && it.asJsonArray.size() > 0 }?.asJsonArray?.get(0)?.asJsonObject
        ?.path("message")?.asJsonObject?.path("content")?.asStringOrNull()
        ?: root.path("message")?.asJsonObject?.path("content")?.asStringOrNull()
}

private fun jsonObject(text: String): JsonObject? = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()

private fun JsonObject.path(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }

private fun JsonElement.asStringOrNull(): String? = runCatching { asString }.getOrNull()

private fun JsonElement.asBooleanOrNull(): Boolean? = runCatching { asBoolean }.getOrNull()

package com.yomu.app.translation.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where a network model lives: [url] of the server, an optional [apiKey], and the [model] it is asked for. */
data class NetworkConfig(val url: String, val apiKey: String, val model: String) {
    val complete: Boolean get() = url.serverBase() != null && model.isNotBlank()
}

/** The server root, without a trailing slash or `/v1`, or null unless it is an http(s) URL with a host. */
fun String.serverBase(): String? {
    val url = trim().toHttpUrlOrNull() ?: return null
    val path = url.encodedPath.trimEnd('/').removeSuffix("/v1")
    return "${url.scheme}://${url.host}${if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"}$path"
}

/** What asking a server for its models came to; [Unlisted] means it answered but offers no list. */
sealed interface ModelsResult {
    data class Listed(val models: List<String>) : ModelsResult
    data object Unlisted : ModelsResult
    data class Failed(val code: String) : ModelsResult
}

sealed interface ChatResult {
    data class Success(val text: String) : ChatResult
    data class Failed(val code: String) : ChatResult
}

const val NETWORK_UNREACHABLE = "network_unreachable"
const val NETWORK_UNAUTHORIZED = "network_unauthorized"
const val NETWORK_TIMEOUT = "network_timeout"
const val NETWORK_BAD_URL = "network_bad_url"
const val NETWORK_BAD_REPLY = "network_bad_reply"

/**
 * Talks to an OpenAI-compatible server: Ollama, llama.cpp's server, or a hosted API. Models come from `/v1/models`
 * (Ollama's `/api/tags` as a second try); a page is translated through `/v1/chat/completions`.
 */
@Singleton
class NetworkModelClient @Inject constructor(http: OkHttpClient) {
    // A model on a home server can take minutes for a page; the shared client's 30 s would cut it off.
    private val http = http.newBuilder().readTimeout(CHAT_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    private class Reply(val code: Int, val body: String)

    suspend fun listModels(url: String, apiKey: String): ModelsResult {
        val base = url.serverBase() ?: return ModelsResult.Failed(NETWORK_BAD_URL)
        var answered = false
        for (path in MODEL_PATHS) {
            val reply = try {
                send(request(base + path, apiKey).get().build())
            } catch (e: IOException) {
                return ModelsResult.Failed(failureOf(e))
            }
            answered = true
            if (reply.code == 401 || reply.code == 403) return ModelsResult.Failed(NETWORK_UNAUTHORIZED)
            val models = if (reply.code == 200) modelNames(reply.body) else emptyList()
            if (models.isNotEmpty()) return ModelsResult.Listed(models)
        }
        return if (answered) ModelsResult.Unlisted else ModelsResult.Failed(NETWORK_UNREACHABLE)
    }

    suspend fun chat(config: NetworkConfig, system: String, user: String, temperature: Float): ChatResult {
        val base = config.url.serverBase() ?: return ChatResult.Failed(NETWORK_BAD_URL)
        val body = JsonObject().apply {
            addProperty("model", config.model)
            add("messages", JsonArray().apply {
                add(message("system", system))
                add(message("user", user))
            })
            addProperty("temperature", temperature)
            addProperty("max_tokens", MAX_TOKENS)
            addProperty("stream", false)
        }.toString().toRequestBody(JSON)
        val reply = try {
            send(request("$base/v1/chat/completions", config.apiKey).post(body).build())
        } catch (e: IOException) {
            return ChatResult.Failed(failureOf(e))
        }
        return when {
            reply.code == 401 || reply.code == 403 -> ChatResult.Failed(NETWORK_UNAUTHORIZED)
            reply.code != 200 -> ChatResult.Failed("network_http_${reply.code}")
            else -> replyText(reply.body)?.let(ChatResult::Success) ?: ChatResult.Failed(NETWORK_BAD_REPLY)
        }
    }

    private fun request(url: String, apiKey: String): Request.Builder = Request.Builder().url(url).apply {
        if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}")
    }

    private fun message(role: String, content: String) = JsonObject().apply {
        addProperty("role", role)
        addProperty("content", content)
    }

    private fun failureOf(e: IOException): String = if (e is SocketTimeoutException) NETWORK_TIMEOUT else NETWORK_UNREACHABLE

    /** Cancelling the coroutine cancels the call, so a cancelled page stops waiting on the server. */
    private suspend fun send(request: Request): Reply = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { continuation.resume(Reply(it.code, runCatching { it.body?.string() }.getOrNull().orEmpty())) }
            }
        })
    }

    /** Model names from OpenAI's `data[].id` or Ollama's `models[].name`; anything else is no list. */
    private fun modelNames(body: String): List<String> = runCatching {
        val root = JsonParser.parseString(body).asJsonObject
        val items = root.getAsJsonArray("data") ?: root.getAsJsonArray("models") ?: return emptyList()
        items.mapNotNull { item ->
            val obj = item.asJsonObject
            (obj.get("id") ?: obj.get("name") ?: obj.get("model"))?.asString?.takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList())

    private fun replyText(body: String): String? = runCatching {
        JsonParser.parseString(body).asJsonObject.getAsJsonArray("choices")[0].asJsonObject
            .getAsJsonObject("message").get("content").asString
    }.getOrNull()?.replace(THINK_BLOCK, "")?.trim()

    private companion object {
        val MODEL_PATHS = listOf("/v1/models", "/api/tags")
        val JSON = "application/json".toMediaType()
        val THINK_BLOCK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
        const val CHAT_TIMEOUT_SECONDS = 180L
        const val MAX_TOKENS = 2048
    }
}

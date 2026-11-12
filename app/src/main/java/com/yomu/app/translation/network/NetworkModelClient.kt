package com.yomu.app.translation.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Where a network model lives: [url] of the server, an optional [apiKey], and the [model] it is asked for. Ollama
 * unloads a model [keepAliveMinutes] after its last request unless told otherwise, so the next page would wait for it to load.
 */
data class NetworkConfig(val url: String, val apiKey: String, val model: String, val keepAliveMinutes: Int = DEFAULT_KEEP_ALIVE_MINUTES) {
    val complete: Boolean get() = url.serverBase() != null && model.isNotBlank()
}

const val DEFAULT_KEEP_ALIVE_MINUTES = 30

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
 * (Ollama's `/api/tags` as a second try). A page is translated through `/v1/chat/completions` and its reply read as it
 * streams. An Ollama server is told apart once, and asked through its own `/api/chat` instead, which takes `keep_alive`
 * in the same request, so no other server is sent anything it does not know.
 */
@Singleton
class NetworkModelClient @Inject constructor(http: OkHttpClient) {
    // A model on a home server can take minutes for a page; the shared client's 30 s would cut off a silent wait.
    private val http = http.newBuilder().readTimeout(CHAT_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()

    private class Reply(val code: Int, val body: String)

    private val ollamaServers = ConcurrentHashMap<String, Boolean>()

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
            if (models.isNotEmpty()) return ModelsResult.Listed(models.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER))
        }
        return if (answered) ModelsResult.Unlisted else ModelsResult.Failed(NETWORK_UNREACHABLE)
    }

    /** Asks for one reply and hands each visible piece of it to [onDelta] as it arrives, on the calling coroutine's thread. */
    suspend fun chat(config: NetworkConfig, system: String, user: String, temperature: Float, onDelta: (String) -> Unit = {}): ChatResult {
        val base = config.url.serverBase() ?: return ChatResult.Failed(NETWORK_BAD_URL)
        val ollama = isOllama(base, config.apiKey)
        val messages = JsonArray().apply {
            add(message("system", system))
            add(message("user", user))
        }
        val body = JsonObject().apply {
            addProperty("model", config.model)
            add("messages", messages)
            addProperty("stream", true)
            if (ollama) {
                addProperty("keep_alive", "${config.keepAliveMinutes}m")
                add("options", JsonObject().apply {
                    addProperty("temperature", temperature)
                    addProperty("num_predict", MAX_TOKENS)
                })
            } else {
                addProperty("temperature", temperature)
                addProperty("max_tokens", MAX_TOKENS)
            }
        }.toString().toRequestBody(JSON)
        val path = if (ollama) "/api/chat" else "/v1/chat/completions"
        return stream(request(base + path, config.apiKey).post(body).build(), ollama, onDelta)
    }

    /** Whether the server at [base] is Ollama, which answers `/api/version`; remembered, except when the server could not be reached. */
    private suspend fun isOllama(base: String, apiKey: String): Boolean {
        ollamaServers[base]?.let { return it }
        val reply = try {
            send(request("$base/api/version", apiKey).get().build())
        } catch (e: IOException) {
            return false
        }
        return (reply.code == 200 && "\"version\"" in reply.body).also { ollamaServers[base] = it }
    }

    /** Reads the reply on an IO thread; cancelling the coroutine cancels the call, so a cancelled page stops waiting on the server. */
    private suspend fun stream(request: Request, ollama: Boolean, onDelta: (String) -> Unit): ChatResult = withContext(Dispatchers.IO) {
        val call = http.newCall(request)
        @OptIn(InternalCoroutinesApi::class)
        val cancelOnCancel = coroutineContext.job.invokeOnCompletion(onCancelling = true) { call.cancel() }
        try {
            call.execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> ChatResult.Failed(NETWORK_UNAUTHORIZED)
                    response.code != 200 -> ChatResult.Failed("network_http_${response.code}")
                    else -> response.body?.source()
                        ?.let { readChatStream(it, replyFormat(response.header("Content-Type"), ollama), onDelta) }
                        ?: ChatResult.Failed(NETWORK_BAD_REPLY)
                }
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            ChatResult.Failed(failureOf(e))
        } finally {
            cancelOnCancel.dispose()
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

    private companion object {
        val MODEL_PATHS = listOf("/v1/models", "/api/tags")
        val JSON = "application/json".toMediaType()
        const val CHAT_TIMEOUT_SECONDS = 180L
        const val MAX_TOKENS = 2048
    }
}

package com.yomu.app.translation.network

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class NetworkModelClientTest {
    private val requests = mutableListOf<Request>()
    private var handler: (Request) -> Pair<Int, String> = { 404 to "" }

    private val client = NetworkModelClient(
        OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher(InlineExecutor())).addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            val (code, body) = handler(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("m")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()
    )

    private val config = NetworkConfig("http://192.168.1.10:11434", "", "qwen")

    @Test
    fun `a server address is an http or https root without a trailing slash or v1`() {
        assertEquals("http://192.168.1.10:11434", "http://192.168.1.10:11434/".serverBase())
        assertEquals("https://api.openai.com", "https://api.openai.com/v1".serverBase())
        assertEquals("http://host:8080", "  http://host:8080/v1/ ".serverBase())
        assertNull("ftp://host".serverBase())
        assertNull("host:11434".serverBase())
        assertNull("".serverBase())
        assertNull("http://".serverBase())
    }

    @Test
    fun `the models of an OpenAI-style server are listed from v1 models`() = runTest {
        handler = { 200 to """{"data":[{"id":"qwen2.5:7b"},{"id":"gemma3"}]}""" }

        val result = client.listModels("http://h:1", "")

        assertEquals(ModelsResult.Listed(listOf("gemma3", "qwen2.5:7b")), result)
        assertEquals("http://h:1/v1/models", requests.single().url.toString())
    }

    @Test
    fun `the models are listed alphabetically, ignoring case, without repeats`() = runTest {
        handler = { 200 to """{"data":[{"id":"qwen3:8b"},{"id":"Llama3"},{"id":"gemma3"},{"id":"qwen3:8b"},{"id":"deepseek-r1"}]}""" }

        assertEquals(
            ModelsResult.Listed(listOf("deepseek-r1", "gemma3", "Llama3", "qwen3:8b")),
            client.listModels("http://h:1", "")
        )
    }

    @Test
    fun `an API key is sent as a bearer token and none when blank`() = runTest {
        handler = { 200 to """{"data":[{"id":"m"}]}""" }

        client.listModels("http://h:1", "secret")
        client.listModels("http://h:1", "")

        assertEquals("Bearer secret", requests[0].header("Authorization"))
        assertNull(requests[1].header("Authorization"))
    }

    @Test
    fun `Ollama's own tags are tried when v1 models has no list`() = runTest {
        handler = { request ->
            if (request.url.encodedPath == "/api/tags") 200 to """{"models":[{"name":"llama3:8b"}]}""" else 404 to ""
        }

        assertEquals(ModelsResult.Listed(listOf("llama3:8b")), client.listModels("http://h:1", ""))
    }

    @Test
    fun `a server that answers without a list lets the reader type the model`() = runTest {
        handler = { 404 to "not found" }
        assertEquals(ModelsResult.Unlisted, client.listModels("http://h:1", ""))

        handler = { 200 to "<html>hello</html>" }
        assertEquals(ModelsResult.Unlisted, client.listModels("http://h:1", ""))

        handler = { 200 to """{"data":[]}""" }
        assertEquals(ModelsResult.Unlisted, client.listModels("http://h:1", ""))
    }

    @Test
    fun `a rejected key and an unreachable server are failures, not a reason to type a model`() = runTest {
        handler = { 401 to "" }
        assertEquals(ModelsResult.Failed("network_unauthorized"), client.listModels("http://h:1", "bad"))

        assertEquals(ModelsResult.Failed("network_bad_url"), client.listModels("nonsense", ""))

        val unreachable = NetworkModelClient(OkHttpClient.Builder().addInterceptor { throw IOException("down") }.build())
        assertEquals(ModelsResult.Failed("network_unreachable"), unreachable.listModels("http://h:1", ""))
    }

    @Test
    fun `a chat request carries the model, both messages and the key, and the reply text comes back`() = runTest {
        handler = { 200 to """{"choices":[{"message":{"role":"assistant","content":"[1] Привет"}}]}""" }

        val result = client.chat(config.copy(apiKey = "k"), "sys", "user text", 0.2f)

        assertEquals(ChatResult.Success("[1] Привет"), result)
        val request = requests.first()
        assertEquals("http://192.168.1.10:11434/v1/chat/completions", request.url.toString())
        assertEquals("Bearer k", request.header("Authorization"))
        val sent = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(sent, sent.contains("\"model\":\"qwen\"") && sent.contains("sys") && sent.contains("user text") && sent.contains("\"stream\":false"))
    }

    @Test
    fun `a reasoning model's think block is dropped from the reply`() = runTest {
        handler = { 200 to """{"choices":[{"message":{"content":"<think>hmm</think>\n[1] Hi"}}]}""" }

        assertEquals(ChatResult.Success("[1] Hi"), client.chat(config, "s", "u", 0.2f))
    }

    @Test
    fun `chat failures are typed`() = runTest {
        handler = { 401 to "" }
        assertEquals(ChatResult.Failed("network_unauthorized"), client.chat(config, "s", "u", 0.2f))

        handler = { 500 to "" }
        assertEquals(ChatResult.Failed("network_http_500"), client.chat(config, "s", "u", 0.2f))

        handler = { 200 to "no json" }
        assertEquals(ChatResult.Failed("network_bad_reply"), client.chat(config, "s", "u", 0.2f))

        val slow = NetworkModelClient(OkHttpClient.Builder().addInterceptor { throw SocketTimeoutException("t") }.build())
        assertEquals(ChatResult.Failed("network_timeout"), slow.chat(config, "s", "u", 0.2f))
    }

    @Test
    fun `the model is asked to stay loaded for 30 minutes after a page unless set otherwise`() = runTest {
        handler = { request ->
            if (request.url.encodedPath == "/api/generate") 200 to "{}" else 200 to """{"choices":[{"message":{"content":"[1] Hi"}}]}"""
        }

        client.chat(config, "s", "u", 0.2f)
        client.chat(config.copy(keepAliveMinutes = 60), "s", "u", 0.2f)

        val keepAlive = requests.filter { it.url.encodedPath == "/api/generate" }.map { okio.Buffer().also { b -> it.body!!.writeTo(b) }.readUtf8() }
        assertEquals(2, keepAlive.size)
        assertTrue(keepAlive[0], keepAlive[0].contains("\"keep_alive\":\"30m\"") && keepAlive[0].contains("\"model\":\"qwen\""))
        assertTrue(keepAlive[1], keepAlive[1].contains("\"keep_alive\":\"60m\""))
        assertEquals("/v1/chat/completions", requests.first().url.encodedPath)
    }

    @Test
    fun `a server without that endpoint is asked once and then left alone`() = runTest {
        handler = { request ->
            if (request.url.encodedPath == "/api/generate") 404 to "" else 200 to """{"choices":[{"message":{"content":"[1] Hi"}}]}"""
        }

        client.chat(config, "s", "u", 0.2f)
        client.chat(config, "s", "u", 0.2f)

        assertEquals(1, requests.count { it.url.encodedPath == "/api/generate" })
    }

    @Test
    fun `a failed chat extends nothing`() = runTest {
        handler = { 500 to "" }

        client.chat(config, "s", "u", 0.2f)

        assertEquals(0, requests.count { it.url.encodedPath == "/api/generate" })
    }
}

/** Runs OkHttp's calls on the calling thread, so a fire-and-forget request has been sent when the test looks. */
private class InlineExecutor : java.util.concurrent.AbstractExecutorService() {
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() = Unit
    override fun shutdownNow(): List<Runnable> = emptyList()
    override fun isShutdown(): Boolean = false
    override fun isTerminated(): Boolean = false
    override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = true
}

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
import org.junit.Assert.assertFalse
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
                .body(body.toResponseBody(contentType(request.url.encodedPath, body).toMediaType())).build()
        }).build()
    )

    private fun contentType(path: String, body: String) = when {
        body.startsWith("data:") -> "text/event-stream"
        path == "/api/chat" -> "application/x-ndjson"
        else -> "application/json"
    }

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

    private fun sent(request: Request): String = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    private fun paths() = requests.map { it.url.encodedPath }

    private val sse = """
        data: {"choices":[{"delta":{"role":"assistant"}}]}

        data: {"choices":[{"delta":{"content":"[1] При"}}]}

        data: {"choices":[{"delta":{"content":"вет\n[2] Пока"}}]}

        data: [DONE]
    """.trimIndent()

    @Test
    fun `an OpenAI-style reply is read as it streams, piece by piece, with the key and no Ollama extras`() = runTest {
        handler = { request -> if (request.url.encodedPath == "/v1/chat/completions") 200 to sse else 404 to "" }
        val pieces = mutableListOf<String>()

        val result = client.chat(config.copy(apiKey = "k"), "sys", "user text", 0.2f) { pieces += it }

        assertEquals(ChatResult.Success("[1] Привет\n[2] Пока"), result)
        assertEquals(listOf("[1] При", "вет\n[2] Пока"), pieces)
        val chat = requests.last()
        assertEquals("http://192.168.1.10:11434/v1/chat/completions", chat.url.toString())
        assertEquals("Bearer k", chat.header("Authorization"))
        val body = sent(chat)
        assertTrue(body, body.contains("\"model\":\"qwen\"") && body.contains("sys") && body.contains("user text") && body.contains("\"stream\":true"))
        assertFalse(body, body.contains("keep_alive"))
        assertEquals(listOf("/api/version", "/v1/chat/completions"), paths())
    }

    @Test
    fun `an Ollama server is asked through its own chat with keep_alive in the same request, and recognised only once`() = runTest {
        handler = { request ->
            when (request.url.encodedPath) {
                "/api/version" -> 200 to """{"version":"0.5.7"}"""
                "/api/chat" -> 200 to """
                    {"message":{"role":"assistant","content":"[1] Hi"},"done":false}
                    {"message":{"role":"assistant","content":"\n[2] Bye"},"done":false}
                    {"message":{"role":"assistant","content":""},"done":true}
                """.trimIndent()
                else -> 404 to ""
            }
        }
        val pieces = mutableListOf<String>()

        val first = client.chat(config, "sys", "user", 0.2f) { pieces += it }
        client.chat(config.copy(keepAliveMinutes = 60), "sys", "user", 0.2f)

        assertEquals(ChatResult.Success("[1] Hi\n[2] Bye"), first)
        assertEquals(listOf("[1] Hi", "\n[2] Bye"), pieces)
        assertEquals(listOf("/api/version", "/api/chat", "/api/chat"), paths())
        val body = sent(requests[1])
        assertTrue(body, body.contains("\"keep_alive\":\"30m\"") && body.contains("\"stream\":true") && body.contains("\"temperature\":0.2"))
        assertTrue(sent(requests[2]).contains("\"keep_alive\":\"60m\""))
    }

    @Test
    fun `a server that is not Ollama is never sent keep_alive or asked through its endpoints`() = runTest {
        handler = { request -> if (request.url.encodedPath == "/v1/chat/completions") 200 to sse else 404 to "" }

        client.chat(config, "s", "u", 0.2f)
        client.chat(config, "s", "u", 0.2f)

        assertEquals(listOf("/api/version", "/v1/chat/completions", "/v1/chat/completions"), paths())
        assertTrue(requests.none { it.method == "POST" && sent(it).contains("keep_alive") })
    }

    @Test
    fun `a server that ignores stream and sends one JSON body is read as one piece`() = runTest {
        handler = { 200 to """{"choices":[{"message":{"content":"[1] Привет"}}]}""" }
        val pieces = mutableListOf<String>()

        val result = client.chat(config, "s", "u", 0.2f) { pieces += it }

        assertEquals(ChatResult.Success("[1] Привет"), result)
        assertEquals(listOf("[1] Привет"), pieces)
    }

    @Test
    fun `a reasoning model's think block is dropped from the stream, even when split across pieces`() = runTest {
        handler = { """
            data: {"choices":[{"delta":{"content":"<thi"}}]}

            data: {"choices":[{"delta":{"content":"nk>hmm</think>\n[1] H"}}]}

            data: {"choices":[{"delta":{"content":"i"}}]}

            data: [DONE]
        """.trimIndent().let { body -> 200 to body } }
        val pieces = mutableListOf<String>()

        val result = client.chat(config, "s", "u", 0.2f) { pieces += it }

        assertEquals(ChatResult.Success("[1] Hi"), result)
        assertEquals("\n[1] Hi", pieces.joinToString(""))
    }

    @Test
    fun `an error inside the stream is a bad reply`() = runTest {
        handler = { 200 to "data: {\"error\":{\"message\":\"model not found\"}}" }

        assertEquals(ChatResult.Failed("network_bad_reply"), client.chat(config, "s", "u", 0.2f))
    }

    @Test
    fun `chat failures are typed`() = runTest {
        handler = { 401 to "" }
        assertEquals(ChatResult.Failed("network_unauthorized"), client.chat(config, "s", "u", 0.2f))

        handler = { 500 to "" }
        assertEquals(ChatResult.Failed("network_http_500"), client.chat(config, "s", "u", 0.2f))

        handler = { 200 to "no json" }
        assertEquals(ChatResult.Failed("network_bad_reply"), client.chat(config, "s", "u", 0.2f))

        assertEquals(ChatResult.Failed("network_bad_url"), client.chat(config.copy(url = "nonsense"), "s", "u", 0.2f))

        val slow = NetworkModelClient(OkHttpClient.Builder().addInterceptor { throw SocketTimeoutException("t") }.build())
        assertEquals(ChatResult.Failed("network_timeout"), slow.chat(config, "s", "u", 0.2f))

        val down = NetworkModelClient(OkHttpClient.Builder().addInterceptor { throw IOException("down") }.build())
        assertEquals(ChatResult.Failed("network_unreachable"), down.chat(config, "s", "u", 0.2f))
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

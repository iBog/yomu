package com.yomu.app.translation.network

import com.google.gson.Gson
import com.yomu.app.translation.MapSharedPreferences
import com.yomu.core.SourceLanguage
import com.yomu.core.SpokenLanguage
import com.yomu.core.TargetLanguage
import com.yomu.core.TranslatableBubble
import com.yomu.core.TranslatablePage
import com.yomu.core.TranslationOutcome
import com.yomu.core.TranslationStatus
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
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTranslationSlotTest {
    private val store = NetworkModelStore(MapSharedPreferences(), MapSharedPreferences())
    private val chats = mutableListOf<Request>()

    /** One reply per chat request, in order; the server is not Ollama, so the first request is the version probe it refuses. */
    private val replies = ArrayDeque<Pair<Int, String>>()
    private val slot = NetworkTranslationSlot(
        NetworkModelClient(OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val (code, body) = if (request.url.encodedPath == "/api/version") 404 to "" else {
                chats += request
                replies.removeFirstOrNull() ?: (200 to content(""))
            }
            val type = if (body.startsWith("data:")) "text/event-stream" else "application/json"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("m")
                .body(body.toResponseBody(type.toMediaType())).build()
        }).build()),
        store
    )
    private val page = TranslatablePage(
        listOf(listOf(TranslatableBubble(1, "One"), TranslatableBubble(2, "Two")), listOf(TranslatableBubble(3, "Three"))),
        TargetLanguage.RUSSIAN, SourceLanguage.LATIN_SCRIPT, SpokenLanguage.ENGLISH
    )

    private fun configure() {
        store.saveUrl("http://h:11434")
        store.saveModel("qwen")
    }

    private fun content(text: String) = """{"choices":[{"message":{"content":${Gson().toJson(text)}}}]}"""

    private fun stream(vararg pieces: String) =
        pieces.joinToString("\n\n", postfix = "\n\ndata: [DONE]") { "data: " + """{"choices":[{"delta":{"content":${Gson().toJson(it)}}}]}""" }

    private fun body(request: Request) = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()

    @Test
    fun `without an address and a model the slot is not ready and says so`() = runTest {
        assertFalse(slot.ensureReady())
        assertEquals(TranslationStatus.Error(ERROR_NETWORK_NOT_CONFIGURED), slot.status)
        val result = slot.translatePage(page)
        assertEquals(TranslationOutcome.NOT_LOADED, result.outcome)
        assertEquals(ERROR_NETWORK_NOT_CONFIGURED, result.errorCode)
    }

    @Test
    fun `a streamed page is answered by id, each answer reported once as its line completes`() = runTest {
        configure()
        replies += 200 to stream("[1] При", "вет\n[2] По", "ка\n[3] Тр", "и")
        val heard = mutableListOf<Pair<Int, String>>()

        val result = slot.translatePage(page) { id, text -> heard += id to text }

        assertEquals(mapOf(1 to "Привет", 2 to "Пока", 3 to "Три"), result.byId)
        assertEquals(listOf(1 to "Привет", 2 to "Пока", 3 to "Три"), heard)
        assertEquals(TranslationOutcome.SUCCESS, result.outcome)
        assertEquals(1, chats.size)
    }

    @Test
    fun `a page answered in one JSON body is read the same`() = runTest {
        configure()
        replies += 200 to content("[1] Привет\n[2] Пока\n[3] Три")

        assertEquals(mapOf(1 to "Привет", 2 to "Пока", 3 to "Три"), slot.translatePage(page).byId)
    }

    @Test
    fun `ids the reply skipped are asked for once more, alone, as their own lines`() = runTest {
        configure()
        replies += 200 to stream("[1] Раз\n[3] Три")
        replies += 200 to stream("[2] Два")
        val heard = mutableListOf<Int>()

        val result = slot.translatePage(page) { id, _ -> heard += id }

        assertEquals(2, chats.size)
        val second = body(chats[1])
        assertTrue(second, second.contains("[2] Two") && !second.contains("[1] One") && !second.contains("[3] Three"))
        assertEquals(mapOf(1 to "Раз", 3 to "Три", 2 to "Два"), result.byId)
        assertEquals(listOf(1, 3, 2), heard)
        assertEquals(TranslationOutcome.SUCCESS, result.outcome)
    }

    @Test
    fun `the retry is made once, and whatever it misses stays unanswered`() = runTest {
        configure()
        replies += 200 to stream("[1] Раз")
        replies += 200 to stream("[2] Два")
        replies += 200 to stream("[3] Три")

        val result = slot.translatePage(page)

        assertEquals(2, chats.size)
        assertEquals(mapOf(1 to "Раз", 2 to "Два"), result.byId)
    }

    @Test
    fun `a retry that fails keeps what the first reply gave`() = runTest {
        configure()
        replies += 200 to stream("[1] Раз\n[2] Два")
        replies += 500 to ""

        val result = slot.translatePage(page)

        assertEquals(mapOf(1 to "Раз", 2 to "Два"), result.byId)
        assertEquals(TranslationOutcome.SUCCESS, result.outcome)
    }

    @Test
    fun `a reply with no usable line is blank and is not asked again`() = runTest {
        configure()
        replies += 200 to stream("I cannot help with that.")

        val result = slot.translatePage(page)

        assertTrue(result.byId.isEmpty())
        assertEquals(TranslationOutcome.BLANK, result.outcome)
        assertEquals(1, chats.size)
    }

    @Test
    fun `a page that is fully answered is asked once`() = runTest {
        configure()
        replies += 200 to stream("[1] Раз\n[2] Два\n[3] Три")

        slot.translatePage(page)

        assertEquals(1, chats.size)
    }

    @Test
    fun `a server failure is typed on the result`() = runTest {
        configure()
        replies += 500 to ""

        val result = slot.translatePage(page)

        assertEquals(TranslationOutcome.ERROR, result.outcome)
        assertEquals("network_http_500", result.errorCode)
        assertTrue(result.byId.isEmpty())
    }

    @Test
    fun `a stream that breaks keeps the lines already heard, and says it failed`() = runTest {
        configure()
        replies += 200 to (stream("[1] Раз\n[2] Д").removeSuffix("\n\ndata: [DONE]") + "\n\ndata: {\"error\":{\"message\":\"gone\"}}")
        val heard = mutableListOf<Int>()

        val result = slot.translatePage(page) { id, _ -> heard += id }

        assertEquals(listOf(1), heard)
        assertEquals(mapOf(1 to "Раз"), result.byId)
        assertEquals(TranslationOutcome.ERROR, result.outcome)
        assertEquals("network_bad_reply", result.errorCode)
    }

    @Test
    fun `an empty page is no request`() = runTest {
        configure()

        assertEquals(emptyMap<Int, String>(), slot.translatePage(page.copy(panels = emptyList())).byId)
        assertEquals(0, chats.size)
    }
}

package com.yomu.app.translation.network

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
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTranslationSlotTest {
    private val store = NetworkModelStore(MapSharedPreferences(), MapSharedPreferences())
    private var reply: Pair<Int, String> = 200 to ""
    private val slot = NetworkTranslationSlot(
        NetworkModelClient(OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(reply.first).message("m")
                .body(reply.second.toResponseBody("application/json".toMediaType())).build()
        }).build()),
        store
    )
    private val page = TranslatablePage(
        listOf(listOf(TranslatableBubble(1, "Hello"), TranslatableBubble(2, "Bye"))),
        TargetLanguage.RUSSIAN, SourceLanguage.LATIN_SCRIPT, SpokenLanguage.ENGLISH
    )

    private fun configure() {
        store.saveUrl("http://h:11434")
        store.saveModel("qwen")
    }

    private fun content(text: String) = 200 to """{"choices":[{"message":{"content":${com.google.gson.Gson().toJson(text)}}}]}"""

    @Test
    fun `without an address and a model the slot is not ready and says so`() = runTest {
        assertFalse(slot.ensureReady())
        assertEquals(TranslationStatus.Error(ERROR_NETWORK_NOT_CONFIGURED), slot.status)
        val result = slot.translatePage(page)
        assertEquals(TranslationOutcome.NOT_LOADED, result.outcome)
        assertEquals(ERROR_NETWORK_NOT_CONFIGURED, result.errorCode)
    }

    @Test
    fun `a page is answered by id and each answer is reported once`() = runTest {
        configure()
        reply = content("[1] Привет\n[2] Пока")
        val heard = mutableListOf<Pair<Int, String>>()

        val result = slot.translatePage(page) { id, text -> heard += id to text }

        assertEquals(mapOf(1 to "Привет", 2 to "Пока"), result.byId)
        assertEquals(listOf(1 to "Привет", 2 to "Пока"), heard)
        assertEquals(TranslationOutcome.SUCCESS, result.outcome)
    }

    @Test
    fun `a reply with no usable lines is blank, not an error`() = runTest {
        configure()
        reply = content("I cannot help with that.")

        val result = slot.translatePage(page)

        assertTrue(result.byId.isEmpty())
        assertEquals(TranslationOutcome.BLANK, result.outcome)
    }

    @Test
    fun `a server failure is typed on the result`() = runTest {
        configure()
        reply = 500 to ""

        val result = slot.translatePage(page)

        assertEquals(TranslationOutcome.ERROR, result.outcome)
        assertEquals("network_http_500", result.errorCode)
    }

    @Test
    fun `an empty page is no request`() = runTest {
        configure()

        assertEquals(emptyMap<Int, String>(), slot.translatePage(page.copy(panels = emptyList())).byId)
    }
}

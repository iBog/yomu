package com.yomu.app.translation

import com.yomu.app.translation.network.NetworkModelClient
import com.yomu.app.translation.network.NetworkModelStore
import com.yomu.app.translation.network.NetworkTranslationSlot
import com.yomu.app.translation.network.TranslationBackend
import com.yomu.core.SpokenLanguage
import com.yomu.core.TargetLanguage
import com.yomu.ml.LlamaTranslationBridge
import com.yomu.pipeline.TranslationPipeline
import com.yomu.pipeline.bubble.BubbleDetector
import com.yomu.pipeline.context.ContextAssembler
import com.yomu.pipeline.ocr.OcrEngine
import com.yomu.pipeline.translation.ReaderFailure
import com.yomu.pipeline.translation.TranslationEngine
import com.yomu.pipeline.translation.readerFailure
import com.yomu.pipeline.typesetting.Typesetter
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.io.File

/** The translation test goes through the same engine and slot choice as reading, so it follows what Settings chose. */
class TryTranslateBackendTest {
    private val prefs = MapSharedPreferences()
    private val store = NetworkModelStore(prefs, MapSharedPreferences())
    private val requests = mutableListOf<Request>()
    private var status = 200
    private var content = "[0] Привет"

    private val network = NetworkTranslationSlot(
        NetworkModelClient(OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            requests += chain.request()
            val body = """{"choices":[{"message":{"content":${com.google.gson.Gson().toJson(content)}}}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("m")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()),
        store
    )
    private val selection = TranslationModelSelection(Mockito.mock(LlamaTranslationBridge::class.java), prefs, File("models"), 0L, network, store)
    private val pipeline = TranslationPipeline(
        Mockito.mock(BubbleDetector::class.java),
        Mockito.mock(OcrEngine::class.java),
        ContextAssembler(),
        TranslationEngine(selection::current, selection::close),
        Mockito.mock(Typesetter::class.java)
    )

    private fun chooseNetwork() {
        store.saveUrl("http://192.168.1.10:11434")
        store.saveApiKey("k")
        store.saveModel("qwen")
        store.saveBackend(TranslationBackend.NETWORK)
    }

    @Test
    fun `with the network model chosen the text is sent to the server and its translation comes back`() = runTest {
        chooseNetwork()

        val result = pipeline.translateLines(listOf("Hello"), TargetLanguage.RUSSIAN, SpokenLanguage.ENGLISH, emptyList())

        assertEquals("Привет", result.translations.single().translatedText)
        assertEquals("http://192.168.1.10:11434/v1/chat/completions", requests.first().url.toString())
        assertEquals("Bearer k", requests.first().header("Authorization"))
        val sent = okio.Buffer().also { requests.first().body!!.writeTo(it) }.readUtf8()
        assertTrue(sent, sent.contains("Translate each English line to Russian") && sent.contains("[0] Hello") && sent.contains("\"model\":\"qwen\""))
    }

    @Test
    fun `Auto leaves the source out of what the server is asked`() = runTest {
        chooseNetwork()

        pipeline.translateLines(listOf("Hello"), TargetLanguage.GERMAN, null, emptyList())

        val sent = okio.Buffer().also { requests.first().body!!.writeTo(it) }.readUtf8()
        assertTrue(sent, sent.contains("Translate each line to German"))
    }

    @Test
    fun `a server that fails is reported as a network failure, not as a missing model`() = runTest {
        chooseNetwork()
        status = 500

        val result = pipeline.translateLines(listOf("Hello"), TargetLanguage.RUSSIAN, null, emptyList())

        assertEquals(ReaderFailure.NetworkFailed("network_http_500"), result.readerFailure())
    }

    @Test
    fun `the glossary entries it is given reach the server as the source text`() = runTest {
        chooseNetwork()

        pipeline.translateLines(listOf("ルフィ is here"), TargetLanguage.RUSSIAN, null, listOf(com.yomu.core.GlossaryEntry("ルフィ", "Luffy")))

        val sent = okio.Buffer().also { requests.first().body!!.writeTo(it) }.readUtf8()
        assertTrue(sent, sent.contains("Luffy is here"))
    }
}

package com.yomu.app.ui.settings

import com.yomu.app.translation.MapSharedPreferences
import com.yomu.app.translation.network.NetworkModelClient
import com.yomu.app.translation.network.NetworkModelStore
import com.yomu.app.translation.network.TranslationBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkModelViewModelTest {
    private val prefs = MapSharedPreferences()
    private val secrets = MapSharedPreferences()
    private val store = NetworkModelStore(prefs, secrets)
    private var reply: Pair<Int, String> = 200 to """{"data":[{"id":"qwen"},{"id":"gemma"}]}"""
    private var asked = 0
    private val client = NetworkModelClient(
        OkHttpClient.Builder().dispatcher(Dispatcher(InlineExecutor())).addInterceptor(Interceptor { chain ->
            asked++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(reply.first).message("m")
                .body(reply.second.toResponseBody("application/json".toMediaType())).build()
        }).build()
    )

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the models are asked for once typing pauses, not on every keystroke`() = runTest {
        val viewModel = NetworkModelViewModel(store, client)

        viewModel.setUrl("http://h")
        viewModel.setUrl("http://h:11434")
        advanceTimeBy(300)
        assertEquals(0, asked)
        advanceUntilIdle()

        assertEquals(1, asked)
        assertEquals(ModelsState.Listed(listOf("gemma", "qwen")), viewModel.state.value.models)
    }

    @Test
    fun `a server without a model list lets the reader type the model`() = runTest {
        reply = 404 to ""
        val viewModel = NetworkModelViewModel(store, client)

        viewModel.setUrl("http://h:11434")
        advanceUntilIdle()
        viewModel.setModel("qwen2.5:7b")

        assertEquals(ModelsState.Manual, viewModel.state.value.models)
        assertEquals("qwen2.5:7b", store.config().model)
        assertTrue(viewModel.state.value.ready)
    }

    @Test
    fun `an address is marked good once a server answered, with or without a model list`() {
        assertTrue(ModelsState.Listed(listOf("m")).answered)
        assertTrue(ModelsState.Manual.answered)
        assertFalse(ModelsState.Loading.answered)
        assertFalse(ModelsState.Idle.answered)
        assertFalse(ModelsState.Failed("network_unreachable").answered)
    }

    @Test
    fun `a rejected key is a failure`() = runTest {
        reply = 401 to ""
        val viewModel = NetworkModelViewModel(store, client)

        viewModel.setUrl("http://h:11434")
        advanceUntilIdle()

        assertEquals(ModelsState.Failed("network_unauthorized"), viewModel.state.value.models)
    }

    @Test
    fun `the settings are saved as typed and the key goes to its own file`() = runTest {
        val viewModel = NetworkModelViewModel(store, client)

        viewModel.setUrl("http://h:1")
        viewModel.setApiKey("secret")
        viewModel.setModel("m")
        advanceUntilIdle()

        assertEquals("http://h:1", store.config().url)
        assertEquals("secret", secrets.values[NetworkModelStore.KEY_API_KEY])
    }

    @Test
    fun `the network model can be chosen only once an address and a model are set`() = runTest {
        val viewModel = NetworkModelViewModel(store, client)
        viewModel.setBackend(TranslationBackend.NETWORK)
        assertFalse(viewModel.state.value.active)
        assertFalse(viewModel.state.value.ready)

        viewModel.setUrl("http://h:1")
        viewModel.setModel("m")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.active)
        assertTrue(store.active())
    }

    @Test
    fun `a saved address is asked about when the screen opens`() = runTest {
        store.saveUrl("http://h:11434")

        val viewModel = NetworkModelViewModel(store, client)
        advanceUntilIdle()

        assertEquals(1, asked)
        assertEquals(ModelsState.Listed(listOf("gemma", "qwen")), viewModel.state.value.models)
    }
}

/** Runs OkHttp's calls on the calling thread, so a virtual-time test sees a reply as soon as it is sent. */
private class InlineExecutor : AbstractExecutorService() {
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() = Unit
    override fun shutdownNow(): List<Runnable> = emptyList()
    override fun isShutdown(): Boolean = false
    override fun isTerminated(): Boolean = false
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
}

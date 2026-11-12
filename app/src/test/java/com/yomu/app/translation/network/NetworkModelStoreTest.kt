package com.yomu.app.translation.network

import com.yomu.app.translation.MapSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModelStoreTest {
    private val prefs = MapSharedPreferences()
    private val secrets = MapSharedPreferences()
    private val store = NetworkModelStore(prefs, secrets)

    @Test
    fun `nothing stored is the on-device model and an empty config`() {
        assertEquals(TranslationBackend.DEVICE, store.backend())
        assertEquals(NetworkConfig("", "", ""), store.config())
        assertFalse(store.active())
    }

    @Test
    fun `the API key is kept apart from the other preferences`() {
        store.saveUrl(" http://h:1 ")
        store.saveApiKey(" secret ")
        store.saveModel("m")

        assertEquals(NetworkConfig("http://h:1", "secret", "m"), store.config())
        assertTrue(prefs.values.values.none { it == "secret" })
        assertEquals("secret", secrets.values[NetworkModelStore.KEY_API_KEY])
    }

    @Test
    fun `the network model is in use only when chosen and complete`() {
        store.saveBackend(TranslationBackend.NETWORK)
        assertFalse(store.active())

        store.saveUrl("http://h:1")
        assertFalse(store.active())

        store.saveModel("m")
        assertTrue(store.active())

        store.saveBackend(TranslationBackend.DEVICE)
        assertFalse(store.active())
    }

    @Test
    fun `a model stays loaded for 30 minutes until the reader says otherwise`() {
        assertEquals(30, store.config().keepAliveMinutes)

        store.saveKeepAliveMinutes(60)
        assertEquals(60, store.config().keepAliveMinutes)

        prefs.values[NetworkModelStore.KEY_KEEP_ALIVE] = 0
        assertEquals(30, store.config().keepAliveMinutes)
        prefs.values[NetworkModelStore.KEY_KEEP_ALIVE] = "soon"
        assertEquals(30, store.config().keepAliveMinutes)
    }

    @Test
    fun `an unknown or wrongly typed backend is the on-device model`() {
        prefs.values[NetworkModelStore.KEY_BACKEND] = "mars"
        assertEquals(TranslationBackend.DEVICE, store.backend())
        prefs.values[NetworkModelStore.KEY_BACKEND] = 3
        assertEquals(TranslationBackend.DEVICE, store.backend())
    }
}

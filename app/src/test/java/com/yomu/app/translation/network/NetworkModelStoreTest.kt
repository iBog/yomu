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
    fun `the address, key, model and keep-alive are kept in the secure preferences and none of them in the plain ones`() {
        store.saveUrl(" http://h:1 ")
        store.saveApiKey(" secret ")
        store.saveModel("m")
        store.saveKeepAliveMinutes(60)
        store.saveBackend(TranslationBackend.NETWORK)

        assertEquals(NetworkConfig("http://h:1", "secret", "m", 60), store.config())
        assertEquals(mapOf(NetworkModelStore.KEY_BACKEND to "network"), prefs.values.toMap())
        assertEquals(
            setOf(NetworkModelStore.KEY_URL, NetworkModelStore.KEY_API_KEY, NetworkModelStore.KEY_MODEL, NetworkModelStore.KEY_KEEP_ALIVE),
            secrets.values.keys
        )
    }

    @Test
    fun `settings kept in plain files by earlier builds move into the secure preferences and leave nothing behind`() {
        val legacy = MapSharedPreferences()
        prefs.values[NetworkModelStore.KEY_URL] = "http://old:11434"
        prefs.values[NetworkModelStore.KEY_MODEL] = "qwen"
        prefs.values[NetworkModelStore.KEY_KEEP_ALIVE] = 60
        prefs.values[NetworkModelStore.KEY_BACKEND] = "network"
        legacy.values[NetworkModelStore.KEY_API_KEY] = "old-key"

        store.migrateLegacy(legacy)

        assertEquals(NetworkConfig("http://old:11434", "old-key", "qwen", 60), store.config())
        assertEquals(mapOf(NetworkModelStore.KEY_BACKEND to "network"), prefs.values.toMap())
        assertTrue(legacy.values.isEmpty())
        assertTrue(store.active())
    }

    @Test
    fun `a value already in the secure preferences wins over a plain leftover`() {
        secrets.values[NetworkModelStore.KEY_URL] = "http://new:1"
        prefs.values[NetworkModelStore.KEY_URL] = "http://old:1"

        store.migrateLegacy(MapSharedPreferences())

        assertEquals("http://new:1", store.config().url)
        assertFalse(prefs.values.containsKey(NetworkModelStore.KEY_URL))
    }

    @Test
    fun `migrating with nothing to move changes nothing`() {
        store.migrateLegacy(MapSharedPreferences())

        assertEquals(NetworkConfig("", "", "", 30), store.config())
        assertTrue(secrets.values.isEmpty() && prefs.values.isEmpty())
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

        secrets.values[NetworkModelStore.KEY_KEEP_ALIVE] = 0
        assertEquals(30, store.config().keepAliveMinutes)
        secrets.values[NetworkModelStore.KEY_KEEP_ALIVE] = "soon"
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

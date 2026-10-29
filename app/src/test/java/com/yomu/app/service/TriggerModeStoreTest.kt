package com.yomu.app.service

import com.yomu.app.translation.MapSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class TriggerModeStoreTest {

    private val prefs = MapSharedPreferences()
    private val store = TriggerModeStore(prefs)

    @Test
    fun `nothing stored loads the floating button`() {
        assertEquals(TriggerMode.FLOATING_BUTTON, store.load())
    }

    @Test
    fun `a chosen mode is kept`() {
        store.save(TriggerMode.NOTIFICATION)
        assertEquals(TriggerMode.NOTIFICATION, TriggerModeStore(prefs).load())

        store.save(TriggerMode.FLOATING_BUTTON)
        assertEquals(TriggerMode.FLOATING_BUTTON, TriggerModeStore(prefs).load())
    }

    @Test
    fun `an unknown or wrongly typed stored value loads the floating button`() {
        prefs.values[TriggerModeStore.KEY] = "tile"
        assertEquals(TriggerMode.FLOATING_BUTTON, store.load())

        prefs.values[TriggerModeStore.KEY] = 3
        assertEquals(TriggerMode.FLOATING_BUTTON, store.load())
    }
}

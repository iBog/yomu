package com.yomu.app.service

import com.yomu.app.translation.MapSharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveHistoryStoreTest {
    private val prefs = MapSharedPreferences()

    @Test
    fun `history is saved until the reader turns it off`() {
        assertTrue(SaveHistoryStore(prefs).load())
    }

    @Test
    fun `the choice is kept`() {
        SaveHistoryStore(prefs).save(false)
        assertFalse(SaveHistoryStore(prefs).load())
        SaveHistoryStore(prefs).save(true)
        assertTrue(SaveHistoryStore(prefs).load())
    }

    @Test
    fun `a value of another type falls back to saving`() {
        prefs.values[SaveHistoryStore.KEY] = "no"
        assertTrue(SaveHistoryStore(prefs).load())
    }
}

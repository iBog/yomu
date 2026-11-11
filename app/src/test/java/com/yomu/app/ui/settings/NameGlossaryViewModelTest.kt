package com.yomu.app.ui.settings

import com.yomu.app.translation.MapSharedPreferences
import com.yomu.app.translation.NameGlossaryStore
import com.yomu.core.GlossaryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameGlossaryViewModelTest {

    private val prefs = MapSharedPreferences()
    private val store = NameGlossaryStore(prefs)

    @Test
    fun `the screen opens on the stored glossary`() {
        store.save("ルフィ", "Luffy")

        assertEquals(listOf(GlossaryEntry("ルフィ", "Luffy")), NameGlossaryViewModel(store).entries.value)
    }

    @Test
    fun `saved and deleted entries show at once`() {
        val viewModel = NameGlossaryViewModel(store)

        viewModel.save("ルフィ", "Luffy", replacing = null)
        viewModel.save("ゾロ", "Zoro", replacing = null)
        viewModel.delete("ルフィ")

        assertEquals(listOf(GlossaryEntry("ゾロ", "Zoro")), viewModel.entries.value)
    }

    @Test
    fun `a refused entry reports it was not saved`() {
        assertFalse(NameGlossaryViewModel(store).save(" ", "Luffy", replacing = null))
    }

    @Test
    fun `the switch opens on the stored value and a flip shows at once with the entries kept`() {
        store.save("ルフィ", "Luffy")
        store.setEnabled(false)
        val viewModel = NameGlossaryViewModel(store)
        assertFalse(viewModel.enabled.value)

        viewModel.setEnabled(true)

        assertTrue(viewModel.enabled.value)
        assertTrue(NameGlossaryStore(prefs).isEnabled())
        assertEquals(listOf(GlossaryEntry("ルフィ", "Luffy")), viewModel.entries.value)
    }
}

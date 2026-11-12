package com.yomu.app.translation

import com.yomu.core.GlossaryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameGlossaryStoreTest {

    private val prefs = MapSharedPreferences()
    private val store = NameGlossaryStore(prefs)

    @Test
    fun `nothing stored loads an empty glossary`() {
        assertEquals(emptyList<GlossaryEntry>(), store.load())
    }

    @Test
    fun `saved entries load back in the order they were added`() {
        store.save("ルフィ", "Luffy")
        store.save("ゾロ", "Zoro")

        assertEquals(listOf(GlossaryEntry("ルフィ", "Luffy"), GlossaryEntry("ゾロ", "Zoro")), NameGlossaryStore(prefs).load())
    }

    @Test
    fun `both fields are trimmed`() {
        assertTrue(store.save("  ルフィ ", " Luffy\n"))

        assertEquals(listOf(GlossaryEntry("ルフィ", "Luffy")), store.load())
    }

    @Test
    fun `a blank term or rendering is refused`() {
        assertFalse(store.save("  ", "Luffy"))
        assertFalse(store.save("ルフィ", " "))

        assertEquals(emptyList<GlossaryEntry>(), store.load())
    }

    @Test
    fun `saving a term already listed edits that entry instead of adding another`() {
        store.save("ルフィ", "Luffy")
        store.save("ゾロ", "Zoro")
        store.save("ルフィ", "Ruffy")

        assertEquals(listOf(GlossaryEntry("ルフィ", "Ruffy"), GlossaryEntry("ゾロ", "Zoro")), store.load())
    }

    @Test
    fun `editing an entry's term replaces the old entry where it stood`() {
        store.save("ルフイ", "Luffy")
        store.save("ゾロ", "Zoro")

        store.save("ルフィ", "Luffy", replacing = "ルフイ")

        assertEquals(listOf(GlossaryEntry("ルフィ", "Luffy"), GlossaryEntry("ゾロ", "Zoro")), store.load())
    }

    @Test
    fun `a deleted entry is gone`() {
        store.save("ルフィ", "Luffy")
        store.save("ゾロ", "Zoro")

        store.delete("ルフィ")

        assertEquals(listOf(GlossaryEntry("ゾロ", "Zoro")), store.load())
    }

    @Test
    fun `an unreadable stored value loads an empty glossary`() {
        listOf("not json", "{\"term\":1}", "[{\"term\":\"ルフィ\"}]", 3).forEach { stored ->
            prefs.values[NameGlossaryStore.KEY] = stored

            assertEquals("$stored", emptyList<GlossaryEntry>(), store.load())
        }
    }

    @Test
    fun `nothing stored loads the glossary on`() {
        assertTrue(store.isEnabled())
    }

    @Test
    fun `turning the glossary off and on again is kept`() {
        store.setEnabled(false)
        assertFalse(NameGlossaryStore(prefs).isEnabled())

        store.setEnabled(true)
        assertTrue(NameGlossaryStore(prefs).isEnabled())
    }

    @Test
    fun `a wrongly typed enabled value loads the glossary on`() {
        prefs.values[NameGlossaryStore.ENABLED_KEY] = "off"

        assertTrue(store.isEnabled())
    }

    @Test
    fun `while off the entries in use are empty and the entries are untouched`() {
        store.save("ルフィ", "Luffy")

        store.setEnabled(false)

        assertEquals(emptyList<GlossaryEntry>(), store.entriesInUse())
        assertEquals(listOf(GlossaryEntry("ルフィ", "Luffy")), store.load())
    }

    @Test
    fun `while on the entries in use are the entries`() {
        store.save("ルフィ", "Luffy")

        assertEquals(listOf(GlossaryEntry("ルフィ", "Luffy")), store.entriesInUse())
    }
}

package com.yomu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenLanguageTest {
    @Test
    fun `a code finds its language and an unknown code finds none`() {
        assertEquals(SpokenLanguage.FRENCH, SpokenLanguage.fromCode("fr"))
        assertNull(SpokenLanguage.fromCode("auto"))
        assertNull(SpokenLanguage.fromCode(null))
    }

    @Test
    fun `every language is read in the script its OCR model reads`() {
        assertEquals(SourceLanguage.JAPANESE, SpokenLanguage.JAPANESE.script)
        assertEquals(SourceLanguage.LATIN_SCRIPT, SpokenLanguage.GERMAN.script)
        assertEquals(SourceLanguage.CYRILLIC_SCRIPT, SpokenLanguage.UKRAINIAN.script)
    }

    @Test
    fun `a script is named only when one language owns it`() {
        assertEquals(SpokenLanguage.KOREAN, SpokenLanguage.ofScript(SourceLanguage.KOREAN))
        assertNull(SpokenLanguage.ofScript(SourceLanguage.LATIN_SCRIPT))
        assertNull(SpokenLanguage.ofScript(SourceLanguage.CYRILLIC_SCRIPT))
    }

    @Test
    fun `every script has at least one language`() {
        assertEquals(SourceLanguage.entries.toSet(), SpokenLanguage.entries.map { it.script }.toSet())
    }
}

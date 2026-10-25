package com.yomu.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLanguageTest {

    @Test
    fun `a stored code names its source language`() {
        assertEquals(SourceLanguage.JAPANESE, SourceLanguage.fromCode("ja"))
        assertEquals(SourceLanguage.CYRILLIC_SCRIPT, SourceLanguage.fromCode("cyrl"))
        assertNull(SourceLanguage.fromCode("xx"))
        assertNull(SourceLanguage.fromCode(null))
    }

    @Test
    fun `a single language is named in prompts and a script family is not`() {
        assertEquals("Japanese", SourceLanguage.JAPANESE.promptName)
        assertEquals("Chinese", SourceLanguage.CHINESE.promptName)
        assertEquals("Korean", SourceLanguage.KOREAN.promptName)
        assertNull(SourceLanguage.LATIN_SCRIPT.promptName)
        assertNull(SourceLanguage.CYRILLIC_SCRIPT.promptName)
    }

    @Test
    fun `japanese keeps only reads with kana or kanji`() {
        assertTrue(SourceLanguage.JAPANESE.isReadable("こんにちは"))
        assertFalse(SourceLanguage.JAPANESE.isReadable("12:00"))
        assertFalse(SourceLanguage.JAPANESE.isReadable("Hello"))
    }

    @Test
    fun `another source keeps a read with at least two letters`() {
        assertTrue(SourceLanguage.LATIN_SCRIPT.isReadable("Hi!"))
        assertTrue(SourceLanguage.CYRILLIC_SCRIPT.isReadable("Да."))
        assertTrue(SourceLanguage.KOREAN.isReadable("뭐야"))
        assertTrue(SourceLanguage.CHINESE.isReadable("你好"))
        listOf("12:00", "!?", "……", "A", " ").forEach { text ->
            assertFalse(text, SourceLanguage.LATIN_SCRIPT.isReadable(text))
        }
    }

    @Test
    fun `untranslated han or kana is residue only for a source written in them`() {
        assertTrue(SourceLanguage.JAPANESE.leftUntranslated("おはよう, everyone"))
        assertTrue(SourceLanguage.CHINESE.leftUntranslated("你好 друг"))
        assertFalse(SourceLanguage.LATIN_SCRIPT.leftUntranslated("Привет"))
        assertFalse(SourceLanguage.JAPANESE.leftUntranslated("Привет, как дела?"))
    }
}

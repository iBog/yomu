package com.yomu.app.translation

import com.yomu.core.Constants
import com.yomu.core.SourceLanguage
import com.yomu.core.SpokenLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenLanguageStoreTest {
    private val prefs = MapSharedPreferences()
    private val store = SpokenLanguageStore(prefs)

    @Test
    fun `nothing stored is Auto`() {
        assertNull(store.load())
    }

    @Test
    fun `every language and Auto load back`() {
        SpokenLanguage.entries.forEach {
            store.save(it)
            assertEquals(it, SpokenLanguageStore(prefs).load())
        }
        store.save(null)
        assertNull(SpokenLanguageStore(prefs).load())
    }

    @Test
    fun `an unknown stored code is Auto`() {
        prefs.values[Constants.PREF_SPOKEN_LANGUAGE] = "xx"

        assertNull(store.load())
    }

    @Test
    fun `the choice names the prompt only while the OCR model reads its script`() {
        store.save(SpokenLanguage.FRENCH)

        assertEquals(SpokenLanguage.FRENCH, store.effective(SourceLanguage.LATIN_SCRIPT))
        assertNull(store.effective(SourceLanguage.JAPANESE))
    }
}

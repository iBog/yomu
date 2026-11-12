package com.yomu.app

import com.yomu.app.translation.MapSharedPreferences
import com.yomu.core.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class AppLanguageTest {
    private val prefs = MapSharedPreferences()

    @Test
    fun `a saved language loads back`() {
        AppLanguage.entries.forEach {
            AppLanguage.save(prefs, it)
            assertEquals(it, AppLanguage.load(prefs))
        }
    }

    @Test
    fun `an unknown code is no language`() {
        assertNull(AppLanguage.fromCode("xx"))
        assertNull(AppLanguage.fromCode(null))
    }

    @Test
    fun `until chosen the app follows a Russian device and is English otherwise`() {
        val device = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ru"))
            assertEquals(AppLanguage.RUSSIAN, AppLanguage.load(prefs))
            Locale.setDefault(Locale("de"))
            assertEquals(AppLanguage.ENGLISH, AppLanguage.load(prefs))
            prefs.values[Constants.PREF_APP_LANGUAGE] = "xx"
            assertEquals(AppLanguage.ENGLISH, AppLanguage.load(prefs))
        } finally {
            Locale.setDefault(device)
        }
    }
}

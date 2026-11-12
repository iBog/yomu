package com.yomu.app.ui.home

import com.yomu.app.R
import com.yomu.app.ui.UiText
import com.yomu.core.ERROR_ENGLISH_ONLY
import com.yomu.core.TranslationOutcome
import com.yomu.pipeline.translation.TranslatedBubble
import com.yomu.pipeline.translation.TranslationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TryTranslateStateTest {
    private fun result(vararg bubbles: TranslatedBubble, code: String? = null, outcome: TranslationOutcome = TranslationOutcome.SUCCESS) =
        TranslationResult(bubbles.toList(), "", 2500L, outcome, code)

    @Test
    fun `every line's translation is shown with the time it took`() {
        val state = result(TranslatedBubble(0, "a", "One", true), TranslatedBubble(1, "b", "Two", true)).toTryState()

        assertEquals("One\nTwo", state.text)
        assertEquals(2500L, state.millis)
        assertFalse(state.failed)
        assertEquals(null, state.note)
    }

    @Test
    fun `a line the model left unanswered is noted`() {
        val state = result(TranslatedBubble(0, "a", "One", true), TranslatedBubble(1, "b", "b", false)).toTryState()

        assertEquals(UiText(R.string.reader_untranslated_notice, 1, 2), state.note)
    }

    @Test
    fun `an English-only refusal and an empty answer both fail with a reason`() {
        assertTrue(result(code = ERROR_ENGLISH_ONLY).toTryState().failed)
        val empty = result(TranslatedBubble(0, "a", "a", false)).toTryState()
        assertTrue(empty.failed)
        assertEquals(UiText(R.string.try_no_usable_translation), empty.error)
    }
}

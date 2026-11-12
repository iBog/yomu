package com.yomu.app.ui.home

import com.yomu.app.db.entities.ModelStatus
import com.yomu.app.db.entities.ModelType
import android.content.Context
import com.yomu.app.R
import com.yomu.app.service.ModelSlotSelection
import com.yomu.app.service.SlotDeliverable
import com.yomu.app.ui.UiText
import com.yomu.app.ui.settings.deliverableStatus
import com.yomu.core.SpokenLanguage
import com.yomu.core.TargetLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuickSettingsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private val qwen = SlotDeliverable("qwen", "Qwen2.5 1.5B Instruct", 1L, "Apache-2.0", multilingual = true)
    private val cat = SlotDeliverable("cat", "CAT-Translate 1.4B", 1L, "MIT", multilingual = false)
    private val state = HomeUiState(deliverables = mapOf(ModelType.LLM to listOf(qwen, cat)))

    @Test
    fun `the model chip names the selected translation model`() {
        assertEquals(UiText(R.string.home_verbatim, "Qwen2.5 1.5B Instruct"), state.copy(translationSelectedId = "qwen").translationChipLabel())
    }

    @Test
    fun `a pending pick names the model that takes over when its download finishes`() {
        assertEquals(
            UiText(R.string.home_chip_pending, UiText(R.string.home_verbatim, "Qwen2.5 1.5B Instruct"), "CAT-Translate 1.4B"),
            state.copy(translationSelectedId = "qwen", translationPendingId = "cat").translationChipLabel()
        )
    }

    @Test
    fun `an empty translation slot says no model is selected`() {
        assertEquals(UiText(R.string.home_chip_no_model), state.translationChipLabel())
    }

    @Test
    fun `a deliverable's status says whether it is downloaded, in use or taking over`() {
        assertEquals("Not downloaded", deliverableStatus(context, null, null, selected = false, pending = false))
        assertEquals("Not downloaded · Takes over when downloaded", deliverableStatus(context, ModelStatus.AVAILABLE, null, selected = false, pending = true))
        assertEquals("Downloading 40% · Takes over when downloaded", deliverableStatus(context, ModelStatus.DOWNLOADING, 40, selected = false, pending = true))
        assertEquals("Downloaded", deliverableStatus(context, ModelStatus.READY, null, selected = false, pending = false))
        assertEquals("In use", deliverableStatus(context, ModelStatus.READY, null, selected = true, pending = false))
        assertEquals("Couldn't download", deliverableStatus(context, ModelStatus.ERROR, null, selected = false, pending = false))
    }

    @Test
    fun `the language chips name the source, Auto until one is chosen, and the target`() {
        assertEquals("From: Auto", state.spokenChipLabel().resolve(context))
        assertEquals("From: French", state.copy(spoken = SpokenLanguage.FRENCH).spokenChipLabel().resolve(context))
        assertEquals("To: English", state.languageChipLabel().resolve(context))
        assertEquals("To: Spanish", state.copy(targetLanguage = TargetLanguage.SPANISH).languageChipLabel().resolve(context))
    }

    @Test
    fun `an English-only model is unavailable with the reason while the language is not English`() {
        val spanish = state.copy(targetLanguage = TargetLanguage.SPANISH)

        assertEquals("Can't write Spanish", spanish.languageBlock(cat)?.resolve(context))
        assertNull(spanish.languageBlock(qwen))
        assertNull(state.languageBlock(cat))
    }

    @Test
    fun `the language chip offers only English when no Multilingual model fits`() {
        val englishOnly = state.copy(deliverables = mapOf(ModelType.LLM to listOf(cat)))

        assertEquals(ModelSlotSelection.NO_MULTILINGUAL_FIT, englishOnly.languageUnavailable(TargetLanguage.FRENCH))
        assertNull(englishOnly.languageUnavailable(TargetLanguage.ENGLISH))
        assertNull(state.languageUnavailable(TargetLanguage.FRENCH))
    }
}

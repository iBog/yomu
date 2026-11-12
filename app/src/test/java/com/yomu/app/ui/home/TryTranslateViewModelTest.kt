package com.yomu.app.ui.home

import com.yomu.app.translation.MapSharedPreferences
import com.yomu.app.translation.NameGlossaryStore
import com.yomu.core.PageTranslation
import com.yomu.core.SpokenLanguage
import com.yomu.core.TargetLanguage
import com.yomu.core.TranslatablePage
import com.yomu.core.TranslationSlot
import com.yomu.core.TranslationStatus
import com.yomu.pipeline.TranslationPipeline
import com.yomu.pipeline.bubble.BubbleDetector
import com.yomu.pipeline.context.ContextAssembler
import com.yomu.pipeline.ocr.OcrEngine
import com.yomu.pipeline.translation.TranslationEngine
import com.yomu.pipeline.typesetting.Typesetter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class TryTranslateViewModelTest {
    private val prefs = MapSharedPreferences()
    private val pages = mutableListOf<TranslatablePage>()
    private val slot = object : TranslationSlot {
        override val status: TranslationStatus = TranslationStatus.Ready
        override suspend fun ensureReady() = true
        override suspend fun translatePage(page: TranslatablePage, onBubble: (Int, String) -> Unit): PageTranslation {
            pages += page
            return PageTranslation(page.panels.flatten().associate { it.bubbleId to "T:${it.sourceText}" }, "", 1L)
        }
        override fun endSession() = Unit
        override fun close() = Unit
    }

    // The engine asks for the slot on every page, as it does for the reading overlay, so a Settings change reaches the next test.
    private val viewModel = TryTranslateViewModel(
        TranslationPipeline(
            Mockito.mock(BubbleDetector::class.java),
            Mockito.mock(OcrEngine::class.java),
            ContextAssembler(),
            TranslationEngine({ slot }),
            Mockito.mock(Typesetter::class.java)
        ),
        prefs
    )

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the test translates into the language and from the source it is given`() = runTest {
        viewModel.translate("Bonjour", SpokenLanguage.FRENCH, TargetLanguage.RUSSIAN)
        advanceUntilIdle()

        assertEquals(TargetLanguage.RUSSIAN, pages.single().targetLanguage)
        assertEquals(SpokenLanguage.FRENCH, pages.single().spoken)
        assertEquals("T:Bonjour", viewModel.state.value.text)
    }

    @Test
    fun `Auto names no source`() = runTest {
        viewModel.translate("Bonjour", null, TargetLanguage.RUSSIAN)
        advanceUntilIdle()

        assertEquals(null, pages.single().spoken)
    }

    @Test
    fun `the name glossary is applied while its switch is on and not while it is off`() = runTest {
        val glossary = NameGlossaryStore(prefs)
        glossary.save("ルフィ", "Luffy")

        viewModel.translate("ルフィ", null, TargetLanguage.ENGLISH)
        advanceUntilIdle()
        glossary.setEnabled(false)
        viewModel.translate("ルフィ", null, TargetLanguage.ENGLISH)
        advanceUntilIdle()

        assertEquals(listOf("Luffy", "ルフィ"), pages.map { it.panels.flatten().single().sourceText })
    }
}

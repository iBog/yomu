package com.yomu.app.overlay

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import com.yomu.core.TargetLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class QuickSettingsPopupTest {
    private val windowManager = mock(WindowManager::class.java)
    private var releaseOutside: (() -> Unit)? = null
    private var unblocks = 0
    private var unavailable: String? = null
    private val chosen = mutableListOf<TargetLanguage>()
    private val recallChanges = mutableListOf<Boolean>()
    private val recallSwitch = OverlayRecallSwitch(RuntimeEnvironment.getApplication()) { recallChanges += it }
    private var hasLastPage = false
    private var translateAgainRequests = 0
    private val popup = QuickSettingsPopup(
        context = RuntimeEnvironment.getApplication(),
        windowManager = windowManager,
        modelName = { "Model" },
        languageSwitch = OverlayLanguageSwitch(
            RuntimeEnvironment.getApplication(),
            targetLanguage = { TargetLanguage.ENGLISH },
            unavailable = { language -> unavailable.takeIf { language != TargetLanguage.ENGLISH } },
            onChosen = { chosen += it }
        ),
        recallSwitch = recallSwitch,
        onFontSizeChanged = {},
        onThresholdChanged = {},
        hasLastPage = { hasLastPage },
        onTranslateAgainRequested = { translateAgainRequests++ },
        onReportRequested = {},
        onOpenAppRequested = {},
        onStopRequested = {},
        blockTouchesBelow = { releaseOutside = it },
        unblockTouchesBelow = { unblocks++ }
    )

    /** An outside tap must close the popup through the touch layer, not by watching it pass through to the app (#352). */
    @Test
    fun releaseOutsideClosesThePopupAndUnblocksTouches() {
        popup.show(0, 0)
        val params = ArgumentCaptor.forClass(WindowManager.LayoutParams::class.java)
        verify(windowManager).addView(any(View::class.java), params.capture())
        assertEquals(0, params.value.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)

        checkNotNull(releaseOutside).invoke()
        assertEquals(1, unblocks)

        popup.show(0, 0)
        verify(windowManager, times(2)).addView(any(View::class.java), any(WindowManager.LayoutParams::class.java))
    }

    private fun shownContent(): View {
        popup.show(0, 0)
        val view = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(view.capture(), any(WindowManager.LayoutParams::class.java))
        return view.value
    }

    private fun View.withText(text: String): TextView? = when {
        this is TextView && this.text.toString() == text -> this
        this is ViewGroup -> (0 until childCount).firstNotNullOfOrNull { getChildAt(it).withText(text) }
        else -> null
    }

    @Test
    fun theLanguageChipOpensTheLanguagesAndChoosingOneSwitchesToIt() {
        val content = shownContent()
        val spanish = checkNotNull(content.withText("Spanish"))
        val languages = spanish.parent as View
        assertEquals(View.GONE, languages.visibility)

        checkNotNull(content.withText("Japanese → English")).performClick()
        assertEquals(View.VISIBLE, languages.visibility)
        spanish.performClick()

        assertEquals(listOf(TargetLanguage.SPANISH), chosen)
    }

    @Test
    fun otherLanguagesAreDisabledWithTheReasonWhenNoMultilingualModelFits() {
        unavailable = "No Multilingual model fits this device"
        val content = shownContent()

        assertFalse(checkNotNull(content.withText("French · No Multilingual model fits this device")).isEnabled)
        assertTrue((checkNotNull(content.withText("English")) as RadioButton).isChecked)
    }

    private fun View.recallSwitch(): Switch {
        val row = checkNotNull(withText("Page recall")).parent as ViewGroup
        return (0 until row.childCount).map(row::getChildAt).filterIsInstance<Switch>().single()
    }

    /** The switch shows the stored value and only a tap by the reader reports a change (#401). */
    @Test
    fun thePageRecallSwitchShowsTheStoredValueAndReportsOnlyTheReadersFlips() {
        recallSwitch.update(false)
        val content = shownContent()
        val recall = content.recallSwitch()
        assertFalse(recall.isChecked)

        recallSwitch.update(true)
        assertTrue(recall.isChecked)
        assertEquals(emptyList<Boolean>(), recallChanges)

        recall.performClick()
        assertEquals(listOf(false), recallChanges)
    }

    /** Translate again sits above "Report last page" and follows the same rule: enabled only once a page exists (#402). */
    @Test
    fun translateAgainIsDimmedWithoutALastPageAndOtherwiseClosesThePopupAndRetranslates() {
        val content = shownContent()
        val translateAgain = checkNotNull(content.withText("Translate again"))
        val report = checkNotNull(content.withText("Report last page"))
        val actions = translateAgain.parent as ViewGroup
        assertEquals(actions.indexOfChild(report) - 1, actions.indexOfChild(translateAgain))
        assertFalse(translateAgain.isEnabled)

        hasLastPage = true
        popup.refreshLastPageAvailability()
        assertTrue(translateAgain.isEnabled)

        translateAgain.performClick()
        assertEquals(1, translateAgainRequests)
        assertEquals(1, unblocks)
    }
}

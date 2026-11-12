package com.yomu.app.overlay

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import com.yomu.pipeline.typesetting.TypesetBubble
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TranslatedPageLayoutTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup()
    private val context: Context get() = activity.get()

    @After
    fun tearDown() {
        activity.pause().stop().destroy()
    }

    @Test
    fun longCardFitsPageAndScrollsToBothCopyButtons() {
        for (atTop in listOf(true, false)) {
            val page = page(longText = true, atTop = atTop)
            val card = page.card as ScrollView
            assertTrue(card.top >= 0)
            assertTrue(card.bottom <= page.height)
            assertTrue(card.canScrollVertically(1))
            val buttons = buttons(card)
            assertEquals(listOf("Copy Original", "Copy Translation"), buttons.map { it.contentDescription })
            card.isSmoothScrollingEnabled = false
            card.fullScroll(View.FOCUS_DOWN)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(card.scrollY > 0)
            assertTrue(!card.canScrollVertically(1))
        }
    }

    @Test
    fun copyTapsRemainClicksAndSwipesCancelBothButtons() {
        for ((index, longText) in listOf(0 to false, 1 to false, 0 to true, 1 to true)) {
            val page = page(longText = longText)
            val outcomes = mutableListOf<TranslatedPageGestures.Outcome>()
            page.onGesture = { outcomes.add(it) }
            val card = page.card as ScrollView
            val button = buttons(card)[index]
            val content = card.getChildAt(0) as ViewGroup
            val visible = Rect()
            button.getDrawingRect(visible)
            content.offsetDescendantRectToMyCoords(button, visible)
            card.requestChildRectangleOnScreen(content, visible, true)
            var clicks = 0
            button.setOnClickListener { clicks++ }
            val bounds = Rect()
            button.getDrawingRect(bounds)
            page.offsetDescendantRectToMyCoords(button, bounds)
            val x = bounds.exactCenterX()
            val y = bounds.exactCenterY()
            touch(page, MotionEvent.ACTION_DOWN, x, y)
            touch(page, MotionEvent.ACTION_MOVE, x - 1, y - 1)
            touch(page, MotionEvent.ACTION_UP, x - 1, y - 1)
            assertEquals(1, clicks)
            assertTrue(outcomes.none { it == TranslatedPageGestures.Outcome.DismissPage })
            touch(page, MotionEvent.ACTION_DOWN, x, y)
            touch(page, MotionEvent.ACTION_MOVE, x - 100, y)
            touch(page, MotionEvent.ACTION_UP, x - 100, y)
            assertEquals(1, clicks)
            assertTrue(outcomes.contains(TranslatedPageGestures.Outcome.DismissPage))
        }
    }

    @Test
    fun verticalDragOnLongCardScrollsWithoutDismissing() {
        val page = page(longText = true)
        val outcomes = mutableListOf<TranslatedPageGestures.Outcome>()
        page.onGesture = { outcomes.add(it) }
        val card = page.card as ScrollView
        val x = card.width / 2f
        val y = card.top + card.height / 2f
        touch(page, MotionEvent.ACTION_DOWN, x, y)
        touch(page, MotionEvent.ACTION_MOVE, x, y - 100)
        touch(page, MotionEvent.ACTION_MOVE, x, y - 200)
        touch(page, MotionEvent.ACTION_UP, x, y - 200)
        assertTrue(card.scrollY > 0)
        assertTrue(outcomes.none { it == TranslatedPageGestures.Outcome.DismissPage })
    }

    private fun page(longText: Boolean = false, atTop: Boolean = true): TranslatedPageLayout {
        val text = if (longText) "Long bubble text\n".repeat(300) else "Text"
        val bubble = TypesetBubble(1, text, text, 16f, listOf(text), floatArrayOf(0f, 0f, 100f, 100f))
        return TranslatedPageLayout(context).apply {
            card = bubbleCard(context, bubble, atTop).also(::addView)
            activity.get().setContentView(this)
            shadowOf(Looper.getMainLooper()).idle()
            measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1080, 1600)
        }
    }

    private fun buttons(view: ViewGroup): List<Button> = (0 until view.childCount).flatMap { index ->
        when (val child = view.getChildAt(index)) {
            is Button -> listOf(child)
            is ViewGroup -> buttons(child)
            else -> emptyList()
        }
    }

    private fun touch(page: View, action: Int, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, time, action, x, y, 0)
        try {
            page.dispatchTouchEvent(event)
            shadowOf(Looper.getMainLooper()).idle()
        } finally {
            event.recycle()
        }
    }
}

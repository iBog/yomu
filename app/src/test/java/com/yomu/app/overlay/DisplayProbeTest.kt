package com.yomu.app.overlay

import android.graphics.Point
import android.view.View
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DisplayProbeTest {
    private val windowManager = mock(WindowManager::class.java)
    private val heard = mutableListOf<Pair<Point, Boolean>>()
    private val probe = DisplayProbe(RuntimeEnvironment.getApplication(), windowManager) { size, first -> heard += size to first }

    private fun attachedView(): View {
        probe.attach()
        val captor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(captor.capture(), org.mockito.ArgumentMatchers.any(WindowManager.LayoutParams::class.java))
        return captor.value
    }

    @Test
    fun `it knows nothing until it has been laid out`() {
        attachedView()

        assertNull(probe.size)
        assertEquals(emptyList<Pair<Point, Boolean>>(), heard)
    }

    @Test
    fun `it reports the display as laid out, and again when the screen turns`() {
        val view = attachedView()

        view.layout(0, 0, 1272, 2772)
        view.layout(0, 0, 2772, 1272)

        assertEquals(Point(2772, 1272), probe.size)
        assertEquals(listOf(Point(1272, 2772) to true, Point(2772, 1272) to false), heard)
    }

    @Test
    fun `an empty layout is not a size`() {
        val view = attachedView()

        view.layout(0, 0, 0, 0)

        assertNull(probe.size)
    }

    @Test
    fun `detaching removes the window and forgets the size`() {
        val view = attachedView()
        view.layout(0, 0, 1272, 2772)

        probe.detach()

        verify(windowManager).removeView(view)
        assertNull(probe.size)
    }
}

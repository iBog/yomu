package com.yomu.app.capture

import android.content.Context
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xxhdpi")
class DisplaySizeTest {
    private fun size(): android.graphics.Point {
        val context = RuntimeEnvironment.getApplication()
        return realDisplaySize(context, context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
    }

    @Test
    fun `the display size follows a turn of the screen, as seen from an application context`() {
        val portrait = size()
        assertTrue("portrait ${portrait.x}x${portrait.y}", portrait.y > portrait.x)

        RuntimeEnvironment.setQualifiers("w891dp-h411dp-land-xxhdpi")
        val landscape = size()

        assertTrue("landscape ${landscape.x}x${landscape.y}", landscape.x > landscape.y)
    }

    @Test
    fun `the sides of a display are laid out by the orientation, whichever way round the display reports them`() {
        val portrait = android.content.res.Configuration.ORIENTATION_PORTRAIT
        val landscape = android.content.res.Configuration.ORIENTATION_LANDSCAPE

        // A phone that keeps reporting 1272 x 2772 after it is turned still gets the landscape frame.
        assertEquals(android.graphics.Point(2772, 1272), orientedSize(1272, 2772, landscape))
        assertEquals(android.graphics.Point(2772, 1272), orientedSize(2772, 1272, landscape))
        assertEquals(android.graphics.Point(1272, 2772), orientedSize(2772, 1272, portrait))
        assertEquals(android.graphics.Point(1272, 2772), orientedSize(1272, 2772, portrait))
        assertEquals(android.graphics.Point(1272, 2772), orientedSize(1272, 2772, android.content.res.Configuration.ORIENTATION_UNDEFINED))
    }
}

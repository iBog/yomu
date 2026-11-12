package com.yomu.app.capture

import android.content.Context
import android.view.WindowManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xxhdpi")
class DisplaySizeTest {
    private fun size() = realDisplaySize(RuntimeEnvironment.getApplication().getSystemService(Context.WINDOW_SERVICE) as WindowManager)

    @Test
    fun `the display size follows a turn of the screen, as seen from an application context`() {
        val portrait = size()
        assertTrue("portrait ${portrait.x}x${portrait.y}", portrait.y > portrait.x)

        RuntimeEnvironment.setQualifiers("w891dp-h411dp-land-xxhdpi")
        val landscape = size()

        assertTrue("landscape ${landscape.x}x${landscape.y}", landscape.x > landscape.y)
    }
}

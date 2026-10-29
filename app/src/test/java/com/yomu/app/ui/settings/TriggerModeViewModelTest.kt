package com.yomu.app.ui.settings

import com.yomu.app.service.TriggerMode
import com.yomu.app.service.TriggerModeStore
import com.yomu.app.translation.MapSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class TriggerModeViewModelTest {

    private val prefs = MapSharedPreferences()

    @Test
    fun `the screen opens on the stored mode`() {
        TriggerModeStore(prefs).save(TriggerMode.NOTIFICATION)

        assertEquals(TriggerMode.NOTIFICATION, TriggerModeViewModel(prefs).mode.value)
    }

    @Test
    fun `a chosen mode shows at once and is kept`() {
        val viewModel = TriggerModeViewModel(prefs)

        viewModel.choose(TriggerMode.NOTIFICATION)

        assertEquals(TriggerMode.NOTIFICATION, viewModel.mode.value)
        assertEquals(TriggerMode.NOTIFICATION, TriggerModeStore(prefs).load())
    }
}

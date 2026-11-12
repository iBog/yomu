package com.yomu.app.ui.settings

import android.content.Context
import com.yomu.app.R
import com.yomu.app.service.SlotDeliverable
import com.yomu.app.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SlotPickerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val gb = 1024L * 1024 * 1024

    @Test
    fun `a translation model's detail line shows its language label after the speed label`() {
        val qwen = SlotDeliverable("qwen", "Qwen2.5 1.5B Instruct", gb, "Apache-2.0", multilingual = true)
        val cat = SlotDeliverable("cat", "CAT-Translate 1.4B", gb, "MIT", experimental = true, multilingual = false)

        assertEquals("1.00 GB · Fast · Multilingual · Apache-2.0 · Fits this device", deliverableDetail(context, qwen, fits = true))
        assertEquals("1.00 GB · Fast · English only · MIT · Experimental · Needs more memory", deliverableDetail(context, cat, fits = false))
    }

    @Test
    fun `a reading model's detail line carries no language label`() {
        val ocr = SlotDeliverable("ocr", "manga-ocr", gb, "Apache-2.0 (manga-ocr)")

        assertEquals("1.00 GB · Fast · Apache-2.0 (manga-ocr) · Fits this device", deliverableDetail(context, ocr, fits = true))
    }

    @Test
    fun `an entry blocked by the target language says why`() {
        val cat = SlotDeliverable("cat", "CAT-Translate 1.4B", gb, "MIT", multilingual = false)

        assertEquals(
            "1.00 GB · Fast · English only · MIT · Fits this device · Can't write Spanish",
            deliverableDetail(context, cat, fits = true, languageBlock = UiText(R.string.language_block, UiText(R.string.language_es)))
        )
    }
}

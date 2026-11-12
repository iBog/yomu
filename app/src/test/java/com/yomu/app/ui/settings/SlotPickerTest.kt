package com.yomu.app.ui.settings

import android.content.Context
import com.yomu.app.R
import com.yomu.app.db.entities.ModelStatus
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
    fun `a deliverable's status says whether it is downloaded, in use or taking over`() {
        assertEquals("Not downloaded", deliverableStatus(context, null, null, selected = false, pending = false))
        assertEquals("Not downloaded · Takes over when downloaded", deliverableStatus(context, ModelStatus.AVAILABLE, null, selected = false, pending = true))
        assertEquals("Downloading 40% · Takes over when downloaded", deliverableStatus(context, ModelStatus.DOWNLOADING, 40, selected = false, pending = true))
        assertEquals("Downloaded", deliverableStatus(context, ModelStatus.READY, null, selected = false, pending = false))
        assertEquals("In use", deliverableStatus(context, ModelStatus.READY, null, selected = true, pending = false))
        assertEquals("Couldn't download", deliverableStatus(context, ModelStatus.ERROR, null, selected = false, pending = false))
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

package com.yomu.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadingNotificationContentTest {

    @Test
    fun `with the floating button the notification only points at it`() {
        val content = readingNotificationContent(TriggerMode.FLOATING_BUTTON, translating = false)

        assertNull(content.tap)
        assertEquals(emptyList<ShadeAction>(), content.actions)
    }

    @Test
    fun `from the shade a tap translates and reading can be stopped`() {
        val content = readingNotificationContent(TriggerMode.NOTIFICATION, translating = false)

        assertEquals(ShadeAction.TRANSLATE, content.tap)
        assertEquals(listOf(ShadeAction.TRANSLATE, ShadeAction.STOP), content.actions)
    }

    @Test
    fun `from the shade a page in progress can be cancelled`() {
        val content = readingNotificationContent(TriggerMode.NOTIFICATION, translating = true)

        assertEquals(ShadeAction.CANCEL, content.tap)
        assertEquals(listOf(ShadeAction.CANCEL, ShadeAction.STOP), content.actions)
    }
}

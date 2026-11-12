package com.yomu.ml

import com.yomu.core.SourceLanguage
import com.yomu.core.SpokenLanguage
import com.yomu.core.TargetLanguage
import com.yomu.core.TranslatableBubble
import com.yomu.core.TranslatablePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatchPromptTest {
    private val page = TranslatablePage(
        listOf(listOf(TranslatableBubble(1, "Hello")), listOf(TranslatableBubble(2, "Bye"))),
        TargetLanguage.RUSSIAN,
        SourceLanguage.LATIN_SCRIPT,
        SpokenLanguage.ENGLISH
    )

    @Test
    fun `the batch prompt names the source and target, tags each line and separates panels`() {
        assertEquals(
            "Translate each English line to Russian. Keep the [id] tag before each line. Panels are separated by ---.\n" +
                "[1] Hello\n---\n[2] Bye\n",
            page.batchPrompt()
        )
    }

    @Test
    fun `an unnamed source is left out of the prompt`() {
        assertEquals(
            "Translate each line to Russian. Keep the [id] tag before each line. Panels are separated by ---.\n[1] Hello\n---\n[2] Bye\n",
            page.copy(spoken = null).batchPrompt()
        )
    }

    @Test
    fun `an id-keyed line gives its id and trimmed text`() {
        assertEquals(7 to "Привет", parseIdKeyedLine("  [7]   Привет "))
        assertNull(parseIdKeyedLine("Sure, here you go:"))
    }
}

package com.yomu.pipeline.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class LineOcrDecodingTest {

    @Test
    fun `the character dictionary is read from the model's inference config`() {
        val yml = """
            Global:
              model_name: latin_PP-OCRv5_mobile_rec
            PostProcess:
              name: CTCLabelDecode
              character_dict:
              - '0'
              - A
              - ''''
              - '"'
              - é
              - 　
            Other:
              key: value
        """.trimIndent().lines()

        assertEquals(listOf("0", "A", "'", "\"", "é", "　"), parseCharacterDict(yml.asSequence()))
    }

    @Test
    fun `a config without a dictionary gives none`() {
        assertEquals(emptyList<String>(), parseCharacterDict(sequenceOf("Global:", "  model_name: x")))
    }

    @Test
    fun `ctc keeps one character per run, skips blanks and maps the class past the dictionary to a space`() {
        val dict = listOf("H", "i", "!")
        // Classes: 0 blank, 1..3 the dictionary, 4 the appended space.
        val steps = listOf(1, 1, 0, 2, 4, 3, 3, 0, 0)
        val probs = FloatArray(steps.size * 5).also { p ->
            steps.forEachIndexed { t, cls -> p[t * 5 + cls] = 0.9f }
        }

        val decoded = decodeCtc(probs, classes = 5, dict = dict)

        assertEquals("Hi !", decoded.text)
        assertEquals(0.9f, decoded.confidence, 1e-6f)
    }

    @Test
    fun `a decode that emits nothing has no confidence`() {
        val decoded = decodeCtc(FloatArray(3 * 2) { if (it % 2 == 0) 1f else 0f }, classes = 2, dict = listOf("x"))

        assertEquals("", decoded.text)
        assertEquals(0f, decoded.confidence, 0f)
    }

    @Test
    fun `ink rows split into lines across blank gaps, dropping specks`() {
        val ink = listOf(0, 3, 4, 5, 0, 0, 0, 2, 6, 6, 1, 0, 9, 0, 0, 7, 7, 7, 7, 0).map { it >= 2 }

        assertEquals(listOf(1..3, 7..9, 15..18), textLineBands(ink, minHeight = 2))
    }

    @Test
    fun `a one-row gap inside a line does not split it`() {
        val ink = listOf(true, true, false, true, true, false, false, true, true)

        assertEquals(listOf(0..4, 7..8), textLineBands(ink, minHeight = 2))
    }

    @Test
    fun `lines join with spaces, except where the script runs words together or a word was hyphenated`() {
        assertEquals("WHAT ARE YOU DOING HERE?", joinLines(listOf("WHAT ARE YOU", "DOING HERE?"), spaced = true))
        assertEquals("incredible", joinLines(listOf("incre-", "dible"), spaced = true))
        assertEquals("你在这里做什么?", joinLines(listOf("你在这里", "做什么?"), spaced = false))
    }
}

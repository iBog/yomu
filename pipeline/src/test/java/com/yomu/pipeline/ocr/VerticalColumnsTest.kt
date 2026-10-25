package com.yomu.pipeline.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class VerticalColumnsTest {

    private val width = 200
    private val height = 120

    /** A white page with a dark block over each of [blocks], given as x range to y range. */
    private fun page(blocks: List<Pair<IntRange, IntRange>>, paper: Int = 255, ink: Int = 0): IntArray =
        IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (blocks.any { (xs, ys) -> x in xs && y in ys }) ink else paper
        }

    /** [count] tall columns, 10 px wide with 6 px gaps, from x = 4. */
    private fun columns(count: Int): List<Pair<IntRange, IntRange>> =
        (0 until count).map { c -> (4 + c * 16 until 14 + c * 16) to (10 until 110) }

    @Test
    fun `a block of vertical text is read two columns at a time, right to left`() {
        val groups = verticalColumnGroups(page(columns(5)), width, height)

        assertEquals(listOf(52..77, 20..45, 4..13), groups)
    }

    @Test
    fun `a bubble of three columns or fewer is read whole`() {
        assertEquals(emptyList<IntRange>(), verticalColumnGroups(page(columns(3)), width, height))
    }

    @Test
    fun `a horizontal line, whose characters are as wide as they are tall, is read whole`() {
        val characters = (0 until 8).map { c -> (4 + c * 20 until 20 + c * 20) to (50 until 66) }

        assertEquals(emptyList<IntRange>(), verticalColumnGroups(page(characters), width, height))
    }

    @Test
    fun `light text on a dark box is split like dark text on light`() {
        val groups = verticalColumnGroups(page(columns(4), paper = 20, ink = 240), width, height)

        assertEquals(listOf(36..61, 4..29), groups)
    }

    @Test
    fun `specks narrower than a stroke do not count as columns`() {
        val specks = (0 until 6).map { c -> (100 + c * 10..100 + c * 10) to (10 until 110) }

        assertEquals(emptyList<IntRange>(), verticalColumnGroups(page(columns(3) + specks), width, height))
    }

    @Test
    fun `column reads join in reading order and keep the least sure confidence`() {
        val joined = joinColumnReads(
            listOf(OcrResult("これは", 0.98f, FloatArray(4)), OcrResult("絶対に", 0.91f, FloatArray(4)))
        )

        assertEquals("これは絶対に", joined.text)
        assertEquals(0.91f, joined.confidence)
    }
}

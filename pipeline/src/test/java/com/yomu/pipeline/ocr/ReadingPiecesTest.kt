package com.yomu.pipeline.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingPiecesTest {

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
        val joined = joinPieceReads(
            listOf(OcrResult("これは", 0.98f, FloatArray(4)), OcrResult("絶対に", 0.91f, FloatArray(4)))
        )

        assertEquals("これは絶対に", joined.text)
        assertEquals(0.91f, joined.confidence)
    }

    /** One horizontal line of [count] square characters, 16 px with 4 px gaps, from x = 4, across a [lineWidth]-wide strip. */
    private fun line(count: Int, lineWidth: Int, paper: Int = 255, ink: Int = 0): Pair<IntArray, Int> {
        val stripHeight = 48
        val pixels = IntArray(lineWidth * stripHeight) { i ->
            val x = i % lineWidth
            val y = i / lineWidth
            val inCharacter = x >= 4 && (x - 4) % 20 < 16 && (x - 4) / 20 < count && y in 16 until 32
            if (inCharacter) ink else paper
        }
        return pixels to stripHeight
    }

    @Test
    fun `a long single line is read in pieces cut between characters`() {
        val (pixels, stripHeight) = line(count = 30, lineWidth = 620)

        val pieces = longLinePieces(pixels, 620, stripHeight)

        assertEquals(4, pieces.first().first)
        assertEquals(4 + 30 * 20 - 4 - 1, pieces.last().last)
        pieces.zipWithNext().forEach { (a, b) ->
            assertEquals(a.last + 1, b.first)
            // Every cut falls in the 4 px gap between two characters.
            assertTrue("${b.first}", (b.first - 4) % 20 >= 16)
        }
        assertTrue(pieces.size in 6..9)
    }

    @Test
    fun `a line too short to be squeezed out of shape is read whole`() {
        val (pixels, stripHeight) = line(count = 6, lineWidth = 140)

        assertEquals(emptyList<IntRange>(), longLinePieces(pixels, 140, stripHeight))
    }

    @Test
    fun `a block of two or more lines is read whole`() {
        val lineWidth = 620
        val pixels = IntArray(lineWidth * 96) { i ->
            val x = i % lineWidth
            val y = i / lineWidth
            val inCharacter = x >= 4 && (x - 4) % 20 < 16 && x < 600
            if (inCharacter && (y in 16 until 32 || y in 56 until 72)) 0 else 255
        }

        assertEquals(emptyList<IntRange>(), longLinePieces(pixels, lineWidth, 96))
    }

    @Test
    fun `a long line of light text on a dark bar is cut like dark text on light`() {
        val (dark, stripHeight) = line(count = 30, lineWidth = 620)
        val (light, _) = line(count = 30, lineWidth = 620, paper = 20, ink = 240)

        assertEquals(longLinePieces(dark, 620, stripHeight), longLinePieces(light, 620, stripHeight))
    }

    @Test
    fun `vertical columns are split before long lines are looked for`() {
        assertEquals(verticalColumnGroups(page(columns(5)), width, height), readingPieces(page(columns(5)), width, height))
        val (pixels, stripHeight) = line(count = 30, lineWidth = 620)
        assertEquals(longLinePieces(pixels, 620, stripHeight), readingPieces(pixels, 620, stripHeight))
    }
}

package com.yomu.pipeline.ocr

import android.graphics.Bitmap

// manga-ocr squeezes a crop to 224 px, so a caption of many vertical columns shrinks its glyphs past
// reading: a 13-column narration box read whole scored 0.74 and came back garbled, while the same box
// read two columns at a time scored 0.93 at worst and read cleanly. Bubbles of up to three columns
// are left whole, as manga-ocr reads them.
private const val MIN_COLUMNS = 4
private const val COLUMNS_PER_GROUP = 2
private const val INK_LUMINANCE = 128
// A column is ink when this share of its pixels is dark, so a stray speck does not open one.
private const val INK_COLUMN_SHARE = 0.005f
// Narrower than a stroke: a balloon outline or a speck, not a column of text.
private const val MIN_COLUMN_WIDTH = 3
// A vertical column's ink is several times taller than wide; a horizontal line's characters are about square.
private const val MIN_COLUMN_TALLNESS = 2.5f

/**
 * The x ranges to read [luminance] in, right to left, [COLUMNS_PER_GROUP] vertical columns each; empty when
 * it is not a block of at least [MIN_COLUMNS] vertical columns, and is read whole.
 */
internal fun verticalColumnGroups(luminance: IntArray, width: Int, height: Int): List<IntRange> {
    // Light text on a dark box reads as dark text on light once inverted.
    val inverted = luminance.average() < INK_LUMINANCE
    fun ink(x: Int, y: Int): Boolean = luminance[y * width + x].let { if (inverted) 255 - it else it } < INK_LUMINANCE
    val minInk = maxOf(1, (height * INK_COLUMN_SHARE).toInt())
    val inkColumns = (0 until width).map { x -> (0 until height).count { y -> ink(x, y) } >= minInk }
    val columns = textLineBands(inkColumns, MIN_COLUMN_WIDTH)
    if (columns.size < MIN_COLUMNS) return emptyList()
    val tallness = columns.map { band ->
        val rows = (0 until height).filter { y -> band.any { x -> ink(x, y) } }
        (rows.last() - rows.first() + 1).toFloat() / (band.last - band.first + 1)
    }.sorted()
    if (tallness[tallness.size / 2] < MIN_COLUMN_TALLNESS) return emptyList()
    return columns.reversed().chunked(COLUMNS_PER_GROUP).map { group -> group.last().first..group.first().last }
}

/** One bubble's column reads as one: their text in reading order, and the least sure read's confidence. */
internal fun joinColumnReads(reads: List<OcrResult>): OcrResult = OcrResult(
    text = reads.joinToString("") { it.text },
    confidence = reads.minOf { it.confidence },
    boundingBox = reads.first().boundingBox
)

/** [bitmap]'s pixels as luminance 0–255, row by row. */
internal fun luminanceOf(bitmap: Bitmap): IntArray {
    val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
    return IntArray(pixels.size) { i ->
        val p = pixels[i]
        (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }
}

package com.yomu.pipeline.ocr

import android.graphics.Bitmap

// manga-ocr squeezes a crop to 224 x 224 px, so text far from square shrinks past reading. A 13-column
// narration box read whole scored 0.74 and came back garbled; read two columns at a time it scored 0.93 at
// worst and read cleanly. A one-line caption 25 times wider than tall did the same, 0.68 whole and 0.93 cut
// into pieces about five characters long. Bubbles of up to three columns and short lines are read whole.
private const val MIN_COLUMNS = 4
private const val COLUMNS_PER_GROUP = 2
private const val INK_LUMINANCE = 128
// A column or row is ink when this share of its pixels is dark, so a stray speck does not open one.
private const val INK_SHARE = 0.005f
// Narrower than a stroke: a balloon outline or a speck, not a column of text.
private const val MIN_COLUMN_WIDTH = 3
// A vertical column's ink is several times taller than wide; a horizontal line's characters are about square.
private const val MIN_COLUMN_TALLNESS = 2.5f
private const val MIN_LINE_HEIGHT = 4
// "覚悟なさい!" at 5.4 times its height reads whole at 0.9998, so only longer lines are cut.
private const val MIN_LINE_ASPECT = 8f
// A piece is about this many line heights wide, cut at the widest gap within a quarter of that.
private const val PIECE_ASPECT = 5f
private const val PIECE_SLACK = 0.25f
// A tail shorter than half a piece is kept on the last piece: read alone, a lone "!" came back as "あの子".
private const val MAX_LAST_PIECE = 1.5f

/** The x ranges to read [luminance] in, in reading order; empty when it is read whole. */
internal fun readingPieces(luminance: IntArray, width: Int, height: Int): List<IntRange> =
    verticalColumnGroups(luminance, width, height).ifEmpty { longLinePieces(luminance, width, height) }

/**
 * The x ranges to read [luminance] in, right to left, [COLUMNS_PER_GROUP] vertical columns each; empty when
 * it is not a block of at least [MIN_COLUMNS] vertical columns.
 */
internal fun verticalColumnGroups(luminance: IntArray, width: Int, height: Int): List<IntRange> {
    val ink = inkMask(luminance)
    val minInk = maxOf(1, (height * INK_SHARE).toInt())
    val inkColumns = (0 until width).map { x -> (0 until height).count { y -> ink[y * width + x] } >= minInk }
    val columns = textLineBands(inkColumns, MIN_COLUMN_WIDTH)
    if (columns.size < MIN_COLUMNS) return emptyList()
    val tallness = columns.map { band ->
        val rows = (0 until height).filter { y -> band.any { x -> ink[y * width + x] } }
        (rows.last() - rows.first() + 1).toFloat() / (band.last - band.first + 1)
    }.sorted()
    if (tallness[tallness.size / 2] < MIN_COLUMN_TALLNESS) return emptyList()
    return columns.reversed().chunked(COLUMNS_PER_GROUP).map { group -> group.last().first..group.first().last }
}

/**
 * The x ranges, left to right, to read a single horizontal line in when it is at least [MIN_LINE_ASPECT] times
 * wider than tall, each cut in a gap between characters; empty for anything else.
 */
internal fun longLinePieces(luminance: IntArray, width: Int, height: Int): List<IntRange> {
    val ink = inkMask(luminance)
    val minInk = maxOf(1, (width * INK_SHARE).toInt())
    val inkRows = (0 until height).map { y -> (0 until width).count { x -> ink[y * width + x] } >= minInk }
    val line = textLineBands(inkRows, MIN_LINE_HEIGHT).singleOrNull() ?: return emptyList()
    val lineHeight = (line.last - line.first + 1).toFloat()
    val inkColumns = (0 until width).map { x -> line.any { y -> ink[y * width + x] } }
    val left = inkColumns.indexOf(true)
    val right = inkColumns.lastIndexOf(true)
    if ((right - left + 1) < MIN_LINE_ASPECT * lineHeight) return emptyList()

    val gaps = textLineBands((left..right).map { !inkColumns[it] }, 1).map { (it.first + left)..(it.last + left) }
    val target = PIECE_ASPECT * lineHeight
    val cuts = mutableListOf(left)
    while (right + 1 - cuts.last() > target * MAX_LAST_PIECE) {
        val from = cuts.last()
        val gap = gaps
            .filter { (it.first + it.last) / 2f - from in target * (1 - PIECE_SLACK)..target * (1 + PIECE_SLACK) }
            .maxByOrNull { it.last - it.first } ?: break
        cuts += (gap.first + gap.last + 1) / 2
    }
    return (cuts + (right + 1)).zipWithNext { start, end -> start until end }
}

/** One bubble's piece reads as one: their text in reading order, and the least sure read's confidence. */
internal fun joinPieceReads(reads: List<OcrResult>): OcrResult = OcrResult(
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

// Light text on a dark box reads as dark text on light once inverted.
private fun inkMask(luminance: IntArray): BooleanArray {
    val inverted = luminance.average() < INK_LUMINANCE
    return BooleanArray(luminance.size) { i -> (if (inverted) 255 - luminance[i] else luminance[i]) < INK_LUMINANCE }
}

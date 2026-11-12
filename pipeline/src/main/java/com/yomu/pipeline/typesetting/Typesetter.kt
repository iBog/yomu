package com.yomu.pipeline.typesetting

import android.graphics.Paint
import android.graphics.Typeface
import com.yomu.pipeline.translation.TranslatedBubble

data class TypesetBubble(
    val bubbleId: Int,
    val translatedText: String,
    val originalText: String,
    val fontSize: Float,
    val textLines: List<String>,
    val boundingBox: FloatArray,
    val backgroundColor: Int = 0xF0FFFFFF.toInt(),
    val textColor: Int = 0xFF000000.toInt()
) {
    // Typesetting fits the box against this height, so rendering has to draw with it too.
    val lineHeight: Float get() = fontSize * LINE_HEIGHT_RATIO

    companion object {
        const val LINE_HEIGHT_RATIO = 1.5f
    }
}

class Typesetter(var fontSizeScale: Float = 1.0f) {

    companion object {
        private const val MIN_FONT_SIZE = 10f
        private const val MAX_FONT_SIZE = 36f
        // A box with a short side past MAX_FONT_SIZE / this is not a manga bubble but a text panel (a visual novel's
        // dialogue box, a caption): its text may grow with the box instead of floating in padding at the bubble cap.
        private const val MAX_FONT_BOX_FRACTION = 0.12f
        // A box narrower than its longest word at this size is widened rather than breaking the
        // word letter by letter (a tall text box around vertical はい rendered "Y / e / s").
        private const val MIN_WORD_FONT_SIZE = 20f
        // CJK punctuation, kana and ideographs all sit at or above U+3000.
        private const val CJK_START = 0x3000
        private const val PADDING = 0.1f
        // A text panel is a plain rectangle, not an ellipse the text must keep clear of, so it needs little margin.
        private const val PANEL_PADDING = 0.04f
        // Fixed growth budget, take page bounds into the Typesetter if bubbles need
        // to grow against real page room rather than a multiple of their own box.
        private const val MAX_BOX_GROWTH = 3f
        private const val ELLIPSIS = "\u2026"
        private val OCR_TOKEN_REGEX = Regex("""\[(CLS|SEP|PAD)\]""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
    }

    private val paint = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.DEFAULT
    }

    fun typeset(
        translations: List<TranslatedBubble>,
        bubbleBounds: Map<Int, FloatArray>
    ): List<TypesetBubble> {
        return translations.map { translation ->
            val bounds = bubbleBounds[translation.bubbleId]
                ?: floatArrayOf(0f, 0f, 100f, 50f)

            val cleaned = cleanText(translation.translatedText)
            val padding = paddingFor(bounds)
            val boxWidth = (bounds[2] - bounds[0]) * (1 - padding * 2)
            val maxHeight = (bounds[3] - bounds[1]) * (1 - padding * 2)
            val searchWidth = maxOf(boxWidth, longestWordWidth(cleaned, MIN_WORD_FONT_SIZE))
            val fontCap = maxOf(MAX_FONT_SIZE, minOf(bounds[2] - bounds[0], bounds[3] - bounds[1]) * MAX_FONT_BOX_FRACTION)
            val fontSize = calculateFontSize(cleaned, searchWidth, maxHeight, fontCap)
            // fontSizeScale can push a word past the searched width, so widen to the final size too.
            val maxWidth = maxOf(searchWidth, longestWordWidth(cleaned, fontSize))
            val lines = fitLines(wrapText(cleaned, maxWidth, fontSize), fontSize, maxWidth, maxHeight)
            val textHeight = lineHeight(fontSize) * lines.size

            TypesetBubble(
                bubbleId = translation.bubbleId,
                translatedText = cleaned,
                originalText = translation.originalText,
                fontSize = fontSize,
                textLines = lines,
                boundingBox = grownBounds(bounds, maxWidth, textHeight, padding)
            )
        }
    }

    private fun cleanText(text: String): String =
        OCR_TOKEN_REGEX.replace(text, "")
            .let { WHITESPACE_REGEX.replace(it.trim(), " ") }

    private fun calculateFontSize(text: String, maxWidth: Float, maxHeight: Float, maxSize: Float): Float {
        var low = MIN_FONT_SIZE
        var high = maxSize
        var bestSize = MIN_FONT_SIZE

        while (low <= high) {
            val mid = (low + high) / 2
            val lines = wrapText(text, maxWidth, mid)
            val totalHeight = lineHeight(mid) * lines.size

            if (totalHeight <= maxHeight && longestWordWidth(text, mid) <= maxWidth) {
                bestSize = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return (bestSize * fontSizeScale).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE * 2f)
    }

    private fun lineHeight(fontSize: Float): Float = fontSize * TypesetBubble.LINE_HEIGHT_RATIO

    // Japanese has no spaces and breaks anywhere, so untranslated source text (the per-bubble
    // fallback) is left to wrap by character instead of widening the box to one long line.
    private fun longestWordWidth(text: String, fontSize: Float): Float {
        paint.textSize = fontSize
        return text.split(" ")
            .filter { word -> word.none { it.code >= CJK_START } }
            .maxOfOrNull { paint.measureText(it) } ?: 0f
    }

    /** The margin kept inside [bounds]: little for a text panel, more for a bubble. */
    internal fun paddingFor(bounds: FloatArray): Float =
        if (isPanel(bounds)) PANEL_PADDING else PADDING

    private fun isPanel(bounds: FloatArray): Boolean =
        minOf(bounds[2] - bounds[0], bounds[3] - bounds[1]) * MAX_FONT_BOX_FRACTION > MAX_FONT_SIZE

    private fun grownBounds(bounds: FloatArray, textWidth: Float, textHeight: Float, padding: Float): FloatArray {
        val width = maxOf(bounds[2] - bounds[0], textWidth / (1 - padding * 2))
        val height = maxOf(bounds[3] - bounds[1], textHeight / (1 - padding * 2))
        val centerX = (bounds[0] + bounds[2]) / 2f
        val centerY = (bounds[1] + bounds[3]) / 2f
        return floatArrayOf(centerX - width / 2f, centerY - height / 2f, centerX + width / 2f, centerY + height / 2f)
    }

    private fun wrapText(text: String, maxWidth: Float, fontSize: Float): List<String> {
        paint.textSize = fontSize
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            if (paint.measureText(word) > maxWidth) {
                if (currentLine.isNotEmpty()) {
                    lines.add(currentLine.toString())
                    currentLine = StringBuilder()
                }
                var chunk = StringBuilder()
                for (ch in word) {
                    val test = chunk.toString() + ch
                    if (paint.measureText(test) <= maxWidth) {
                        chunk.append(ch)
                    } else {
                        if (chunk.isNotEmpty()) lines.add(chunk.toString())
                        chunk = StringBuilder(ch.toString())
                    }
                }
                if (chunk.isNotEmpty()) currentLine = chunk
            } else {
                val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                if (paint.measureText(testLine) <= maxWidth) {
                    currentLine.append(if (currentLine.isNotEmpty()) " " else "").append(word)
                } else {
                    if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
                    currentLine = StringBuilder(word)
                }
            }
        }

        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
        return lines
    }

    // Past the growth budget the cut is marked, so lost text is visible instead of silently dropped.
    private fun fitLines(
        lines: List<String>,
        fontSize: Float,
        maxWidth: Float,
        maxHeight: Float
    ): List<String> {
        val maxLines = maxOf(1, (maxHeight * MAX_BOX_GROWTH / lineHeight(fontSize)).toInt())
        if (lines.size <= maxLines) return lines
        return lines.take(maxLines).toMutableList().also {
            it[it.lastIndex] = ellipsize(it.last(), fontSize, maxWidth)
        }
    }

    private fun ellipsize(line: String, fontSize: Float, maxWidth: Float): String {
        paint.textSize = fontSize
        var kept = line.trimEnd()
        while (kept.isNotEmpty() && paint.measureText(kept + ELLIPSIS) > maxWidth) {
            kept = kept.dropLast(1).trimEnd()
        }
        return kept + ELLIPSIS
    }
}

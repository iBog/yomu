package com.yomu.pipeline.ocr

private const val DICT_KEY = "  character_dict:"
private const val DICT_ITEM = "  - "

/** A CTC decode's text and the mean probability of the characters it kept; 0 when it kept none. */
internal data class CtcDecode(val text: String, val confidence: Float)

/**
 * The `character_dict` list from a PP-OCR `inference.yml`, in class order. YAML single quotes
 * wrap characters YAML would otherwise read as syntax, with `''` for a quote itself.
 */
internal fun parseCharacterDict(lines: Sequence<String>): List<String> {
    val dict = mutableListOf<String>()
    var inDict = false
    for (line in lines) {
        if (!inDict) {
            inDict = line.trimEnd() == DICT_KEY
            continue
        }
        if (!line.startsWith(DICT_ITEM)) break
        dict += line.removePrefix(DICT_ITEM).unquoted()
    }
    return dict
}

private fun String.unquoted(): String =
    if (length >= 2 && startsWith('\'') && endsWith('\'')) substring(1, length - 1).replace("''", "'") else this

/**
 * Greedy CTC over [probs], a time-major `steps × classes` matrix. Class 0 is the blank, classes
 * 1..dict.size are [dict], and any class past it is the space the model appends to its dictionary.
 * A run of one class is one character.
 */
internal fun decodeCtc(probs: FloatArray, classes: Int, dict: List<String>): CtcDecode {
    val text = StringBuilder()
    var kept = 0
    var probabilitySum = 0f
    var previous = 0
    for (step in 0 until probs.size / classes) {
        var best = 0
        for (cls in 1 until classes) {
            if (probs[step * classes + cls] > probs[step * classes + best]) best = cls
        }
        if (best != 0 && best != previous) {
            text.append(dict.getOrNull(best - 1) ?: " ")
            probabilitySum += probs[step * classes + best]
            kept++
        }
        previous = best
    }
    return CtcDecode(text.toString().trim(), if (kept == 0) 0f else probabilitySum / kept)
}

/**
 * The row ranges of [inkRows] that hold a line of text. A single blank row inside a line, as
 * between a letter's dot and stem, does not end it; a band thinner than [minHeight] is a speck.
 */
internal fun textLineBands(inkRows: List<Boolean>, minHeight: Int): List<IntRange> {
    val bands = mutableListOf<IntRange>()
    var start = -1
    var end = -1
    for ((row, ink) in inkRows.withIndex()) {
        when {
            ink && start < 0 -> { start = row; end = row }
            ink -> end = row
            start >= 0 && row - end > 1 -> {
                if (end - start + 1 >= minHeight) bands += start..end
                start = -1
            }
        }
    }
    if (start >= 0 && end - start + 1 >= minHeight) bands += start..end
    return bands
}

/** A bubble's lines as one text: a hyphen that broke a word is dropped, and [spaced] scripts get a space between lines. */
internal fun joinLines(lines: List<String>, spaced: Boolean): String = buildString {
    for (line in lines.map { it.trim() }.filter { it.isNotEmpty() }) {
        when {
            isEmpty() -> append(line)
            spaced && last() == '-' && line.first().isLowerCase() -> { setLength(length - 1); append(line) }
            spaced -> append(' ').append(line)
            else -> append(line)
        }
    }
}

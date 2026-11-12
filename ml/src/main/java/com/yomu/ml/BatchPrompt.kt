package com.yomu.ml

import com.yomu.core.SpokenLanguage
import com.yomu.core.TranslatablePage

private val ID_KEYED_LINE = "^\\s*\\[(\\d+)]\\s*(.*)$".toRegex()

/** The source named before a noun in a prompt, "Korean ", or nothing for Auto. */
internal fun SpokenLanguage?.promptPrefix(): String = this?.let { "${it.promptName} " }.orEmpty()

/** The prompt a whole page is translated with: one `[id] text` line per bubble, panels apart by `---`. */
fun TranslatablePage.batchPrompt(): String = buildString {
    appendLine(
        "Translate each ${spoken.promptPrefix()}line to ${targetLanguage.promptName}. Keep the [id] tag before each line. " +
            "Panels are separated by ---."
    )
    panels.forEachIndexed { index, panel ->
        if (index > 0) appendLine("---")
        panel.forEach { bubble -> appendLine("[${bubble.bubbleId}] ${bubble.sourceText}") }
    }
}

/** An `[id] text` line as its id and text, or null for any other line. */
fun parseIdKeyedLine(line: String): Pair<Int, String>? {
    val match = ID_KEYED_LINE.matchEntire(line) ?: return null
    val id = match.groupValues[1].toIntOrNull() ?: return null
    return id to match.groupValues[2].trim()
}

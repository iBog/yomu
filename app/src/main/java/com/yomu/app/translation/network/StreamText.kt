package com.yomu.app.translation.network

private const val THINK_OPEN = "<think>"
private const val THINK_CLOSE = "</think>"

/**
 * Drops a reasoning model's `<think>...</think>` block from text that arrives in pieces. A tag may be cut between two
 * pieces, so the end of what could still turn into a tag is held back until the next piece or [finish].
 */
class ThinkStripper {
    private var pending = ""
    private var inThink = false

    /** The visible text in [piece], which may be empty while a tag or a thought is still open. */
    fun feed(piece: String): String {
        pending += piece
        val out = StringBuilder()
        while (true) {
            val tag = if (inThink) THINK_CLOSE else THINK_OPEN
            val at = pending.indexOf(tag)
            if (at >= 0) {
                if (!inThink) out.append(pending, 0, at)
                pending = pending.substring(at + tag.length)
                inThink = !inThink
                continue
            }
            val keep = heldBack(pending, tag)
            if (!inThink) out.append(pending, 0, pending.length - keep)
            pending = pending.substring(pending.length - keep)
            return out.toString()
        }
    }

    /** What is left once the text has ended: a held-back piece that never became a tag is text, unless a thought was open. */
    fun finish(): String = (if (inThink) "" else pending).also {
        pending = ""
        inThink = false
    }

    /** How many trailing characters of [text] start [tag] and so cannot be told apart from it yet. */
    private fun heldBack(text: String, tag: String): Int =
        (minOf(text.length, tag.length - 1) downTo 1).firstOrNull { text.endsWith(tag.substring(0, it)) } ?: 0
}

/** Cuts text that arrives in pieces into lines, handing each whole line over once its newline has arrived. */
class LineBuffer(private val onLine: (String) -> Unit) {
    private val buffer = StringBuilder()

    fun feed(piece: String) {
        buffer.append(piece)
        while (true) {
            val newline = buffer.indexOf("\n")
            if (newline < 0) return
            onLine(buffer.substring(0, newline).trimEnd('\r'))
            buffer.delete(0, newline + 1)
        }
    }

    /** Hands over the unterminated last line, for a reply the server ended. */
    fun finish() {
        if (buffer.isNotBlank()) onLine(buffer.toString().trimEnd('\r'))
        buffer.clear()
    }
}

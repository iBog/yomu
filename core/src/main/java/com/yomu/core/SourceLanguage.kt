package com.yomu.core

// ・ and ー stand in for ellipses and long vowels in otherwise translated lines, so they are not residue.
private val HAN_OR_KANA = Regex("[぀-ヺヽ-ヿ㐀-䶿一-鿿豈-﫿ｦ-ﾟ]")
private const val MIN_LETTERS = 2

/** Whether the text has any kana or kanji, which reader-app labels, dates and a lone "!?" never do. */
fun String.hasHanOrKana(): Boolean = HAN_OR_KANA.containsMatchIn(this)

/**
 * The language a page is read in, fixed by the OCR model that reads it. [code] is what history
 * rows store. [promptName] names a single language in the prompt; a script family has none, so
 * the model is told only the target and reads the source as written. [spacedLines] says whether
 * a bubble's lines are joined with a space.
 */
enum class SourceLanguage(val code: String, val displayName: String, val promptName: String?, val spacedLines: Boolean) {
    JAPANESE("ja", "Japanese", "Japanese", spacedLines = false),
    CHINESE("zh", "Chinese (and English)", "Chinese", spacedLines = false),
    KOREAN("ko", "Korean", "Korean", spacedLines = true),
    LATIN_SCRIPT("latn", "Latin script: English, Spanish, French, German, Italian…", null, spacedLines = true),
    CYRILLIC_SCRIPT("cyrl", "Cyrillic script: Russian, Ukrainian, Serbian, Bulgarian…", null, spacedLines = true);

    /**
     * Whether an OCR read is worth translating. Japanese needs kana or kanji, which reader-app
     * labels, dates and a lone "!?" never have; any other source needs two letters.
     */
    fun isReadable(text: String): Boolean =
        if (this == JAPANESE) text.hasHanOrKana() else text.count { it.isLetter() } >= MIN_LETTERS

    /** Whether a reply still carries the source's own script, which a translation out of Japanese or Chinese never should. */
    fun leftUntranslated(reply: String): Boolean = (this == JAPANESE || this == CHINESE) && reply.hasHanOrKana()

    companion object {
        /** The source stored as [code], or null for a code no source has. */
        fun fromCode(code: String?): SourceLanguage? = entries.firstOrNull { it.code == code }
    }
}

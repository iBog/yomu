package com.yomu.core

/**
 * A language the reader can name as the source (#444). [code] is what preferences store and what
 * TranslateGemma's prompt writes; [promptName] is the name written into the prompt; [script] is the
 * [SourceLanguage] whose OCR model reads it. "Auto" is the absence of one: the prompt names only the target.
 */
enum class SpokenLanguage(val code: String, val promptName: String, val script: SourceLanguage) {
    JAPANESE("ja", "Japanese", SourceLanguage.JAPANESE),
    CHINESE("zh", "Chinese", SourceLanguage.CHINESE),
    KOREAN("ko", "Korean", SourceLanguage.KOREAN),
    ENGLISH("en", "English", SourceLanguage.LATIN_SCRIPT),
    SPANISH("es", "Spanish", SourceLanguage.LATIN_SCRIPT),
    FRENCH("fr", "French", SourceLanguage.LATIN_SCRIPT),
    GERMAN("de", "German", SourceLanguage.LATIN_SCRIPT),
    ITALIAN("it", "Italian", SourceLanguage.LATIN_SCRIPT),
    PORTUGUESE("pt", "Portuguese", SourceLanguage.LATIN_SCRIPT),
    RUSSIAN("ru", "Russian", SourceLanguage.CYRILLIC_SCRIPT),
    UKRAINIAN("uk", "Ukrainian", SourceLanguage.CYRILLIC_SCRIPT);

    companion object {
        /** The language stored as [code], or null for "Auto" and for a code no language has. */
        fun fromCode(code: String?): SpokenLanguage? = entries.firstOrNull { it.code == code }

        /** The one language [script] is read as, or null for a script family that holds several. */
        fun ofScript(script: SourceLanguage): SpokenLanguage? = entries.singleOrNull { it.script == script }
    }
}

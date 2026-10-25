package com.yomu.core

/**
 * The language the reader reads in (#337). [code] is what preferences and history rows store;
 * [promptName] is the full name written into the prompt.
 */
enum class TargetLanguage(val code: String, val promptName: String) {
    ENGLISH("en", "English"),
    BRAZILIAN_PORTUGUESE("pt-BR", "Brazilian Portuguese"),
    SPANISH("es", "Spanish"),
    FRENCH("fr", "French"),
    GERMAN("de", "German"),
    RUSSIAN("ru", "Russian");

    companion object {
        /** The language stored as [code], or null for a code no language has. */
        fun fromCode(code: String?): TargetLanguage? = entries.firstOrNull { it.code == code }
    }
}

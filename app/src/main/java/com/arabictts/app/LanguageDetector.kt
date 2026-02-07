package com.arabictts.app

/**
 * Detects whether text is Arabic or English and splits mixed text into segments.
 */
object LanguageDetector {

    enum class Language { ARABIC, ENGLISH }

    data class TextSegment(val text: String, val language: Language)

    private val ARABIC_RANGE = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")

    fun detectLanguage(text: String): Language {
        val arabicCount = ARABIC_RANGE.findAll(text).count()
        val totalLetters = text.count { it.isLetter() }
        if (totalLetters == 0) return Language.ENGLISH
        return if (arabicCount.toFloat() / totalLetters > 0.5f) Language.ARABIC else Language.ENGLISH
    }

    /**
     * Splits text into contiguous segments of the same language.
     * This allows bilingual text to be spoken with the correct voice for each part.
     */
    fun splitByLanguage(text: String): List<TextSegment> {
        if (text.isBlank()) return emptyList()

        val segments = mutableListOf<TextSegment>()
        val currentSegment = StringBuilder()
        var currentLang: Language? = null

        for (char in text) {
            val charLang = when {
                ARABIC_RANGE.containsMatchIn(char.toString()) -> Language.ARABIC
                char.isLetter() -> Language.ENGLISH
                else -> currentLang // whitespace/punctuation inherits current language
            }

            if (charLang != null && charLang != currentLang && currentSegment.isNotEmpty() && currentLang != null) {
                segments.add(TextSegment(currentSegment.toString().trim(), currentLang))
                currentSegment.clear()
            }

            if (charLang != null) {
                currentLang = charLang
            }
            currentSegment.append(char)
        }

        if (currentSegment.isNotEmpty() && currentLang != null) {
            val trimmed = currentSegment.toString().trim()
            if (trimmed.isNotEmpty()) {
                segments.add(TextSegment(trimmed, currentLang))
            }
        }

        return segments
    }
}

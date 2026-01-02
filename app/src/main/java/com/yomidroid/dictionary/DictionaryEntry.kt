package com.yomidroid.dictionary

/**
 * Source dictionary for an entry.
 */
enum class DictionarySource {
    JITENDEX,
    JMNEDICT;

    companion object {
        fun fromString(value: String?): DictionarySource {
            return when (value?.lowercase()) {
                "jmnedict" -> JMNEDICT
                else -> JITENDEX
            }
        }
    }
}

/**
 * Type of name for JMnedict entries.
 */
enum class NameType {
    PERSON,
    SURNAME,
    GIVEN,
    PLACE,
    COMPANY,
    PRODUCT,
    WORK,
    OTHER;

    companion object {
        fun fromString(value: String?): NameType? {
            if (value == null) return null
            return when (value.lowercase()) {
                "person" -> PERSON
                "surname" -> SURNAME
                "given" -> GIVEN
                "place" -> PLACE
                "company" -> COMPANY
                "product" -> PRODUCT
                "work" -> WORK
                "other" -> OTHER
                else -> OTHER
            }
        }
    }
}

data class DictionaryEntry(
    val id: Long,
    val expression: String,
    val reading: String,
    val glossary: List<String>,
    val partsOfSpeech: List<String>,
    val score: Int,
    val matchedText: String = expression,
    val deinflectionPath: String = "",
    // Multi-dictionary support
    val source: DictionarySource = DictionarySource.JITENDEX,
    val nameType: NameType? = null,
    val frequencyRank: Int? = null
) {
    /** Returns true if this entry is from the names dictionary */
    val isName: Boolean get() = source == DictionarySource.JMNEDICT

    /** User-friendly source label for display */
    val sourceLabel: String get() = when (source) {
        DictionarySource.JITENDEX -> "Jitendex"
        DictionarySource.JMNEDICT -> "JMnedict"
    }

    /** Name type label for UI badges (null for non-name entries) */
    val nameTypeLabel: String? get() = when (nameType) {
        NameType.PLACE -> "Place"
        NameType.PERSON -> "Name"
        NameType.SURNAME -> "Surname"
        NameType.GIVEN -> "Given"
        NameType.COMPANY -> "Company"
        NameType.PRODUCT -> "Product"
        NameType.WORK -> "Work"
        NameType.OTHER -> "Name"
        null -> null
    }

    /** Frequency badge text (e.g., "Top 500", "Top 1K") */
    val frequencyBadge: String? get() = frequencyRank?.let { rank ->
        when {
            rank <= 500 -> "Top 500"
            rank <= 1000 -> "Top 1K"
            rank <= 2000 -> "Top 2K"
            rank <= 5000 -> "Top 5K"
            rank <= 10000 -> "Top 10K"
            else -> null  // Don't show badge for uncommon words
        }
    }
}

/**
 * A dictionary entry with position information from text scanning.
 */
data class DictionaryEntryWithPosition(
    val entry: DictionaryEntry,
    val startIndex: Int,
    val endIndex: Int
) {
    val matchedText: String get() = entry.matchedText
    val expression: String get() = entry.expression
    val reading: String get() = entry.reading
}

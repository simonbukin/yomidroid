package com.yomidroid.dictionary

/**
 * Source dictionary for an entry.
 */
enum class DictionarySource {
    JITENDEX,
    JMNEDICT,
    CUSTOM;

    companion object {
        fun fromString(value: String?): DictionarySource {
            return when (value?.lowercase()) {
                "jmnedict" -> JMNEDICT
                "jitendex" -> JITENDEX
                else -> CUSTOM
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
    val frequencyRank: Int? = null,
    val jpdbRank: Int? = null,
    val pitchDownstep: Int? = null,  // primary (backward compat)
    val pitchDownsteps: List<Int> = emptyList(),
    val sourceDictId: String = "bundled",
    val dictionaryTitle: String = "",  // title from InstalledDictionary (for CSS scoping)
    val additionalFrequencies: Map<String, Int> = emptyMap(),
    val glossaryRich: String? = null,
    val definitionTags: List<String> = emptyList(),
    val tagMeta: Map<String, com.yomidroid.data.TagMeta> = emptyMap(),
    val inflectionChainLength: Int = 0,
    val isExactMatch: Boolean = false,
    val dictionaryPriority: Int = 0
) {
    /** Returns true if this entry is from the names dictionary */
    val isName: Boolean get() = source == DictionarySource.JMNEDICT

    /** User-friendly source label for display */
    val sourceLabel: String get() {
        return when (source) {
            DictionarySource.JITENDEX -> "Jitendex"
            DictionarySource.JMNEDICT -> "JMnedict"
            DictionarySource.CUSTOM -> sourceDictId.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
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

    /** Human-readable POS label for display */
    val posDisplayLabel: String? get() {
        if (partsOfSpeech.isEmpty()) return null
        val labels = partsOfSpeech.mapNotNull { tag ->
            when (tag) {
                "v1" -> "Ichidan"
                "v5k", "v5s", "v5t", "v5n", "v5m", "v5r", "v5u", "v5g", "v5b",
                "v5k-s", "v5aru" -> "Godan"
                "vs", "vs-i", "vs-s" -> "Suru verb"
                "vk" -> "Kuru verb"
                "vt" -> "Transitive"
                "vi" -> "Intransitive"
                "adj-i" -> "i-adj"
                "adj-na" -> "na-adj"
                "adj-no" -> "no-adj"
                "adj-t", "adj-f" -> "Adj"
                "n" -> "Noun"
                "adv" -> "Adverb"
                "exp" -> "Expression"
                "prt" -> "Particle"
                "conj" -> "Conjunction"
                "int" -> "Interjection"
                "pn" -> "Pronoun"
                "suf" -> "Suffix"
                "pref" -> "Prefix"
                "ctr" -> "Counter"
                "aux", "aux-v", "aux-adj" -> "Auxiliary"
                "cop" -> "Copula"
                "name" -> null // Already shown as name badge
                else -> null
            }
        }.distinct()
        return if (labels.isNotEmpty()) labels.joinToString(", ") else null
    }

    /** Frequency badge text — uses highest-priority enabled frequency or bundled rank */
    val frequencyBadge: String? get() {
        val rank = frequencyRank ?: additionalFrequencies.values.minOrNull()
        return rank?.let { if (it <= 50000) "#$it" else null }
    }

    /** ARGB color for the frequency badge */
    val frequencyBadgeColor: Int get() {
        val rank = frequencyRank ?: additionalFrequencies.values.minOrNull() ?: return 0xFF4CAF50.toInt()
        return when {
            rank <= 1500 -> 0xFF4CAF50.toInt()   // Green
            rank <= 5000 -> 0xFF8BC34A.toInt()    // Light green
            rank <= 15000 -> 0xFFFFC107.toInt()   // Amber
            rank <= 50000 -> 0xFFFF9800.toInt()   // Orange
            else -> 0xFF4CAF50.toInt()
        }
    }

    /** JPDB frequency badge text */
    val jpdbBadge: String? get() = jpdbRank?.let { rank ->
        if (rank <= 50000) "JPDB #$rank" else null
    }

    /** ARGB color for the JPDB badge */
    val jpdbBadgeColor: Int get() = jpdbRank?.let { rank ->
        when {
            rank <= 1500 -> 0xFF7C4DFF.toInt()   // Deep purple
            rank <= 5000 -> 0xFF9C27B0.toInt()    // Purple
            rank <= 15000 -> 0xFFAB47BC.toInt()   // Light purple
            rank <= 50000 -> 0xFFCE93D8.toInt()   // Pale purple
            else -> 0xFF9C27B0.toInt()
        }
    } ?: 0xFF9C27B0.toInt()

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

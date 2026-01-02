package com.yomidroid.grammar

import androidx.compose.runtime.Immutable

/**
 * Represents a single morpheme (token) from Kuromoji analysis.
 */
@Immutable
data class Morpheme(
    val surface: String,           // Surface form (as written)
    val reading: String,           // Reading in katakana
    val baseForm: String,          // Dictionary/base form
    val partOfSpeech: String,      // Primary POS (noun, verb, etc.)
    val posDetail1: String,        // POS subcategory 1
    val posDetail2: String,        // POS subcategory 2
    val posDetail3: String,        // POS subcategory 3
    val conjugationType: String,   // Conjugation type (for verbs/adj)
    val conjugationForm: String,   // Conjugation form
    val isContentWord: Boolean     // True for nouns, verbs, adjectives
) {
    /** Full POS string for display */
    val fullPosLabel: String
        get() = listOf(partOfSpeech, posDetail1, posDetail2, posDetail3)
            .filter { it.isNotEmpty() && it != "*" }
            .joinToString("-")

    /** Localized POS label for UI */
    val posLabel: String
        get() = when (partOfSpeech) {
            "名詞" -> "Noun"
            "動詞" -> "Verb"
            "形容詞" -> "i-Adj"
            "形容動詞" -> "na-Adj"
            "副詞" -> "Adverb"
            "連体詞" -> "Adnominal"
            "接続詞" -> "Conjunction"
            "感動詞" -> "Interjection"
            "助詞" -> "Particle"
            "助動詞" -> "Aux"
            "接頭詞" -> "Prefix"
            "接尾詞" -> "Suffix"
            "記号" -> "Symbol"
            "フィラー" -> "Filler"
            else -> partOfSpeech
        }

    /** Color for POS badge */
    val posColor: PosColor
        get() = when (partOfSpeech) {
            "名詞" -> PosColor.NOUN
            "動詞" -> PosColor.VERB
            "形容詞", "形容動詞" -> PosColor.ADJECTIVE
            "副詞" -> PosColor.ADVERB
            "助詞" -> PosColor.PARTICLE
            "助動詞" -> PosColor.AUXILIARY
            else -> PosColor.OTHER
        }
}

enum class PosColor {
    NOUN,       // Blue
    VERB,       // Green
    ADJECTIVE,  // Orange
    ADVERB,     // Purple
    PARTICLE,   // Gray
    AUXILIARY,  // Teal
    OTHER       // Default
}

/**
 * Represents a bunsetsu (文節) - a phrase unit consisting of
 * a content word followed by functional words.
 * All derived values are pre-computed at construction for performance.
 */
@Immutable
data class Bunsetsu(
    val morphemes: List<Morpheme>,
    val colorIndex: Int,          // For UI color coding (-1 for punctuation)
    // Pre-computed values (computed once at construction)
    val text: String,
    val reading: String,
    val headWord: Morpheme?,
    val trailingParticle: Morpheme?,
    val particleRole: ParticleRole,
    val headPosColor: PosColor
) {
    companion object {
        /** Factory function that pre-computes all derived values */
        fun create(morphemes: List<Morpheme>, colorIndex: Int): Bunsetsu {
            val text = morphemes.joinToString("") { it.surface }
            val reading = morphemes.joinToString("") { it.reading }
            val headWord = morphemes.firstOrNull { it.isContentWord }
            val trailingParticle = morphemes.lastOrNull { it.partOfSpeech == "助詞" }
            val particleRole = trailingParticle?.let { getParticleRole(it.surface) } ?: ParticleRole.NONE
            val headPosColor = headWord?.posColor ?: PosColor.OTHER

            return Bunsetsu(
                morphemes = morphemes,
                colorIndex = colorIndex,
                text = text,
                reading = reading,
                headWord = headWord,
                trailingParticle = trailingParticle,
                particleRole = particleRole,
                headPosColor = headPosColor
            )
        }
    }
}

/**
 * Complete grammar analysis result for a text.
 */
@Immutable
data class GrammarAnalysisResult(
    val originalText: String,
    val morphemes: List<Morpheme>,
    val bunsetsu: List<Bunsetsu>,
    val grammarPoints: List<DetectedGrammarPoint> = emptyList()
)

/**
 * A detected DOJG grammar point in the text.
 */
@Immutable
data class DetectedGrammarPoint(
    val id: String,
    val pattern: String,
    val level: String,              // "basic", "intermediate", "advanced"
    val meaning: String,
    val formation: List<String>,
    val examples: List<DojgExample>,
    val matchedText: String,
    val startIndex: Int,
    val endIndex: Int,
    val sourceUrl: String
)

/**
 * Example sentence from DOJG.
 */
@Immutable
data class DojgExample(
    val ja: String,
    val en: String
)

/**
 * A dictionary match found via longest-match scanning.
 */
@Immutable
data class DictionaryMatch(
    val startIndex: Int,
    val endIndex: Int,
    val matchedText: String,
    val expression: String,
    val reading: String,
    val glossary: List<String>,
    val partsOfSpeech: List<String>,
    val frequencyRank: Int?,
    val source: String
)

/**
 * Particle role information for visual encoding.
 */
enum class ParticleRole(val particle: String, val label: String) {
    TOPIC("は", "topic"),
    SUBJECT("が", "subj"),
    OBJECT("を", "obj"),
    TARGET("に", "target"),
    MEANS("で", "means"),
    POSSESSIVE("の", "poss"),
    NONE("", "")
}

/**
 * Get the particle role from a particle surface form.
 */
fun getParticleRole(surface: String): ParticleRole {
    return when (surface) {
        "は" -> ParticleRole.TOPIC
        "が" -> ParticleRole.SUBJECT
        "を" -> ParticleRole.OBJECT
        "に", "へ" -> ParticleRole.TARGET
        "で" -> ParticleRole.MEANS
        "の" -> ParticleRole.POSSESSIVE
        else -> ParticleRole.NONE
    }
}

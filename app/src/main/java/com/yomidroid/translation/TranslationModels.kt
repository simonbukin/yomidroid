package com.yomidroid.translation

import androidx.compose.runtime.Immutable

/**
 * Translation mode determines what type of translation to generate.
 */
enum class TranslationMode {
    /** Fluent, natural English translation */
    NATURAL,
    /** Literal translation preserving Japanese structure with parentheticals */
    LITERAL,
    /** Morpheme-by-morpheme breakdown with readings and glosses */
    INTERLINEAR
}

/**
 * Result of a translation request containing all three modes.
 */
@Immutable
data class TranslationResult(
    val originalText: String,
    val natural: String?,
    val literal: String?,
    val interlinear: InterlinearResult?,
    val backend: String,          // Which backend produced this (for debugging/display)
    val cached: Boolean = false   // Whether this was a cache hit
)

/**
 * Interlinear translation with morpheme-level alignment.
 *
 * Example:
 * 熱      を    早く   下げ    たい
 * ねつ    を    はやく  さげ    たい
 * fever  [obj] quickly lower  want
 */
@Immutable
data class InterlinearResult(
    val morphemes: List<InterlinearMorpheme>
) {
    /** Render as aligned text blocks for display */
    fun toDisplayLines(): Triple<String, String, String> {
        val japanese = morphemes.map { it.surface }
        val readings = morphemes.map { it.reading }
        val glosses = morphemes.map { it.gloss }

        return Triple(
            japanese.joinToString(" "),
            readings.joinToString(" "),
            glosses.joinToString(" ")
        )
    }
}

/**
 * Single morpheme with its interlinear gloss.
 */
@Immutable
data class InterlinearMorpheme(
    val surface: String,   // Original Japanese text
    val reading: String,   // Reading in hiragana/katakana
    val gloss: String      // English gloss (e.g., "fever", "[obj]", "want")
)

/**
 * Translation request parameters.
 */
data class TranslationRequest(
    val text: String,
    val modes: Set<TranslationMode> = setOf(TranslationMode.NATURAL, TranslationMode.LITERAL),
    val includeInterlinear: Boolean = false
)

/**
 * Backend availability status.
 */
sealed class BackendStatus {
    object Available : BackendStatus()
    object Unavailable : BackendStatus()
    data class Downloading(val progress: Float) : BackendStatus()
    data class Error(val message: String) : BackendStatus()
}

package com.yomidroid.grammar

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Morphological analyzer using Kuromoji with bunsetsu grouping.
 *
 * Bunsetsu (文節) = content word + trailing functional words (particles, auxiliaries).
 * Example: 「私は学校に行きました」 → 「私は」「学校に」「行きました」
 */
class GrammarAnalyzer private constructor() {

    // Lazy tokenizer (thread-safe, created on first use)
    private val tokenizer: Tokenizer by lazy {
        Tokenizer()
    }

    // POS tags that indicate content words (start new bunsetsu)
    private val contentWordPos = setOf(
        "名詞",      // Noun
        "動詞",      // Verb
        "形容詞",    // i-adjective
        "形容動詞",  // na-adjective (rare in ipadic)
        "副詞",      // Adverb
        "連体詞",    // Adnominal
        "接続詞",    // Conjunction
        "感動詞",    // Interjection
        "接頭詞"     // Prefix (starts new bunsetsu but attaches to following)
    )

    // POS tags that are functional (attach to previous bunsetsu)
    private val functionalPos = setOf(
        "助詞",      // Particle
        "助動詞",    // Auxiliary verb
        "接尾詞"     // Suffix
    )

    /**
     * Analyze text and return morphemes with bunsetsu grouping.
     * Runs on Default dispatcher to avoid blocking main thread.
     */
    suspend fun analyze(text: String): GrammarAnalysisResult = withContext(Dispatchers.Default) {
        if (text.isBlank()) {
            return@withContext GrammarAnalysisResult(text, emptyList(), emptyList())
        }

        val tokens = tokenizer.tokenize(text)
        val morphemes = tokens.map { tokenToMorpheme(it) }
        val bunsetsu = groupIntoBunsetsu(morphemes)

        GrammarAnalysisResult(
            originalText = text,
            morphemes = morphemes,
            bunsetsu = bunsetsu
        )
    }

    /**
     * Synchronous version for use in non-coroutine contexts.
     */
    fun analyzeSync(text: String): GrammarAnalysisResult {
        if (text.isBlank()) {
            return GrammarAnalysisResult(text, emptyList(), emptyList())
        }

        val tokens = tokenizer.tokenize(text)
        val morphemes = tokens.map { tokenToMorpheme(it) }
        val bunsetsu = groupIntoBunsetsu(morphemes)

        return GrammarAnalysisResult(
            originalText = text,
            morphemes = morphemes,
            bunsetsu = bunsetsu
        )
    }

    private fun tokenToMorpheme(token: Token): Morpheme {
        // Kuromoji ipadic features:
        // [0] POS, [1] POS detail 1, [2] POS detail 2, [3] POS detail 3
        // [4] conjugation type, [5] conjugation form, [6] base form, [7] reading, [8] pronunciation
        val features = token.allFeaturesArray
        val pos = features.getOrElse(0) { "*" }

        return Morpheme(
            surface = token.surface,
            reading = features.getOrElse(7) { token.surface },
            baseForm = features.getOrElse(6) { token.surface }.let {
                if (it == "*") token.surface else it
            },
            partOfSpeech = pos,
            posDetail1 = features.getOrElse(1) { "*" },
            posDetail2 = features.getOrElse(2) { "*" },
            posDetail3 = features.getOrElse(3) { "*" },
            conjugationType = features.getOrElse(4) { "*" },
            conjugationForm = features.getOrElse(5) { "*" },
            isContentWord = pos in contentWordPos
        )
    }

    /**
     * Group morphemes into bunsetsu (phrase units).
     *
     * Rules:
     * 1. Content word starts a new bunsetsu
     * 2. Functional words (particles, auxiliaries) attach to the preceding bunsetsu
     * 3. Symbols and punctuation are standalone (no color)
     * 4. Leading functional words form their own bunsetsu
     */
    private fun groupIntoBunsetsu(morphemes: List<Morpheme>): List<Bunsetsu> {
        if (morphemes.isEmpty()) return emptyList()

        val result = mutableListOf<Bunsetsu>()
        var currentGroup = mutableListOf<Morpheme>()
        var colorIndex = 0

        for (morpheme in morphemes) {
            when {
                // Punctuation/symbols are standalone with no color
                morpheme.partOfSpeech == "記号" -> {
                    if (currentGroup.isNotEmpty()) {
                        result.add(Bunsetsu.create(currentGroup.toList(), colorIndex++ % BUNSETSU_COLORS))
                        currentGroup = mutableListOf()
                    }
                    result.add(Bunsetsu.create(listOf(morpheme), -1))
                }

                // Content word starts new bunsetsu
                morpheme.isContentWord -> {
                    if (currentGroup.isNotEmpty()) {
                        result.add(Bunsetsu.create(currentGroup.toList(), colorIndex++ % BUNSETSU_COLORS))
                        currentGroup = mutableListOf()
                    }
                    currentGroup.add(morpheme)
                }

                // Functional word attaches to current bunsetsu
                morpheme.partOfSpeech in functionalPos -> {
                    if (currentGroup.isEmpty()) {
                        // Orphan functional word - start new bunsetsu
                        currentGroup.add(morpheme)
                    } else {
                        currentGroup.add(morpheme)
                    }
                }

                // Other cases (fillers, unknown) - attach or start new
                else -> {
                    if (currentGroup.isEmpty()) {
                        currentGroup.add(morpheme)
                    } else {
                        currentGroup.add(morpheme)
                    }
                }
            }
        }

        // Don't forget the last group
        if (currentGroup.isNotEmpty()) {
            result.add(Bunsetsu.create(currentGroup.toList(), colorIndex % BUNSETSU_COLORS))
        }

        return result
    }

    companion object {
        const val BUNSETSU_COLORS = 6  // Number of distinct colors for cycling

        @Volatile
        private var instance: GrammarAnalyzer? = null

        fun getInstance(): GrammarAnalyzer {
            return instance ?: synchronized(this) {
                instance ?: GrammarAnalyzer().also { instance = it }
            }
        }
    }
}

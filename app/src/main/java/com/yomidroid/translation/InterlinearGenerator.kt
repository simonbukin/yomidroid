package com.yomidroid.translation

import com.yomidroid.grammar.GrammarAnalyzer
import com.yomidroid.grammar.Morpheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generates interlinear translations using Kuromoji morphological analysis.
 *
 * Output format:
 * 熱      を    早く   下げ    たい
 * ねつ    を    はやく  さげ    たい
 * fever  [obj] quickly lower  want
 *
 * Glosses are generated from:
 * 1. POS-based function word glosses (particles, auxiliaries)
 * 2. Dictionary lookups for content words (future: integrate with DictionaryEngine)
 * 3. LLM-generated glosses (future: use translation backend)
 */
class InterlinearGenerator {

    private val grammarAnalyzer = GrammarAnalyzer.getInstance()

    /**
     * Generate interlinear breakdown for Japanese text.
     */
    suspend fun generate(text: String): InterlinearResult = withContext(Dispatchers.Default) {
        val analysis = grammarAnalyzer.analyze(text)

        val interlinearMorphemes = analysis.morphemes.map { morpheme ->
            InterlinearMorpheme(
                surface = morpheme.surface,
                reading = convertToHiragana(morpheme.reading),
                gloss = generateGloss(morpheme)
            )
        }

        InterlinearResult(interlinearMorphemes)
    }

    /**
     * Generate a gloss for a morpheme based on POS and known patterns.
     */
    private fun generateGloss(morpheme: Morpheme): String {
        // Function word glosses (grammatical markers)
        val functionGloss = getFunctionWordGloss(morpheme)
        if (functionGloss != null) return functionGloss

        // Content word glosses - use base form and POS hint
        return when (morpheme.partOfSpeech) {
            "名詞" -> "[N]${morpheme.baseForm}"  // Noun
            "動詞" -> getVerbGloss(morpheme)     // Verb
            "形容詞" -> "[Adj]${morpheme.baseForm}"  // i-adjective
            "形容動詞" -> "[na-Adj]${morpheme.baseForm}"  // na-adjective
            "副詞" -> "[Adv]${morpheme.baseForm}"  // Adverb
            "連体詞" -> morpheme.surface  // Adnominal (e.g., この, その)
            "接続詞" -> morpheme.surface  // Conjunction
            "感動詞" -> morpheme.surface  // Interjection
            else -> morpheme.baseForm
        }
    }

    /**
     * Get gloss for function words (particles, auxiliaries).
     */
    private fun getFunctionWordGloss(morpheme: Morpheme): String? {
        if (morpheme.partOfSpeech == "助詞") {
            return getParticleGloss(morpheme.surface)
        }

        if (morpheme.partOfSpeech == "助動詞") {
            return getAuxiliaryGloss(morpheme)
        }

        if (morpheme.partOfSpeech == "記号") {
            return morpheme.surface  // Return punctuation as-is
        }

        return null
    }

    /**
     * Get gloss for particles.
     */
    private fun getParticleGloss(surface: String): String {
        return when (surface) {
            // Case particles
            "は" -> "[TOP]"    // Topic marker
            "が" -> "[SUBJ]"   // Subject marker
            "を" -> "[OBJ]"    // Object marker
            "に" -> "[DAT]"    // Dative/location
            "へ" -> "[DIR]"    // Direction
            "で" -> "[LOC/INST]"  // Location of action / instrument
            "と" -> "[COM/QUO]"  // Comitative / quotation
            "から" -> "[FROM]"   // Source/reason
            "まで" -> "[UNTIL]"  // Limit
            "より" -> "[THAN]"   // Comparison

            // Possessive/modifying
            "の" -> "[GEN]"    // Genitive/possessive

            // Conjunctive
            "て" -> "[TE]"     // Te-form connector
            "ても" -> "[even.if]"
            "けど", "けれど", "けれども" -> "[but]"
            // Note: が and から have multiple uses - case particle vs conjunctive
            // Primary case particle meaning is used above
            "し" -> "[and/because]"
            "ので" -> "[because]"
            "のに" -> "[although]"
            "ば" -> "[if]"
            "たら" -> "[if/when]"
            "なら" -> "[if]"

            // Sentence-final
            "よ" -> "[!]"      // Assertion
            "ね" -> "[right?]" // Confirmation seeking
            "か" -> "[?]"      // Question
            "な" -> "[!]"      // Exclamation (male)
            "わ" -> "[!]"      // Soft assertion (female)
            "ぞ" -> "[!]"      // Strong assertion (male)
            "かな" -> "[wonder]"
            "っけ" -> "[wasn't.it?]"

            else -> "[$surface]"
        }
    }

    /**
     * Get gloss for auxiliary verbs.
     */
    private fun getAuxiliaryGloss(morpheme: Morpheme): String {
        val base = morpheme.baseForm
        val surface = morpheme.surface

        return when {
            // Tense/aspect
            base == "た" || base == "だ" -> "[PAST]"
            base == "ます" -> "[POL]"  // Polite
            base == "です" -> "[COP.POL]"  // Copula polite
            base == "だ" -> "[COP]"    // Copula plain

            // Negation
            base == "ない" || base == "ぬ" -> "[NEG]"
            base == "ません" -> "[NEG.POL]"

            // Desire/volition
            base == "たい" -> "[want]"
            base == "たがる" -> "[want.3rd]"
            base == "う" || base == "よう" -> "[VOL]"  // Volitional

            // Potential/passive/causative
            base == "れる" || base == "られる" -> "[PASS/POT]"
            base == "せる" || base == "させる" -> "[CAUS]"

            // Progressive
            base == "いる" || base == "ている" -> "[PROG]"

            // Completion
            base == "しまう" || base == "ちゃう" -> "[COMPL]"

            // Giving/receiving
            base == "あげる" -> "[give]"
            base == "くれる" -> "[give.to.me]"
            base == "もらう" -> "[receive]"

            // Attempt
            base == "みる" -> "[try]"

            else -> "[$surface]"
        }
    }

    /**
     * Get gloss for verbs, indicating conjugation form.
     */
    private fun getVerbGloss(morpheme: Morpheme): String {
        val base = morpheme.baseForm
        val conjForm = morpheme.conjugationForm

        val suffix = when {
            conjForm.contains("連用形") -> ".CONT"  // Continuative
            conjForm.contains("未然形") -> ".NEG"   // Negative stem
            conjForm.contains("命令形") -> ".IMP"   // Imperative
            conjForm.contains("仮定形") -> ".COND"  // Conditional
            conjForm.contains("終止形") -> ""       // Terminal (dictionary form)
            conjForm.contains("連体形") -> ".ATTR"  // Attributive
            else -> ""
        }

        return "[V]$base$suffix"
    }

    /**
     * Convert katakana reading to hiragana.
     */
    private fun convertToHiragana(reading: String): String {
        return reading.map { char ->
            if (char in '\u30A1'..'\u30F6') {
                // Katakana to Hiragana: subtract 0x60
                (char.code - 0x60).toChar()
            } else {
                char
            }
        }.joinToString("")
    }
}

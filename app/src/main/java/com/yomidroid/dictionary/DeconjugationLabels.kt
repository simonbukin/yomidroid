package com.yomidroid.dictionary

/**
 * Maps the verbose Jiten deconjugation `detail` strings (emitted by the
 * Hoshidicts `main-mit` deconjugator, which only carries the long-form name)
 * to compact, Japanese-suffix-style display labels — so the popup can render a
 * row of short chips (like Hoshireader / Yomihon) instead of one long sentence.
 *
 * The full set of `detail` strings lives in
 * `cpp/hoshidicts/src/deconjugator/deconjugation_rules.hpp`. Every step is kept
 * (including grammatical "stem" scaffolding); strings without an entry here
 * fall back to the original text with surrounding parentheses stripped, so
 * nothing is ever silently dropped.
 */
object DeconjugationLabels {

    /** One deconjugation step: compact [name] + the original verbose [description]. */
    data class Step(val name: String, val description: String)

    // Japanese-suffix style for inflectional forms; terse labels for the
    // grammatical stem scaffolding (which has no natural suffix form).
    private val COMPACT: Map<String, String> = mapOf(
        // Stems / scaffolding
        "(mizenkei)" to "未然",
        "(izenkei)" to "已然",
        "(infinitive)" to "連用",
        "(unstressed infinitive)" to "連用",
        "(adverbial stem)" to "連用",
        "(ka stem)" to "か",
        "(ke stem)" to "け",
        "('a' stem)" to "あ",
        "(suru verb noun stem)" to "stem",
        "(stem)" to "stem",
        "classical attributive" to "連体",
        "noun form" to "noun",

        // Core inflections
        "past" to "-た",
        "(te form)" to "-て",
        "negative" to "-ない",
        "adverbial negative" to "-なく",
        "archaic negative" to "-ぬ",
        "without doing so" to "-ずに",
        "polite" to "-ます",
        "negative polite" to "-ません",
        "past polite" to "-ました",
        "te polite" to "-まして",
        "past negative polite" to "-ませんでした",
        "polite volitional" to "-ましょう",
        "polite (childish)" to "-ます",
        "formal negative" to "-ません",
        "formal negative past" to "-ませんでした",
        "imperative" to "-ろ",
        "volitional" to "-よう",
        "shortened volitional" to "-よ",
        "presumptive" to "-だろう",

        // Conditionals
        "conditional" to "-たら",
        "formal conditional" to "-ましたら",
        "provisional conditional" to "-ば",
        "negative conditional" to "-なければ",
        "colloquial negative conditional" to "-なきゃ",
        "classical hypothetical conditional" to "-ば",

        // Voice / valency
        "potential" to "-える",
        "passive" to "-れる",
        "passive/potential" to "-られる",
        "causative" to "-せる",
        "short causative" to "-す",

        // Auxiliary -te chains
        "teiru" to "-ている",
        "teru (teiru)" to "-てる",
        "teoru" to "-ておる",
        "toru (teoru)" to "-とる",
        "tearu" to "-てある",
        "teiku" to "-ていく",
        "teku (teiku)" to "-てく",
        "tekuru" to "-てくる",
        "temiru" to "-てみる",
        "for now" to "-ておく",
        "toku (for now)" to "-とく",
        "do for someone" to "-てあげる",
        "do for me" to "-てくれる",
        "have someone do" to "-てもらう",
        "do for someone (casual)" to "-たげる",
        "do for someone (humble)" to "-てさしあげる",
        "polite request" to "-てください",
        "casual kind request" to "-て",
        "kind request" to "-てちょうだい",
        "topic/condition" to "-ては",
        "contracted conditional (te-ireba)" to "-てりゃ",

        // Modal / aspect / nuance
        "want" to "-たい",
        "too much" to "-すぎる",
        "excess" to "-すぎ",
        "seemingness" to "-そう",
        "seeming" to "-そう",
        "garu" to "-がる",
        "negative appearance" to "-なさそう",
        "while" to "-ながら",
        "tari" to "-たり",
        "mai" to "-まい",
        "negative volition/conjecture" to "-まい",
        "finish/completely/end up" to "-ちゃう",
        "polite command" to "-なさい",

        // Slang / dialect / contractions
        "slang negative" to "-ん",
        "slurred negative" to "-ん",
        "slurred negative conditional" to "-なきゃ",
        "slang negative conditional" to "-なきゃ",
        "negative (kansaiben)" to "-へん",
        "negative past (kansaiben)" to "-へんかった",
        "contracted -te yarou (kansaiben)" to "-たろ",
        "contracted" to "縮約",
        "slurred" to "縮約",
        "dialectal" to "方言",
        "slang (ee/ii)" to "俗語",
        "slang (irregular)" to "俗語",
    )

    /** Compact label for a single verbose `detail` string. */
    fun compact(detail: String): String {
        COMPACT[detail]?.let { return it }
        val t = detail.trim()
        // Fallback: strip one layer of surrounding parens, else use verbatim.
        return if (t.length >= 2 && t.startsWith("(") && t.endsWith(")")) {
            t.substring(1, t.length - 1)
        } else {
            t
        }
    }

    /** Build the compact step list from a native `process` chain, dropping empty steps. */
    fun steps(process: List<String>): List<Step> =
        process.mapNotNull { detail ->
            if (detail.isBlank()) null else Step(compact(detail), detail)
        }
}

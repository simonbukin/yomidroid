package com.yomidroid.grammar

/**
 * Approximate kana → lowercase Hepburn romaji.
 *
 * Used for fuzzy search matching, NOT for display. Output is consistent so
 * that `kanaToRomaji("らしい")` produces a string the user's query like
 * "rashii" will substring-match. Strict Hepburn quirks (e.g. n'apostrophe
 * before vowels, macrons for long vowels) are deliberately omitted —
 * the user types `rashii`, not `rashī`.
 */
object Romaji {

    // Two-char digraphs (small ya/yu/yo). Checked first.
    private val DIGRAPH: Map<String, String> = buildMap {
        val rows = listOf(
            // ki-row + small ya/yu/yo
            "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo",
            "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
            "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
            "じゃ" to "ja",  "じゅ" to "ju",  "じょ" to "jo",
            "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho",
            "ぢゃ" to "ja",  "ぢゅ" to "ju",  "ぢょ" to "jo",
            "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
            "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
            "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
            "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo",
            "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
            "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
        )
        for ((k, v) in rows) {
            put(k, v)
            // Katakana variant
            put(toKatakana(k), v)
        }
    }

    // Single-character kana mappings (hiragana + katakana shared).
    private val MONO: Map<Char, String> = buildMap {
        val hira = mapOf(
            'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
            'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
            'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
            'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
            'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
            'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
            'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
            'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
            'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
            'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
            'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
            'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
            'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
            'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
            'わ' to "wa", 'ゐ' to "i",  'ゑ' to "e", 'を' to "o",
            'ん' to "n",
            // Small standalone vowels — for matching they map to the same vowel
            'ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o",
            'ゃ' to "ya", 'ゅ' to "yu", 'ょ' to "yo",
            'ゎ' to "wa",
        )
        for ((k, v) in hira) {
            put(k, v)
            // Katakana counterpart (offset +0x60)
            val kata = (k.code + 0x60).toChar()
            put(kata, v)
        }
        // Katakana-only / extras
        put('ヴ', "vu")
        put('ー', "")  // handled specially in fun
    }

    private const val HIRAGANA_START = 0x3041
    private const val HIRAGANA_END = 0x3096
    private const val KATAKANA_START = 0x30A1
    private const val KATAKANA_END = 0x30FA
    private const val SOKUON_HIRA = 'っ'
    private const val SOKUON_KATA = 'ッ'
    private const val LONG_MARK = 'ー'

    /**
     * Convert any kana (hiragana + katakana) in `s` to lowercase Hepburn romaji.
     * Non-kana characters pass through unchanged (so mixed strings like "～たい"
     * become "~tai"). Result is lowercase.
     */
    fun kanaToRomaji(s: String): String {
        if (s.isEmpty()) return s
        val out = StringBuilder(s.length * 2)
        var i = 0
        while (i < s.length) {
            // Try 2-char digraph first
            if (i + 1 < s.length) {
                val pair = s.substring(i, i + 2)
                val d = DIGRAPH[pair]
                if (d != null) {
                    out.append(d)
                    i += 2
                    continue
                }
            }
            val c = s[i]

            // Sokuon っ / ッ — double the next consonant.
            if (c == SOKUON_HIRA || c == SOKUON_KATA) {
                val nextRomaji = peekRomaji(s, i + 1)
                if (nextRomaji.isNotEmpty()) {
                    val first = nextRomaji[0]
                    when (first) {
                        'c' -> out.append('t')         // っち → tchi
                        else -> out.append(first)      // っか → kka, っぱ → ppa, …
                    }
                }
                i++
                continue
            }

            // Long-vowel mark ー — repeat the previous vowel.
            if (c == LONG_MARK) {
                val last = if (out.isNotEmpty()) out.last() else null
                if (last != null && last in "aiueo") out.append(last)
                i++
                continue
            }

            val r = MONO[c]
            if (r != null) {
                out.append(r)
                i++
                continue
            }

            // Non-kana char — pass through as lowercase
            out.append(c.lowercaseChar())
            i++
        }
        return out.toString()
    }

    /** Peek the romaji of the kana/digraph starting at index `i`. Empty if not a kana. */
    private fun peekRomaji(s: String, i: Int): String {
        if (i >= s.length) return ""
        if (i + 1 < s.length) {
            DIGRAPH[s.substring(i, i + 2)]?.let { return it }
        }
        return MONO[s[i]] ?: ""
    }

    private fun toKatakana(hira: String): String {
        val sb = StringBuilder(hira.length)
        for (ch in hira) {
            val code = ch.code
            if (code in HIRAGANA_START..HIRAGANA_END) {
                sb.append((code + 0x60).toChar())
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
}

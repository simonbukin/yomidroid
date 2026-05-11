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

    // Inverse: romaji → hiragana, used so the in-app Search field can accept
    // "mizu" / "rashii" / "konnichiwa" and route them into kana-keyed lookups.
    // Reverse-built from the same tables that drive kanaToRomaji so the round-trip
    // stays consistent for the common cases (we lose 'ji'/'zu' ambiguity — we always
    // pick the ざ-row 'じ'/'ず' over the rare だ-row 'ぢ'/'づ').
    private val ROMAJI_TO_HIRA: Map<String, String> by lazy {
        buildMap {
            // 3-char digraphs (sha/shu/sho/cha/chu/cho/etc are 3 chars)
            for ((kana, r) in DIGRAPH) {
                // DIGRAPH duplicates each entry as hiragana+katakana — keep only hiragana.
                if (kana.all { it.code in HIRAGANA_START..HIRAGANA_END }) {
                    putIfAbsent(r, kana)
                }
            }
            // Single mono
            val seenHira = mutableSetOf<String>()
            for ((char, r) in MONO) {
                if (char.code !in HIRAGANA_START..HIRAGANA_END) continue
                if (r.isEmpty()) continue
                // First-write-wins: prefer ざ-row over だ-row for "ji"/"zu" because that's the
                // common modern spelling users actually type.
                if (seenHira.add(r)) put(r, char.toString())
            }
            // Common alt spellings that aren't in the canonical Hepburn table.
            put("si", "し"); put("ti", "ち"); put("tu", "つ"); put("hu", "ふ")
            put("sya", "しゃ"); put("syu", "しゅ"); put("syo", "しょ")
            put("tya", "ちゃ"); put("tyu", "ちゅ"); put("tyo", "ちょ")
            put("zya", "じゃ"); put("zyu", "じゅ"); put("zyo", "じょ")
            put("jya", "じゃ"); put("jyu", "じゅ"); put("jyo", "じょ")
            // Intentionally no "nn" entry: in "konnichiwa" the first n is ん, the second
            // starts a fresh 'ni' mora. Falling back to the 1-char 'n' → ん handles it.
        }
    }

    /**
     * Best-effort Hepburn romaji → hiragana. Lowercased; unknown characters
     * pass through. Handles doubled consonants (kka→っか), 'n' before consonants
     * (anko→あんこ), and long vowels via repeated vowels (ou stays as おう).
     * Empty string in → empty string out.
     */
    fun romajiToHiragana(input: String): String {
        if (input.isEmpty()) return input
        val s = input.lowercase()
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]

            // Pass through non-letters.
            if (c !in 'a'..'z') {
                out.append(c)
                i++
                continue
            }

            // Doubled consonant → sokuon. "kka" / "tta" / "ssa" / "ppa" etc.
            // Also "tch" → "っち". Special case: "nn" handled by 2-char lookup below.
            if (i + 1 < s.length && c == s[i + 1] && c != 'n' && c != 'a' && c != 'i' && c != 'u' && c != 'e' && c != 'o') {
                out.append('っ')
                i++
                continue
            }
            if (c == 't' && i + 2 < s.length && s[i + 1] == 'c' && s[i + 2] == 'h') {
                out.append('っ')
                i++
                continue
            }

            // Try 3-, 2-, then 1-char matches against the table.
            val three = if (i + 3 <= s.length) ROMAJI_TO_HIRA[s.substring(i, i + 3)] else null
            if (three != null) { out.append(three); i += 3; continue }
            val two = if (i + 2 <= s.length) ROMAJI_TO_HIRA[s.substring(i, i + 2)] else null
            if (two != null) { out.append(two); i += 2; continue }
            val one = ROMAJI_TO_HIRA[s.substring(i, i + 1)]
            if (one != null) { out.append(one); i++; continue }

            // 'n' before another consonant (or end of input) → ん.
            if (c == 'n') {
                val next = if (i + 1 < s.length) s[i + 1] else null
                if (next == null || next !in "aiueoy") {
                    out.append('ん')
                    i++
                    continue
                }
            }

            // Unknown character — drop it (rather than emit garbage that ruins lookups).
            i++
        }
        return out.toString()
    }
}

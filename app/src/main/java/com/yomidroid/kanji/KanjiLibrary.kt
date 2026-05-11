package com.yomidroid.kanji

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.google.gson.Gson

@Immutable
data class KanjiInfo(
    val character: String,
    val grade: Int?,
    val strokeCount: Int,
    val jlpt: Int?,            // 5 = N5 (easiest), 1 = N1 (hardest)
    val meanings: List<String>,
    val onReadings: List<String>,
    val kunReadings: List<String>,
    val nameReadings: List<String>,
    val freqMainichi: Int?,
    val heisigEn: String?,
) {
    val meaningsLower: String by lazy { meanings.joinToString(" ").lowercase() }
    val readingsNormalized: List<String> by lazy {
        (onReadings + kunReadings + nameReadings).map { normalizeReading(it) }
    }
}

private data class KanjiDataJson(
    val version: Int,
    val entries: Map<String, KanjiEntryJson>,
)

private data class KanjiEntryJson(
    val g: Int?,
    val s: Int,
    val j: Int?,
    val m: List<String>?,
    val on: List<String>?,
    val kun: List<String>?,
    val nr: List<String>?,
    val f: Int?,
    val h: String?,
)

/** Strip okurigana dot and prefix/suffix dashes; convert katakana to hiragana. */
internal fun normalizeReading(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) {
        when {
            c == '.' || c == '-' || c == '。' -> { /* skip dot/dash */ }
            c in 'ァ'..'ヶ' -> sb.append((c.code - 0x60).toChar()) // katakana → hiragana
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

class KanjiLibrary private constructor(context: Context) {

    companion object {
        private const val TAG = "KanjiLibrary"
        private const val ASSET_DATA = "kanji_data.json"

        @Volatile
        private var instance: KanjiLibrary? = null

        fun getInstance(context: Context): KanjiLibrary =
            instance ?: synchronized(this) {
                instance ?: KanjiLibrary(context.applicationContext).also { instance = it }
            }

        /** Map kanjiapi JLPT int (5..1) → user-facing N-level label. */
        fun jlptLabel(j: Int?): String? = when (j) {
            5 -> "N5"
            4 -> "N4"
            3 -> "N3"
            2 -> "N2"
            1 -> "N1"
            else -> null
        }

        fun gradeLabel(g: Int?): String? = when (g) {
            null -> null
            in 1..6 -> "Grade $g"
            7 -> "Grade 7"
            8 -> "Jōyō (HS)"
            9, 10 -> "Jinmeiyō"
            else -> "Grade $g"
        }
    }

    private val gson = Gson()
    private var entries: Map<String, KanjiInfo> = emptyMap()
    private var allSorted: List<KanjiInfo> = emptyList()
    private var isLoaded = false

    init {
        load(context)
    }

    private fun load(context: Context) {
        try {
            val kanjiJson = context.assets.open(ASSET_DATA)
                .bufferedReader().use { it.readText() }
            val parsed = gson.fromJson(kanjiJson, KanjiDataJson::class.java)
            val map = HashMap<String, KanjiInfo>(parsed.entries.size)
            for ((ch, v) in parsed.entries) {
                map[ch] = KanjiInfo(
                    character = ch,
                    grade = v.g,
                    strokeCount = v.s,
                    jlpt = v.j,
                    meanings = v.m ?: emptyList(),
                    onReadings = v.on ?: emptyList(),
                    kunReadings = v.kun ?: emptyList(),
                    nameReadings = v.nr ?: emptyList(),
                    freqMainichi = v.f,
                    heisigEn = v.h,
                )
            }
            entries = map
            allSorted = map.values.sortedWith(
                compareBy(
                    { it.freqMainichi ?: Int.MAX_VALUE },
                    { -(it.jlpt ?: 0) },
                    { it.strokeCount },
                )
            )

            isLoaded = true
            Log.i(TAG, "Loaded ${entries.size} kanji")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load kanji data", t)
        }
    }

    fun get(character: String): KanjiInfo? = entries[character]

    fun all(): List<KanjiInfo> = allSorted

    fun byGrade(grade: Int): List<KanjiInfo> =
        allSorted.filter { it.grade == grade }

    fun byJlpt(jlpt: Int): List<KanjiInfo> =
        allSorted.filter { it.jlpt == jlpt }

    /** All kanji that have a grade (i.e. Jōyō/Jinmeiyō), sorted by grade then frequency. */
    fun joyoAndJinmeiyo(): List<KanjiInfo> =
        allSorted.filter { it.grade != null }
            .sortedWith(compareBy({ it.grade ?: Int.MAX_VALUE }, { it.freqMainichi ?: Int.MAX_VALUE }))

    /**
     * Search by meaning (English) or reading (kana, normalized).
     * - Single CJK char query → return that kanji if it exists.
     * - All-kana query → return kanji whose readings contain the query.
     * - Otherwise treat as meaning substring (case-insensitive).
     */
    fun search(query: String): List<KanjiInfo> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        // Single-kanji direct lookup
        if (q.length == 1 && isKanjiChar(q[0])) {
            return entries[q]?.let { listOf(it) } ?: emptyList()
        }

        // All-kana query → reading search. Exact matches first, then substrings.
        if (q.all { isKana(it) }) {
            val norm = normalizeReading(q)
            val exact = mutableListOf<KanjiInfo>()
            val substr = mutableListOf<KanjiInfo>()
            for (info in allSorted) {
                val readings = info.readingsNormalized
                when {
                    readings.any { it == norm } -> exact.add(info)
                    readings.any { it.contains(norm) } -> substr.add(info)
                }
                if (exact.size + substr.size >= 100) break
            }
            return exact + substr
        }

        // Meaning substring
        val ql = q.lowercase()
        return allSorted.asSequence()
            .filter { it.meaningsLower.contains(ql) }
            .take(200)
            .toList()
    }

    private fun isKana(c: Char): Boolean {
        val code = c.code
        return (code in 0x3040..0x309F) || (code in 0x30A0..0x30FF) || c == 'ー'
    }

    private fun isKanjiChar(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FFF) || (code in 0x3400..0x4DBF)
    }
}

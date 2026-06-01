package com.yomidroid.data

import android.util.JsonReader
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipFile

/**
 * A compact kanji → example-words index built at import time.
 *
 * The Hoshidicts backend is a hash table keyed by exact expression/reading, so
 * it can't answer "words containing this kanji". This index fills that gap for
 * the Kanji Detail "example words" card: while importing a term dictionary we
 * stream the term banks once and bucket short compounds by the kanji they
 * contain. Querying is then a plain map lookup.
 *
 * Bounded to keep build memory + on-disk size small: only short expressions are
 * indexed (long phrases aren't useful example words), and each kanji keeps the
 * top [MAX_PER_KANJI] by the dictionary's own score (JMdict popularity, Yomitan
 * term field 4), so common words surface ahead of obscure ones. Buckets are
 * stored highest-score first.
 */
object KanjiWordIndex {

    private const val TAG = "KanjiWordIndex"
    const val FILE_NAME = "kanji_index.json"
    private const val MAX_PER_KANJI = 50
    private const val MAX_EXPR_LEN = 5
    private const val SCORE_FIELD = 4 // Yomitan term_bank: [expr, reading, defTags, rules, score, ...]

    /** CJK ideographs (Extension A + Unified). BMP only; supplementary kanji are rare. */
    private fun isKanji(c: Char): Boolean = c.code in 0x3400..0x9FFF

    /** A bounded per-kanji top-N-by-score collector with dedup by expression. */
    private class Bucket {
        val exprs = ArrayList<String>(MAX_PER_KANJI)
        val scores = ArrayList<Int>(MAX_PER_KANJI)

        fun add(expr: String, score: Int) {
            val existing = exprs.indexOf(expr)
            if (existing >= 0) {
                if (score > scores[existing]) scores[existing] = score
                return
            }
            if (exprs.size < MAX_PER_KANJI) {
                exprs.add(expr); scores.add(score)
            } else {
                var minIdx = 0
                for (i in 1 until scores.size) if (scores[i] < scores[minIdx]) minIdx = i
                if (score > scores[minIdx]) { exprs[minIdx] = expr; scores[minIdx] = score }
            }
        }

        /** Expressions, highest score first then shortest. */
        fun ranked(): List<String> =
            exprs.indices.sortedWith(
                compareByDescending<Int> { scores[it] }.thenBy { exprs[it].length }
            ).map { exprs[it] }
    }

    /**
     * Stream a Yomitan term dictionary's term banks and write a kanji→words
     * index to [outFile]. Reads only each entry's expression + score.
     */
    fun build(zipFile: File, outFile: File) {
        val buckets = HashMap<String, Bucket>()
        try {
            ZipFile(zipFile).use { zip ->
                val banks = zip.entries().toList()
                    .filter { it.name.startsWith("term_bank_") && it.name.endsWith(".json") }
                for (entry in banks) {
                    JsonReader(InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8)).use { reader ->
                        reader.beginArray()
                        while (reader.hasNext()) {
                            reader.beginArray()
                            val expression = if (reader.hasNext()) reader.nextString() else ""
                            var idx = 1
                            var score = 0
                            while (reader.hasNext()) {
                                if (idx == SCORE_FIELD && reader.peek() == android.util.JsonToken.NUMBER) {
                                    score = reader.nextInt()
                                } else {
                                    reader.skipValue()
                                }
                                idx++
                            }
                            reader.endArray()
                            index(expression, score, buckets)
                        }
                        reader.endArray()
                    }
                }
            }
            val ranked = buckets.mapValues { (_, b) -> b.ranked() }
            outFile.writeText(Gson().toJson(ranked))
            Log.d(TAG, "Built kanji index: ${ranked.size} kanji -> ${outFile.length() / 1024}KB")
        } catch (e: Exception) {
            Log.w(TAG, "Kanji index build failed: ${e.message}")
        }
    }

    private fun index(expr: String, score: Int, buckets: MutableMap<String, Bucket>) {
        if (expr.isEmpty() || expr.length > MAX_EXPR_LEN) return
        val seen = HashSet<Char>()
        for (c in expr) {
            if (isKanji(c) && seen.add(c)) {
                buckets.getOrPut(c.toString()) { Bucket() }.add(expr, score)
            }
        }
    }

    /** Load a previously-built index, or empty if absent/corrupt. */
    fun load(file: File): Map<String, List<String>> {
        if (!file.exists()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, List<String>>>() {}.type
            Gson().fromJson<Map<String, List<String>>>(file.readText(), type) ?: emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "Kanji index load failed: ${e.message}")
            emptyMap()
        }
    }
}

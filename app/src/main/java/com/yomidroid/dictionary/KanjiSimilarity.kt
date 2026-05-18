package com.yomidroid.dictionary

import android.content.Context
import org.json.JSONObject

/**
 * Lookup table of visually similar kanji, used to suggest replacements when
 * OCR misreads a character. Backed by Lars Yencken's jōyō kanji confusion
 * dataset (stroke-edit-distance + Yeh & Li radical similarity, blended).
 *
 * Asset: assets/kanji_similarity/yencken_top.json — `{kanji: "neighbors..."}`
 * License: CC-BY 3.0 (Yencken, 2010).
 */
object KanjiSimilarity {

    private const val ASSET_PATH = "kanji_similarity/yencken_top.json"

    @Volatile private var table: Map<Char, String>? = null

    fun ensureLoaded(context: Context) {
        if (table != null) return
        synchronized(this) {
            if (table != null) return
            val json = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val map = HashMap<Char, String>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k.length == 1) map[k[0]] = obj.getString(k)
            }
            table = map
        }
    }

    /**
     * Top visually similar kanji for [ch], or an empty list when [ch] is not
     * in the table (e.g. non-jōyō, kana, punctuation).
     */
    fun neighbors(ch: Char): List<Char> {
        val t = table ?: return emptyList()
        val s = t[ch] ?: return emptyList()
        return s.toList()
    }
}

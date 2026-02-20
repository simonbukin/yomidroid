package com.yomidroid.dictionary

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Singleton for O(1) JLPT level lookups.
 * Lazily loads jlpt_vocab.json from assets into a HashMap.
 */
object JlptLookup {
    private const val TAG = "JlptLookup"
    private var levelMap: Map<String, String>? = null

    /**
     * Initialize from assets. Safe to call multiple times — only loads once.
     */
    fun init(context: Context) {
        if (levelMap != null) return
        try {
            val json = context.assets.open("jlpt_vocab.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val map = HashMap<String, String>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = obj.getString(key)
            }
            levelMap = map
            Log.d(TAG, "Loaded ${map.size} JLPT entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load JLPT data: ${e.message}")
            levelMap = emptyMap()
        }
    }

    /**
     * Get the JLPT level for an expression (e.g. "N5", "N1").
     * Returns null if not in the JLPT word lists.
     */
    fun getLevel(expression: String): String? {
        return levelMap?.get(expression)
    }
}

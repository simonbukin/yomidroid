package com.yomidroid.dictionary

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin wrapper for the Hoshidicts (C++23) dictionary backend JNI interface.
 *
 * Hoshidicts owns the term/frequency/pitch lookup path: it imports Yomitan
 * `.zip` dictionaries into a compact mmap'd binary format and performs
 * Yomitan-style longest-match lookups with built-in deinflection. Results cross
 * the JNI boundary as JSON; this class parses them into [HoshiTerm] so
 * [DictionaryEngine] can map them onto [DictionaryEntry].
 *
 * The native side holds a single DictionaryQuery + Yomitan deinflector. Rebuild
 * the dictionary set with [reset] followed by `add*Dict` calls (in priority
 * order) whenever the installed-dictionary config changes — mirrors
 * `DictionaryDb.reloadFromConfig`.
 */
object HoshiDicts {

    private const val TAG = "HoshiDicts"

    @Volatile
    private var libraryLoaded = false

    @Synchronized
    private fun ensureLibrary() {
        if (!libraryLoaded) {
            System.loadLibrary("yomidroid_dict")
            libraryLoaded = true
        }
    }

    // --- Native methods ---
    private external fun nativeImport(zipPath: String, outputDir: String, lowRam: Boolean): String
    private external fun nativeReset()
    private external fun nativeLoad(
        termPaths: Array<String>,
        freqPaths: Array<String>,
        pitchPaths: Array<String>
    )
    private external fun nativeAddTermDict(path: String)
    private external fun nativeAddFreqDict(path: String)
    private external fun nativeAddPitchDict(path: String)
    private external fun nativeLookup(text: String, maxResults: Int, scanLength: Int): String
    private external fun nativeQuery(expression: String): String
    private external fun nativeGetStyles(): String
    private external fun nativeGetMediaFile(dictName: String, mediaPath: String): ByteArray?

    /**
     * Import a Yomitan `.zip` into the compact Hoshidicts format under
     * [outputDir]. The resulting dictionary folder is `outputDir/<title>`.
     */
    fun import(zipPath: String, outputDir: String, lowRam: Boolean = false): HoshiImportResult {
        ensureLibrary()
        return parseImportResult(nativeImport(zipPath, outputDir, lowRam))
    }

    /** Drop all loaded dictionaries and start a fresh, empty query. */
    fun reset() {
        ensureLibrary()
        nativeReset()
    }

    /**
     * Atomically replace the loaded dictionary set. Paths are absolute
     * Hoshidicts folders, given in priority order per category. Concurrent
     * lookups see either the old or new set, never a partial rebuild.
     */
    fun load(
        termPaths: List<String>,
        freqPaths: List<String>,
        pitchPaths: List<String>
    ) {
        ensureLibrary()
        nativeLoad(
            termPaths.toTypedArray(),
            freqPaths.toTypedArray(),
            pitchPaths.toTypedArray()
        )
    }

    fun addTermDict(path: String) { ensureLibrary(); nativeAddTermDict(path) }
    fun addFreqDict(path: String) { ensureLibrary(); nativeAddFreqDict(path) }
    fun addPitchDict(path: String) { ensureLibrary(); nativeAddPitchDict(path) }

    /**
     * Longest-match lookup from the start of [text] (preprocess + deconjugate +
     * query), Hoshidicts-sorted by matched length, preprocessing steps,
     * deconjugation depth, then frequency.
     */
    fun lookup(text: String, maxResults: Int = 16, scanLength: Int = 16): List<HoshiTerm> {
        ensureLibrary()
        if (text.isEmpty()) return emptyList()
        return parseTerms(nativeLookup(text, maxResults, scanLength))
    }

    /** Exact lookup of [expression] (by expression or reading), no deconjugation. */
    fun query(expression: String): List<HoshiTerm> {
        ensureLibrary()
        if (expression.isEmpty()) return emptyList()
        return parseTerms(nativeQuery(expression))
    }

    /** Per-dictionary CSS (`styles.css` from the original Yomitan zips), title → CSS. */
    fun getStyles(): Map<String, String> {
        ensureLibrary()
        val obj = try { JSONObject(nativeGetStyles()) } catch (e: Exception) { return emptyMap() }
        val out = HashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.optString(k)
        }
        return out
    }

    /** Raw bytes for a media file stored inside a term dictionary, or null. */
    fun getMediaFile(dictName: String, mediaPath: String): ByteArray? {
        ensureLibrary()
        return nativeGetMediaFile(dictName, mediaPath)
    }

    // --- JSON parsing ---

    private fun parseImportResult(json: String): HoshiImportResult {
        return try {
            val o = JSONObject(json)
            HoshiImportResult(
                success = o.optBoolean("success", false),
                title = o.optString("title", ""),
                termCount = o.optInt("termCount", 0),
                metaCount = o.optInt("metaCount", 0),
                freqCount = o.optInt("freqCount", 0),
                pitchCount = o.optInt("pitchCount", 0),
                mediaCount = o.optInt("mediaCount", 0),
                errors = o.optJSONArray("errors")?.let { arr ->
                    List(arr.length()) { arr.optString(it) }
                } ?: emptyList()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse import result: ${e.message}")
            HoshiImportResult(false, "", 0, 0, 0, 0, 0, listOf(e.message ?: "parse error"))
        }
    }

    private fun parseTerms(json: String): List<HoshiTerm> {
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i -> parseTerm(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse lookup results: ${e.message}")
            emptyList()
        }
    }

    private fun parseTerm(o: JSONObject): HoshiTerm {
        val glossaries = o.optJSONArray("glossaries")?.let { arr ->
            List(arr.length()) { i ->
                val g = arr.getJSONObject(i)
                HoshiGlossary(
                    dict = g.optString("dict"),
                    definitionTags = g.optString("definitionTags"),
                    termTags = g.optString("termTags"),
                    // Keep the structured-content array as raw JSON for the renderer.
                    glossaryJson = g.optJSONArray("glossary")?.toString() ?: "[]"
                )
            }
        } ?: emptyList()

        val frequencies = o.optJSONArray("frequencies")?.let { arr ->
            List(arr.length()) { i ->
                val f = arr.getJSONObject(i)
                HoshiFreq(
                    dict = f.optString("dict"),
                    values = f.optJSONArray("values").toIntList(),
                    display = f.optJSONArray("display").toStringList()
                )
            }
        } ?: emptyList()

        val pitches = o.optJSONArray("pitches")?.let { arr ->
            List(arr.length()) { i ->
                val p = arr.getJSONObject(i)
                HoshiPitch(dict = p.optString("dict"), positions = p.optJSONArray("positions").toIntList())
            }
        } ?: emptyList()

        val steps = o.optJSONArray("steps")?.let { arr ->
            List(arr.length()) { i ->
                val s = arr.getJSONObject(i)
                HoshiStep(name = s.optString("name"), description = s.optString("description"))
            }
        } ?: emptyList()

        return HoshiTerm(
            matched = o.optString("matched"),
            deinflected = o.optString("deinflected"),
            preprocessorSteps = o.optInt("preprocessorSteps", 0),
            steps = steps,
            expression = o.optString("expression"),
            reading = o.optString("reading"),
            rules = o.optString("rules"),
            glossaries = glossaries,
            frequencies = frequencies,
            pitches = pitches
        )
    }

    private fun JSONArray?.toIntList(): List<Int> =
        if (this == null) emptyList() else List(length()) { optInt(it) }

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else List(length()) { optString(it) }
}

data class HoshiGlossary(
    val dict: String,
    val definitionTags: String,
    val termTags: String,
    /** Raw Yomitan definitions array as a JSON string (strings + structured content). */
    val glossaryJson: String
)

data class HoshiFreq(val dict: String, val values: List<Int>, val display: List<String>)

data class HoshiPitch(val dict: String, val positions: List<Int>)

/** One deinflection step from native: compact display [name] + full [description]. */
data class HoshiStep(val name: String, val description: String)

data class HoshiTerm(
    val matched: String,
    val deinflected: String,
    val preprocessorSteps: Int,
    val steps: List<HoshiStep>,
    val expression: String,
    val reading: String,
    val rules: String,
    val glossaries: List<HoshiGlossary>,
    val frequencies: List<HoshiFreq>,
    val pitches: List<HoshiPitch>
)

data class HoshiImportResult(
    val success: Boolean,
    val title: String,
    val termCount: Int,
    val metaCount: Int,
    val freqCount: Int,
    val pitchCount: Int,
    val mediaCount: Int,
    val errors: List<String>
)

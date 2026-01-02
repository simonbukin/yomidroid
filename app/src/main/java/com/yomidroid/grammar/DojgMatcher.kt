package com.yomidroid.grammar

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * JSON structure for DOJG data file.
 */
private data class DojgData(
    val version: String,
    val source: String,
    val grammarPoints: List<DojgGrammarPointJson>
)

private data class DojgGrammarPointJson(
    val id: String,
    val pattern: String,
    val level: String,
    val meaning: String,
    val formation: List<String> = emptyList(),
    val examples: List<DojgExampleJson> = emptyList(),
    val searchPatterns: List<String> = emptyList(),
    val sourceUrl: String
)

private data class DojgExampleJson(
    val ja: String,
    val en: String
)

/**
 * Matches text against DOJG (Dictionary of Japanese Grammar) patterns.
 *
 * Loads grammar patterns from dojg-data.json in assets and provides
 * regex-based matching to detect grammar points in analyzed text.
 */
class DojgMatcher private constructor(context: Context) {

    companion object {
        private const val TAG = "DojgMatcher"
        private const val DOJG_ASSET_PATH = "dojg-data.json"

        @Volatile
        private var instance: DojgMatcher? = null

        fun getInstance(context: Context): DojgMatcher {
            return instance ?: synchronized(this) {
                instance ?: DojgMatcher(context.applicationContext).also { instance = it }
            }
        }
    }

    private val gson = Gson()
    private var grammarPoints: List<DojgGrammarPointJson> = emptyList()
    private var compiledPatterns: List<Pair<DojgGrammarPointJson, List<Pattern>>> = emptyList()
    private var isLoaded = false

    init {
        loadDojgData(context)
    }

    private fun loadDojgData(context: Context) {
        try {
            val jsonString = context.assets.open(DOJG_ASSET_PATH)
                .bufferedReader()
                .use { it.readText() }

            val data = gson.fromJson(jsonString, DojgData::class.java)
            grammarPoints = data.grammarPoints
            Log.d(TAG, "Loaded ${grammarPoints.size} grammar points from DOJG data")

            // Pre-compile regex patterns for performance
            compiledPatterns = grammarPoints.mapNotNull { point ->
                val patterns = point.searchPatterns.mapNotNull { pattern ->
                    try {
                        // Compile with UNICODE_CASE for proper Japanese handling
                        Pattern.compile(pattern, Pattern.UNICODE_CASE)
                    } catch (e: PatternSyntaxException) {
                        Log.w(TAG, "Invalid regex pattern for ${point.pattern}: $pattern")
                        null
                    }
                }
                if (patterns.isNotEmpty()) {
                    point to patterns
                } else {
                    null
                }
            }

            Log.d(TAG, "Compiled ${compiledPatterns.size} grammar point patterns")
            isLoaded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load DOJG data: ${e.message}")
            grammarPoints = emptyList()
            compiledPatterns = emptyList()
            isLoaded = false
        }
    }

    /**
     * Find grammar points in the given text.
     * Returns a list of detected grammar points with their matched positions.
     */
    suspend fun findGrammarPoints(text: String): List<DetectedGrammarPoint> =
        withContext(Dispatchers.Default) {
            if (text.isBlank() || compiledPatterns.isEmpty()) {
                return@withContext emptyList()
            }

            val results = mutableListOf<DetectedGrammarPoint>()

            for ((point, patterns) in compiledPatterns) {
                for (pattern in patterns) {
                    try {
                        val matcher = pattern.matcher(text)
                        while (matcher.find()) {
                            results.add(
                                DetectedGrammarPoint(
                                    id = point.id,
                                    pattern = point.pattern,
                                    level = point.level,
                                    meaning = point.meaning,
                                    formation = point.formation,
                                    examples = point.examples.map { DojgExample(it.ja, it.en) },
                                    matchedText = matcher.group(),
                                    startIndex = matcher.start(),
                                    endIndex = matcher.end(),
                                    sourceUrl = point.sourceUrl
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error matching pattern for ${point.pattern}: ${e.message}")
                    }
                }
            }

            // Remove duplicates (same pattern at same position) and sort by position
            results.distinctBy { "${it.pattern}:${it.startIndex}" }
                .sortedBy { it.startIndex }
        }

    /**
     * Search for grammar points by pattern name.
     * Useful for browsing/searching the grammar library.
     */
    fun searchByPattern(query: String): List<DetectedGrammarPoint> {
        if (query.isBlank()) return emptyList()

        val lowerQuery = query.lowercase()
        return grammarPoints
            .filter {
                it.pattern.lowercase().contains(lowerQuery) ||
                it.meaning.lowercase().contains(lowerQuery)
            }
            .take(20)
            .map { point ->
                DetectedGrammarPoint(
                    id = point.id,
                    pattern = point.pattern,
                    level = point.level,
                    meaning = point.meaning,
                    formation = point.formation,
                    examples = point.examples.map { DojgExample(it.ja, it.en) },
                    matchedText = "",
                    startIndex = 0,
                    endIndex = 0,
                    sourceUrl = point.sourceUrl
                )
            }
    }

    /**
     * Get all grammar points for a given level.
     */
    fun getByLevel(level: String): List<DetectedGrammarPoint> {
        return grammarPoints
            .filter { it.level == level }
            .map { point ->
                DetectedGrammarPoint(
                    id = point.id,
                    pattern = point.pattern,
                    level = point.level,
                    meaning = point.meaning,
                    formation = point.formation,
                    examples = point.examples.map { DojgExample(it.ja, it.en) },
                    matchedText = "",
                    startIndex = 0,
                    endIndex = 0,
                    sourceUrl = point.sourceUrl
                )
            }
    }

    /**
     * Check if DOJG data was loaded successfully.
     */
    fun isAvailable(): Boolean = isLoaded && grammarPoints.isNotEmpty()

    /**
     * Get the total count of loaded grammar points.
     */
    fun getGrammarPointCount(): Int = grammarPoints.size
}

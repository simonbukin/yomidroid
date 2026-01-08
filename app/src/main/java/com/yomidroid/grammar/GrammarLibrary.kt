package com.yomidroid.grammar

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GameGengo video information for each JLPT level.
 */
@Immutable
data class GameGengoVideo(
    val id: String,
    val title: String,
    val jlptLevel: String
)

/**
 * A source for a grammar point (GameGengo, JLPTSensei, or DOJG).
 */
@Immutable
data class GrammarSource(
    val name: String,           // "gamegengo", "jlptsensei", "dojg"
    val timestamp: String? = null,       // For GameGengo: "1:23:45"
    val timestampSeconds: Int? = null,   // For GameGengo: computed seconds
    val meaning: String? = null,         // For JLPTSensei
    val url: String? = null              // For DOJG
)

/**
 * A grammar point from the unified library with data from multiple sources.
 * All source lookups are pre-computed at construction time to avoid
 * allocations during Compose recomposition.
 */
@Immutable
data class GrammarLibraryEntry(
    val id: String,
    val pattern: String,
    val jlptLevel: String,
    val meaning: String? = null,
    // Pre-computed source references (avoid .find() during composition)
    val gamegengoSource: GrammarSource? = null,
    val jlptsenseiSource: GrammarSource? = null,
    val dojgSource: GrammarSource? = null,
    // Pre-computed flags and URLs
    val hasVideo: Boolean = false,
    val videoUrl: String? = null,       // Pre-computed YouTube URL
    val dojgUrl: String? = null,        // Direct reference
    val jlptsenseiUrl: String? = null,  // JLPTSensei grammar page
    // Pre-computed for fast search
    val patternLower: String = pattern.lowercase(),
    val meaningLower: String? = meaning?.lowercase()
)

/**
 * JSON structure for the grammar library data file.
 */
private data class GrammarLibraryData(
    val version: Int,
    val sources: Map<String, SourceMetaJson>,
    val videos: Map<String, VideoJson>,
    val grammarPoints: List<GrammarPointJson>
)

private data class SourceMetaJson(
    val name: String,
    val description: String,
    val url: String? = null
)

private data class VideoJson(
    val id: String,
    val title: String
)

private data class SourceJson(
    val name: String,
    val timestamp: String? = null,
    val timestampSeconds: Int? = null,
    val meaning: String? = null,
    val url: String? = null
)

private data class GrammarPointJson(
    val id: String,
    val pattern: String,
    val jlptLevel: String,
    val meaning: String? = null,
    val sources: List<SourceJson>? = null,
    val jlptsenseiUrl: String? = null
)

/**
 * Loads and provides access to the grammar library with GameGengo video integration.
 *
 * The library contains grammar points organized by JLPT level (N5-N2),
 * each linked to a timestamp in GameGengo's comprehensive grammar videos.
 * Points are enriched with DOJG (Dictionary of Japanese Grammar) links
 * where patterns match.
 */
class GrammarLibrary private constructor(context: Context) {

    companion object {
        private const val TAG = "GrammarLibrary"
        private const val ASSET_PATH = "gamegengo-grammar.json"

        @Volatile
        private var instance: GrammarLibrary? = null

        fun getInstance(context: Context): GrammarLibrary {
            return instance ?: synchronized(this) {
                instance ?: GrammarLibrary(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Convert a timestamp string like "2:45" or "1:23:45" to seconds.
         */
        fun parseTimestamp(timestamp: String): Int {
            val parts = timestamp.split(":").map { it.trim().toIntOrNull() ?: 0 }
            return when (parts.size) {
                1 -> parts[0]                                    // seconds only
                2 -> parts[0] * 60 + parts[1]                   // mm:ss
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2] // hh:mm:ss
                else -> 0
            }
        }
    }

    private val gson = Gson()
    private var videos: Map<String, GameGengoVideo> = emptyMap()
    private var grammarPoints: List<GrammarLibraryEntry> = emptyList()
    private var grammarByLevel: Map<String, List<GrammarLibraryEntry>> = emptyMap()
    private var isLoaded = false

    init {
        loadData(context)
    }

    private fun loadData(context: Context) {
        try {
            val jsonString = context.assets.open(ASSET_PATH)
                .bufferedReader()
                .use { it.readText() }

            val data = gson.fromJson(jsonString, GrammarLibraryData::class.java)

            // Convert videos map first (needed for pre-computing video URLs)
            videos = data.videos.mapValues { (level, video) ->
                GameGengoVideo(
                    id = video.id,
                    title = video.title,
                    jlptLevel = level
                )
            }

            // Convert grammar points with ALL lookups pre-computed at load time
            // This eliminates allocations during Compose recomposition
            grammarPoints = data.grammarPoints.map { point ->
                // Parse sources once
                val sourcesList = point.sources ?: emptyList()
                val ggSource = sourcesList.find { it.name == "gamegengo" }?.let {
                    GrammarSource(it.name, it.timestamp, it.timestampSeconds, it.meaning, it.url)
                }
                val jsSource = sourcesList.find { it.name == "jlptsensei" }?.let {
                    GrammarSource(it.name, it.timestamp, it.timestampSeconds, it.meaning, it.url)
                }
                val dojgSource = sourcesList.find { it.name == "dojg" }?.let {
                    GrammarSource(it.name, it.timestamp, it.timestampSeconds, it.meaning, it.url)
                }

                // Pre-compute video URL (avoid string concatenation during composition)
                val videoId = videos[point.jlptLevel]?.id
                val videoUrl = if (ggSource?.timestampSeconds != null && videoId != null) {
                    "https://www.youtube.com/watch?v=$videoId&t=${ggSource.timestampSeconds}s"
                } else null

                GrammarLibraryEntry(
                    id = point.id,
                    pattern = point.pattern,
                    jlptLevel = point.jlptLevel,
                    meaning = point.meaning,
                    gamegengoSource = ggSource,
                    jlptsenseiSource = jsSource,
                    dojgSource = dojgSource,
                    hasVideo = videoUrl != null,
                    videoUrl = videoUrl,
                    dojgUrl = dojgSource?.url,
                    jlptsenseiUrl = point.jlptsenseiUrl,
                    patternLower = point.pattern.lowercase(),
                    meaningLower = point.meaning?.lowercase()
                )
            }

            // Pre-index by level for faster filtering
            grammarByLevel = grammarPoints.groupBy { it.jlptLevel }

            Log.d(TAG, "Loaded ${grammarPoints.size} grammar points from library")
            isLoaded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load grammar library: ${e.message}")
            videos = emptyMap()
            grammarPoints = emptyList()
            grammarByLevel = emptyMap()
            isLoaded = false
        }
    }

    /**
     * Get the GameGengo video info for a JLPT level.
     */
    fun getVideo(jlptLevel: String): GameGengoVideo? = videos[jlptLevel]

    /**
     * Get all available JLPT levels that have grammar points.
     */
    fun getAvailableLevels(): List<String> {
        val levelOrder = listOf("N5", "N4", "N3", "N2", "N1")
        return grammarByLevel.keys.sortedBy { levelOrder.indexOf(it) }
    }

    /**
     * Get all grammar points, optionally filtered by JLPT level.
     * Uses pre-indexed map for fast level filtering.
     */
    fun getGrammarPoints(jlptLevel: String? = null): List<GrammarLibraryEntry> {
        return if (jlptLevel == null) {
            grammarPoints
        } else {
            grammarByLevel[jlptLevel] ?: emptyList()
        }
    }

    /**
     * Search grammar points by pattern or meaning.
     * Uses pre-computed lowercase fields for performance.
     */
    suspend fun search(query: String): List<GrammarLibraryEntry> = withContext(Dispatchers.Default) {
        if (query.isBlank()) return@withContext emptyList()

        val q = query.lowercase()
        grammarPoints.asSequence()
            .filter { entry ->
                entry.patternLower.contains(q) || entry.meaningLower?.contains(q) == true
            }
            .take(50)
            .toList()
    }

    /**
     * Get grammar points that have video timestamps.
     */
    fun getPointsWithVideos(jlptLevel: String? = null): List<GrammarLibraryEntry> {
        return getGrammarPoints(jlptLevel).filter { it.hasVideo }
    }

    /**
     * Get count of grammar points per level.
     * Uses pre-indexed map for instant lookup.
     */
    fun getCountByLevel(): Map<String, Int> {
        return grammarByLevel.mapValues { it.value.size }
    }

    /**
     * Check if the library was loaded successfully.
     */
    fun isAvailable(): Boolean = isLoaded && grammarPoints.isNotEmpty()

    /**
     * Get total count of grammar points.
     */
    fun getTotalCount(): Int = grammarPoints.size
}

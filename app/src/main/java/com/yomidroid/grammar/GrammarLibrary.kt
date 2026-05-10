package com.yomidroid.grammar

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

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
 * A source for a grammar point (GameGengo or DOJG).
 */
@Immutable
data class GrammarSource(
    val name: String,           // "gamegengo" or "dojg"
    val timestamp: String? = null,       // For GameGengo: "1:23:45"
    val timestampSeconds: Int? = null,   // For GameGengo: computed seconds
    val meaning: String? = null,         // Legacy field; unused — kept for JSON backwards-compat
    val url: String? = null              // For DOJG
)

/**
 * A single external reference (video / textbook page / encyclopedia entry)
 * attached to a grammar point. Generic across sources so adding a new one
 * doesn't require new fields on GrammarLibraryEntry.
 */
@Immutable
data class GrammarResource(
    val source: String,                 // "gamegengo", "dojg", "donnatoki", "taekim", "imabi", "hjg", "masterref"
    val title: String,                  // Human-readable label (often == pattern)
    val url: String,
    val timestampSeconds: Int? = null,  // Only meaningful for video sources
    val headline: String? = null,       // Short English gloss as extracted from this source (when available)
    val localAssetPath: String? = null  // Relative path under itazuraneko mirror; reserved for future offline bundling
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
    /**
     * Internal-only: used to look up which GameGengo video timestamp this pattern belongs to.
     * Not rendered in the UI — the user-facing surface treats all grammar entries as level-less.
     */
    val jlptLevel: String?,
    val meaning: String? = null,
    // Pre-computed source references (avoid .find() during composition)
    val gamegengoSource: GrammarSource? = null,
    val dojgSource: GrammarSource? = null,
    // Pre-computed flags and URLs (back-compat with callers; also reflected in `resources`)
    val hasVideo: Boolean = false,
    val videoUrl: String? = null,
    val dojgUrl: String? = null,
    // Generic resource list — render buttons by iterating this in UI
    val resources: List<GrammarResource> = emptyList(),
    // Short English gloss shown in cards. Picked by source priority at load time.
    val headline: String? = null,
    // Pre-computed for fast search
    val patternLower: String = pattern.lowercase(),
    val patternRomaji: String = Romaji.kanaToRomaji(pattern),
    val meaningLower: String? = meaning?.lowercase(),
    val headlineLower: String? = headline?.lowercase()
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
    val sources: List<SourceJson>? = null
)

/**
 * JSON shape for app/src/main/assets/external-grammar/itazuraneko-index.json
 * (produced by scripts/grammar-ingest/scrape_itazuraneko.py).
 */
private data class ItazuranekoIndexJson(
    val version: Int,
    val fetched_at: String?,
    val entries: List<ItazuranekoEntryJson>
)

private data class ItazuranekoEntryJson(
    val pattern: String,
    val headline: String? = null,
    val sources: List<ItazuranekoSourceJson>
)

private data class ItazuranekoSourceJson(
    val source: String,
    val title: String,
    val url: String,
    val headline: String? = null,
    val localPath: String? = null
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
        private const val EXTERNAL_INDEX_PATH = "external-grammar/itazuraneko-index.json"

        // Preferred ordering for resource buttons in UI
        private val SOURCE_PRIORITY = mapOf(
            "gamegengo" to 0,
            "dojg" to 1,
            "donnatoki" to 2,
            "taekim" to 3,
            "imabi" to 4,
            "hjg" to 5,
            "masterref" to 6,
        )

        private val LEVEL_PREFIX_REGEX = Regex("^[㊞㊤㊥]\\s*")  // ㊞㊤㊥ — DOJG level markers
        private val DISAMBIG_SUFFIX_REGEX = Regex("\\(\\s*\\d+\\s*\\)\\s*$")
        private val WHITESPACE_REGEX = Regex("\\s+")

        /**
         * Normalize a grammar pattern string for cross-source deduplication.
         * Mirrors scripts/grammar-ingest/scrape_itazuraneko.py:normalize_pattern_key.
         */
        fun normalizePatternKey(s: String): String {
            var out = Normalizer.normalize(s, Normalizer.Form.NFKC)
            out = LEVEL_PREFIX_REGEX.replace(out, "")
            out = DISAMBIG_SUFFIX_REGEX.replace(out, "")
            out = WHITESPACE_REGEX.replace(out, "")
            return out.trim()
        }

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
                val dojgSource = sourcesList.find { it.name == "dojg" }?.let {
                    GrammarSource(it.name, it.timestamp, it.timestampSeconds, it.meaning, it.url)
                }

                // Pre-compute video URL (avoid string concatenation during composition)
                val videoId = videos[point.jlptLevel]?.id
                val videoUrl = if (ggSource?.timestampSeconds != null && videoId != null) {
                    "https://www.youtube.com/watch?v=$videoId&t=${ggSource.timestampSeconds}s"
                } else null

                // Build generic resources list — used for rendering buttons in UI.
                val resources = buildList {
                    if (videoUrl != null) {
                        add(GrammarResource(
                            source = "gamegengo",
                            title = videos[point.jlptLevel]?.title ?: "GameGengo Video",
                            url = videoUrl,
                            timestampSeconds = ggSource?.timestampSeconds
                        ))
                    }
                    if (dojgSource?.url != null) {
                        add(GrammarResource("dojg", point.pattern, dojgSource.url))
                    }
                }

                GrammarLibraryEntry(
                    id = point.id,
                    pattern = point.pattern,
                    jlptLevel = point.jlptLevel,
                    meaning = point.meaning,
                    gamegengoSource = ggSource,
                    dojgSource = dojgSource,
                    hasVideo = videoUrl != null,
                    videoUrl = videoUrl,
                    dojgUrl = dojgSource?.url,
                    resources = resources,
                    headline = null,  // populated from itazuraneko index if a match exists
                    patternLower = point.pattern.lowercase(),
                    patternRomaji = Romaji.kanaToRomaji(point.pattern),
                    meaningLower = point.meaning?.lowercase(),
                    headlineLower = null
                )
            }

            // Merge in external itazuraneko-aggregated index (Tae Kim, Imabi, DOJG, HJG, donnatoki).
            val baseCount = grammarPoints.size
            grammarPoints = mergeExternalIndex(context, grammarPoints)

            // Pre-index by level for faster filtering (only JLPT-tagged entries appear here)
            grammarByLevel = grammarPoints
                .filter { it.jlptLevel != null }
                .groupBy { it.jlptLevel!! }

            Log.d(TAG, "Loaded ${grammarPoints.size} grammar points (base=$baseCount, +${grammarPoints.size - baseCount} from itazuraneko index)")
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
     * Merge external itazuraneko-sourced patterns into the base library.
     * Returns the combined list. Behavior:
     *   - For each external entry, find a base entry with the same normalized pattern key.
     *   - If found: append the external sources to the base entry's `resources` (dedup by url).
     *   - If not found: create a new entry with `jlptLevel = null` and `meaning = null`.
     * Existing base entries with no external match are kept unchanged.
     */
    private fun mergeExternalIndex(
        context: Context,
        base: List<GrammarLibraryEntry>
    ): List<GrammarLibraryEntry> {
        val external = try {
            context.assets.open(EXTERNAL_INDEX_PATH)
                .bufferedReader()
                .use { it.readText() }
                .let { gson.fromJson(it, ItazuranekoIndexJson::class.java) }
        } catch (e: Exception) {
            Log.w(TAG, "External grammar index not available: ${e.message}")
            return base
        }

        // Build base index by normalized pattern key.
        val byKey: MutableMap<String, GrammarLibraryEntry> = mutableMapOf()
        val baseAsMutable: MutableList<GrammarLibraryEntry> = base.toMutableList()
        base.forEachIndexed { idx, entry ->
            val key = normalizePatternKey(entry.pattern)
            // First occurrence wins; later duplicates fall through (rare for the curated base set).
            byKey.putIfAbsent(key, entry)
        }

        val addedEntries = mutableListOf<GrammarLibraryEntry>()
        var nextSyntheticId = 0

        external.entries.forEach { ext ->
            val key = normalizePatternKey(ext.pattern)
            if (key.isEmpty()) return@forEach

            val mapped = ext.sources.map { s ->
                GrammarResource(
                    source = s.source,
                    title = s.title,
                    url = s.url,
                    headline = s.headline,
                    localAssetPath = s.localPath
                )
            }
            val existing = byKey[key]
            if (existing != null) {
                // Merge into existing entry: append new (source,url) pairs; fill in headline if missing.
                val seen = existing.resources.map { it.source to it.url }.toMutableSet()
                val merged = existing.resources.toMutableList()
                for (r in mapped) {
                    val k = r.source to r.url
                    if (k !in seen) {
                        merged.add(r)
                        seen.add(k)
                    }
                }
                val sorted = merged.sortedBy { SOURCE_PRIORITY[it.source] ?: 99 }
                val newHeadline = existing.headline ?: ext.headline ?: pickDisplayHeadline(sorted)
                val replaced = existing.copy(
                    resources = sorted,
                    headline = newHeadline,
                    headlineLower = newHeadline?.lowercase()
                )
                byKey[key] = replaced
                val idx = baseAsMutable.indexOf(existing)
                if (idx >= 0) baseAsMutable[idx] = replaced
            } else {
                // New synthetic entry (no JLPT level)
                val sorted = mapped.sortedBy { SOURCE_PRIORITY[it.source] ?: 99 }
                val headline = ext.headline ?: pickDisplayHeadline(sorted)
                val newEntry = GrammarLibraryEntry(
                    id = "itz-${"%05d".format(nextSyntheticId++)}",
                    pattern = ext.pattern,
                    jlptLevel = null,
                    meaning = null,
                    resources = sorted,
                    headline = headline,
                    patternLower = ext.pattern.lowercase(),
                    patternRomaji = Romaji.kanaToRomaji(ext.pattern),
                    meaningLower = null,
                    headlineLower = headline?.lowercase()
                )
                byKey[key] = newEntry
                addedEntries.add(newEntry)
            }
        }

        return baseAsMutable + addedEntries
    }

    /** Pick display headline from a sorted list of resources by source priority. */
    private fun pickDisplayHeadline(resources: List<GrammarResource>): String? {
        // resources are already sorted by SOURCE_PRIORITY; first one with a non-empty headline wins
        for (r in resources) {
            val h = r.headline
            if (!h.isNullOrBlank()) return h
        }
        return null
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
     * Get grammar points that have at least one resource from the given source.
     * Used by the Library screen's source filter chips.
     */
    fun getGrammarPointsBySource(source: String): List<GrammarLibraryEntry> {
        return grammarPoints.filter { entry ->
            entry.resources.any { it.source == source }
        }
    }

    /**
     * Count of grammar points per resource source (across all entries).
     * One entry may be counted in multiple sources if it has multiple resources.
     */
    fun getCountBySource(): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (entry in grammarPoints) {
            val seen = mutableSetOf<String>()
            for (r in entry.resources) {
                if (seen.add(r.source)) {
                    counts[r.source] = (counts[r.source] ?: 0) + 1
                }
            }
        }
        return counts
    }

    /**
     * Search grammar points by pattern or meaning.
     * Uses pre-computed lowercase fields for performance.
     */
    /**
     * Multi-field grammar search. Matches against:
     *   - Japanese pattern (entry.patternLower)
     *   - Hepburn romaji of pattern (entry.patternRomaji)
     *   - Display headline (entry.headlineLower)
     *   - Optional meaning (entry.meaningLower)
     *   - Each per-source headline on entry.resources (so e.g. a donnatoki headline
     *     surfaces even when the picked display headline came from DOJG)
     *
     * Results are sorted by which field matched, then alphabetically by pattern.
     * Field priority: pattern > romaji > headline > meaning > resource-headline.
     */
    suspend fun search(query: String): List<GrammarLibraryEntry> = withContext(Dispatchers.Default) {
        if (query.isBlank()) return@withContext emptyList()

        val q = query.lowercase().trim()
        grammarPoints.asSequence()
            .mapNotNull { entry ->
                val rank = matchRank(entry, q) ?: return@mapNotNull null
                Pair(rank, entry)
            }
            .sortedWith(compareBy({ it.first }, { it.second.patternLower }))
            .map { it.second }
            .take(50)
            .toList()
    }

    /** Returns a small integer rank (lower = better) for an entry matching `q`, or null. */
    private fun matchRank(entry: GrammarLibraryEntry, q: String): Int? {
        // Field-priority ranks. Within a field: exact match < startsWith < contains.
        val pl = entry.patternLower
        if (pl == q) return 0
        if (pl.startsWith(q)) return 1
        val pr = entry.patternRomaji
        if (pr == q) return 2
        if (pr.startsWith(q)) return 3
        if (pl.contains(q)) return 4
        if (pr.contains(q)) return 5
        val hl = entry.headlineLower
        if (hl != null) {
            if (hl == q) return 6
            if (hl.startsWith(q)) return 7
            if (hl.contains(q)) return 8
        }
        val ml = entry.meaningLower
        if (ml != null && ml.contains(q)) return 9
        // Per-source headline / resource title fallback
        for (r in entry.resources) {
            val rh = r.headline
            if (rh != null && rh.contains(q, ignoreCase = true)) return 10
            if (r.title.contains(q, ignoreCase = true)) return 11
        }
        return null
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

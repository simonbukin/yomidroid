package com.yomidroid.grammar

import android.content.Context
import android.util.Log

/**
 * Cross-references DOJG grammar detections with GrammarLibrary entries
 * to produce ResolvedGrammarPoints with linked resources (videos, JLPT levels, etc.).
 *
 * Thread-safe singleton. The library index is built once at init time.
 */
class GrammarResolver private constructor(context: Context) {

    companion object {
        private const val TAG = "GrammarResolver"

        @Volatile
        private var instance: GrammarResolver? = null

        fun getInstance(context: Context): GrammarResolver {
            return instance ?: synchronized(this) {
                instance ?: GrammarResolver(context.applicationContext).also { instance = it }
            }
        }
    }

    private val dojgMatcher = DojgMatcher.getInstance(context)
    private val grammarLibrary = GrammarLibrary.getInstance(context)

    // Pre-built index: lowercase pattern -> GrammarLibraryEntry
    private val libraryIndex: Map<String, GrammarLibraryEntry>

    // All library entries for contains-match fallback
    private val allLibraryEntries: List<GrammarLibraryEntry>

    init {
        val entries = grammarLibrary.getGrammarPoints()
        allLibraryEntries = entries
        libraryIndex = entries.associateBy { it.patternLower }
        Log.d(TAG, "Built library index with ${libraryIndex.size} entries")
    }

    /**
     * Find grammar points in text and cross-reference with the grammar library.
     */
    suspend fun resolveGrammar(text: String): List<ResolvedGrammarPoint> {
        val detected = dojgMatcher.findGrammarPoints(text)
        if (detected.isEmpty()) return emptyList()

        val results = detected.map { point ->
            val libraryEntry = findLibraryMatch(point.pattern)
            // Resources: library entry's resources first, plus the DOJG sourceUrl as a fallback
            // so the user always has at least one external reference to open.
            val resources = libraryEntry?.resources.orEmpty().let { libResources ->
                if (libResources.any { it.url == point.sourceUrl }) {
                    libResources
                } else {
                    libResources + GrammarResource("dojg", point.pattern, point.sourceUrl)
                }
            }
            ResolvedGrammarPoint(
                pattern = point.pattern,
                level = point.level,
                meaning = point.meaning,
                formation = point.formation,
                examples = point.examples,
                matchedText = point.matchedText,
                startIndex = point.startIndex,
                endIndex = point.endIndex,
                sourceUrl = point.sourceUrl,
                videoUrl = libraryEntry?.videoUrl,
                libraryMeaning = libraryEntry?.meaning,
                headline = libraryEntry?.headline,
                resources = resources
            )
        }

        // Deduplicate by pattern name, keep earliest occurrence, sort by position
        return results
            .distinctBy { it.pattern }
            .sortedBy { it.startIndex }
    }

    /**
     * Find a matching GrammarLibraryEntry for a DOJG pattern.
     * Tries exact match first, then contains-match with preference for richest entry.
     */
    private fun findLibraryMatch(dojgPattern: String): GrammarLibraryEntry? {
        val lower = dojgPattern.lowercase()

        // Exact match
        libraryIndex[lower]?.let { return it }

        // Contains match: library pattern contains DOJG pattern or vice versa
        val candidates = allLibraryEntries.filter { entry ->
            entry.patternLower.contains(lower) || lower.contains(entry.patternLower)
        }

        if (candidates.isEmpty()) return null

        // Prefer the entry with the most sources
        return candidates.maxByOrNull { entry ->
            var score = 0
            if (entry.hasVideo) score += 4
            if (entry.dojgUrl != null) score += 2
            score + entry.resources.size  // any additional sources (taekim/imabi/hjg/etc.)
        }
    }
}

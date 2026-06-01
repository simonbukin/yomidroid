package com.yomidroid.dictionary

import android.content.Context
import com.yomidroid.data.DictionaryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dictionary engine implementing Yomitan-style longest-match lookup with
 * deconjugation.
 *
 * Deconjugation + longest-match scanning + querying now happen natively in the
 * Hoshidicts backend ([DictionaryDb.lookup]); this class maps the native results
 * to [DictionaryEntry] via [HoshiEntryMapper] and preserves the public API its
 * callers (overlay popup, search, parse tab, history) depend on.
 */
class DictionaryEngine(context: Context) {

    private val dictionaryDb = DictionaryDb.getInstance(context)

    companion object {
        const val MAX_SEARCH_LENGTH = 20

        // Headwords requested per lookup before per-dictionary expansion + sort.
        private const val MAX_RESULTS = 32

        val ENTRY_COMPARATOR: Comparator<DictionaryEntry> =
            compareByDescending<DictionaryEntry> { it.matchedText.length }
                .thenBy { it.inflectionChainLength }
                .thenByDescending { if (it.isExactMatch) 1 else 0 }
                .thenBy { it.frequencyRank ?: Int.MAX_VALUE }
                .thenBy { it.dictionaryPriority }
                .thenBy { it.source == DictionarySource.JMNEDICT }
                .thenByDescending { it.score }
                .thenBy { it.expression }
                .thenByDescending { it.glossary.size }
    }

    /**
     * Find dictionary entries for text starting at a given position, longest
     * match first. Blocking — use [findTermsAsync] from coroutines.
     */
    fun findTerms(text: String, startIndex: Int = 0): List<DictionaryEntry> {
        if (startIndex >= text.length) return emptyList()
        return findTermsBlocking(text.substring(startIndex))
    }

    private fun findTermsBlocking(searchText: String): List<DictionaryEntry> {
        if (searchText.isEmpty()) return emptyList()
        val scanLength = minOf(searchText.length, MAX_SEARCH_LENGTH)
        val terms = dictionaryDb.lookup(searchText, MAX_RESULTS, scanLength)
        return terms
            .flatMap { HoshiEntryMapper.map(it, dictionaryDb) }
            .sortedWith(ENTRY_COMPARATOR)
            .distinctBy { "${it.expression}|${it.reading}|${it.sourceDictId}" }
    }

    suspend fun findTermsAsync(searchText: String): List<DictionaryEntry> =
        withContext(Dispatchers.IO) { findTermsBlocking(searchText) }

    /**
     * Scan the entire text for dictionary matches using longest-match. Returns
     * all unique entries found, in order of appearance.
     */
    fun findAllMatches(text: String): List<DictionaryEntryWithPosition> {
        val matches = mutableListOf<DictionaryEntryWithPosition>()
        val seen = mutableSetOf<String>()
        var i = 0
        while (i < text.length) {
            val char = text[i]
            if (char.isWhitespace() || char in "。、！？「」『』（）…・") {
                i++
                continue
            }
            val bestEntry = findTerms(text, i).firstOrNull()
            if (bestEntry != null && bestEntry.matchedText.isNotEmpty()) {
                val key = "${bestEntry.expression}|${bestEntry.reading}"
                if (key !in seen) {
                    seen.add(key)
                    matches.add(
                        DictionaryEntryWithPosition(
                            entry = bestEntry,
                            startIndex = i,
                            endIndex = i + bestEntry.matchedText.length
                        )
                    )
                }
                i += bestEntry.matchedText.length
            } else {
                i++
            }
        }
        return matches
    }

    suspend fun findAllMatchesAsync(text: String): List<DictionaryEntryWithPosition> =
        withContext(Dispatchers.IO) { findAllMatches(text) }

    /**
     * Longest-match scan within [text]'s [start, end) substring, returning one
     * [DictionaryWordMatch] per matched word with ALL candidate entries — used
     * by the parse tab to align lookups with Kuromoji bunsetsu boundaries.
     */
    fun findAllMatchesGrouped(text: String, start: Int, end: Int): List<DictionaryWordMatch> {
        val matches = mutableListOf<DictionaryWordMatch>()
        val safeEnd = end.coerceAtMost(text.length)
        var i = start.coerceAtLeast(0)
        while (i < safeEnd) {
            val ch = text[i]
            if (ch.isWhitespace() || ch in "。、！？「」『』（）…・") {
                i++
                continue
            }
            val candidates = findTermsBlocking(text.substring(i, safeEnd))
            val best = candidates.firstOrNull()
            if (best != null && best.matchedText.isNotEmpty()) {
                matches.add(
                    DictionaryWordMatch(
                        matchedText = best.matchedText,
                        startIndex = i,
                        candidates = candidates
                    )
                )
                i += best.matchedText.length
            } else {
                i++
            }
        }
        return matches
    }

    suspend fun findAllMatchesGroupedAsync(text: String, start: Int, end: Int): List<DictionaryWordMatch> =
        withContext(Dispatchers.IO) { findAllMatchesGrouped(text, start, end) }

    /**
     * Search for a term with deconjugation. Longest matches rank first, so a
     * full-word query surfaces its exact entry above any sub-string matches.
     */
    suspend fun searchTerm(query: String): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        findTermsBlocking(query)
    }

    /** Find exact matches for a term (by expression or reading, no deconjugation). */
    suspend fun findExact(term: String): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        dictionaryDb.query(term)
            .flatMap { HoshiEntryMapper.map(it, dictionaryDb) }
            .sortedWith(ENTRY_COMPARATOR)
            .distinctBy { "${it.expression}|${it.reading}|${it.sourceDictId}" }
    }

    suspend fun isDictionaryLoaded(): Boolean = withContext(Dispatchers.IO) {
        dictionaryDb.isValid()
    }

    suspend fun getDictionaryCount(): Int = withContext(Dispatchers.IO) {
        dictionaryDb.count()
    }
}

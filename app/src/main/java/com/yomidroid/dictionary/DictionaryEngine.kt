package com.yomidroid.dictionary

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yomidroid.data.DictionaryDb
import com.yomidroid.data.TermData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dictionary engine implementing Yomitan-style longest-match lookup with deinflection.
 */
class DictionaryEngine(context: Context) {

    private val dictionaryDb = DictionaryDb.getInstance(context)
    private val transformer = LanguageTransformer()
    private val gson = Gson()

    companion object {
        const val MAX_SEARCH_LENGTH = 20
    }

    /**
     * Find dictionary entries for text starting at a given position.
     * Uses longest-first matching with deinflection.
     * This is a blocking call - use findTermsAsync for coroutines.
     *
     * @param text The full text to search in
     * @param startIndex The character index to start searching from (default 0)
     * @return List of dictionary entries, sorted by match length then score
     */
    fun findTerms(text: String, startIndex: Int = 0): List<DictionaryEntry> {
        if (startIndex >= text.length) return emptyList()

        val searchText = text.substring(startIndex)
        return findTermsBlocking(searchText)
    }

    /**
     * Blocking version of term search (for use on background threads).
     */
    private fun findTermsBlocking(searchText: String): List<DictionaryEntry> {
        val results = mutableListOf<DictionaryEntry>()
        val maxLen = minOf(searchText.length, MAX_SEARCH_LENGTH)

        // Try progressively shorter substrings (longest first)
        for (len in maxLen downTo 1) {
            val query = searchText.substring(0, len)

            // Get all deinflected variants
            val variants = transformer.getVariants(query)

            for (variant in variants) {
                // Query database for this variant
                val entities = dictionaryDb.findByExpressionOrReading(variant.text)

                for (entity in entities) {
                    // Convert to DictionaryEntry with match info
                    val entry = entityToEntry(entity, query, variant)
                    results.add(entry)
                }
            }
        }

        // Sort by: match length (longest first), names last, score, then frequency
        return results.sortedWith(
            compareByDescending<DictionaryEntry> { it.matchedText.length }
                .thenBy { it.source == DictionarySource.JMNEDICT }  // Names last
                .thenByDescending { it.score }                       // Score first (particles have high scores)
                .thenBy { it.frequencyRank ?: Int.MAX_VALUE }        // Then frequency
        ).distinctBy { "${it.expression}|${it.reading}|${it.source}" }
    }

    /**
     * Async version of findTerms
     */
    suspend fun findTermsAsync(searchText: String): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        findTermsBlocking(searchText)
    }

    /**
     * Scan the entire text for dictionary matches using longest-match.
     * Returns all unique entries found, in order of appearance.
     *
     * @param text The full text to scan
     * @return List of DictionaryEntry with position info
     */
    fun findAllMatches(text: String): List<DictionaryEntryWithPosition> {
        val matches = mutableListOf<DictionaryEntryWithPosition>()
        val seen = mutableSetOf<String>() // Track seen expressions to avoid duplicates
        var i = 0

        while (i < text.length) {
            // Skip whitespace and punctuation
            val char = text[i]
            if (char.isWhitespace() || char in "。、！？「」『』（）…・") {
                i++
                continue
            }

            // Try to find a match starting at this position
            val entries = findTerms(text, i)
            val bestEntry = entries.firstOrNull()

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
                // Skip past the matched text
                i += bestEntry.matchedText.length
            } else {
                // No match, move forward one character
                i++
            }
        }

        return matches
    }

    /**
     * Async version of findAllMatches
     */
    suspend fun findAllMatchesAsync(text: String): List<DictionaryEntryWithPosition> = withContext(Dispatchers.IO) {
        findAllMatches(text)
    }

    /**
     * Find exact match for a term (no deinflection)
     */
    suspend fun findExact(term: String): List<DictionaryEntry> = withContext(Dispatchers.IO) {
        val entities = dictionaryDb.findByExpressionOrReading(term)
        entities.map { entityToEntry(it, term, Variant(term, emptySet(), emptyList())) }
    }

    /**
     * Check if the dictionary is loaded and has entries
     */
    suspend fun isDictionaryLoaded(): Boolean = withContext(Dispatchers.IO) {
        dictionaryDb.count() > 0
    }

    /**
     * Get the count of dictionary entries
     */
    suspend fun getDictionaryCount(): Int = withContext(Dispatchers.IO) {
        dictionaryDb.count()
    }

    private fun entityToEntry(
        entity: TermData,
        matchedText: String,
        variant: Variant
    ): DictionaryEntry {
        val glossaryList = try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(entity.glossary, type) ?: listOf(entity.glossary)
        } catch (e: Exception) {
            listOf(entity.glossary)
        }

        val posList = try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(entity.partsOfSpeech, type) ?: emptyList()
        } catch (e: Exception) {
            if (entity.partsOfSpeech.isNotEmpty()) {
                entity.partsOfSpeech.split(",").map { it.trim() }
            } else {
                emptyList()
            }
        }

        // Build deinflection path string
        val deinflectionPath = if (variant.path.isNotEmpty()) {
            variant.path.joinToString(" > ")
        } else {
            ""
        }

        return DictionaryEntry(
            id = entity.id,
            expression = entity.expression,
            reading = entity.reading,
            glossary = glossaryList,
            partsOfSpeech = posList,
            score = entity.score,
            matchedText = matchedText,
            deinflectionPath = deinflectionPath,
            source = DictionarySource.fromString(entity.source),
            nameType = NameType.fromString(entity.nameType),
            frequencyRank = entity.frequencyRank
        )
    }
}

package com.yomidroid.ocr

/**
 * Maps a character in the unified text string back to its source OcrResult and local index.
 */
data class CharMapping(
    val ocrResult: OcrResult,
    val charIndex: Int,       // Index within the OcrResult.text
    val unifiedIndex: Int     // Index in the unified string
)

/**
 * Combines all OcrResult objects into a single unified text string while maintaining
 * bidirectional mappings between unified indices and (OcrResult, charIndex) pairs.
 *
 * This enables:
 * - Searching across all text on screen (not just one line)
 * - Highlighting matched characters that may span multiple OcrResults
 *
 * Example:
 * ```
 * OcrResults: ["私は", "勉強する"]
 * UnifiedText: "私は勉強する"
 * Mapping: unified[0] -> (result0, 0), unified[2] -> (result1, 0)
 * ```
 */
class UnifiedOcrContext(val ocrResults: List<OcrResult>) {

    /**
     * Combined text from all OcrResults (concatenated without separators).
     * Japanese text flows continuously without word boundaries.
     */
    val unifiedText: String

    /**
     * Map: unified string index -> CharMapping
     * Array-based for O(1) lookup.
     */
    private val unifiedToLocal: Array<CharMapping>

    /**
     * Map: (OcrResult index, charIndex) -> unified index
     * HashMap for O(1) reverse lookup.
     */
    private val localToUnified: Map<Pair<Int, Int>, Int>

    /**
     * Map: OcrResult -> its index in ocrResults list.
     * For fast OcrResult to index lookup.
     */
    private val resultToIndex: Map<OcrResult, Int>

    init {
        val textBuilder = StringBuilder()
        val mappings = mutableListOf<CharMapping>()
        val reverseMap = mutableMapOf<Pair<Int, Int>, Int>()
        val resultIndexMap = mutableMapOf<OcrResult, Int>()

        ocrResults.forEachIndexed { resultIdx, result ->
            resultIndexMap[result] = resultIdx
            result.text.forEachIndexed { charIdx, _ ->
                val unifiedIdx = textBuilder.length + charIdx
                mappings.add(CharMapping(result, charIdx, unifiedIdx))
                reverseMap[resultIdx to charIdx] = unifiedIdx
            }
            textBuilder.append(result.text)
        }

        unifiedText = textBuilder.toString()
        unifiedToLocal = mappings.toTypedArray()
        localToUnified = reverseMap
        resultToIndex = resultIndexMap
    }

    /**
     * Get unified index from local (OcrResult, charIndex).
     * Returns the starting index in unifiedText for this position.
     *
     * @param ocrResult The OcrResult containing the character
     * @param charIndex The index within that OcrResult's text
     * @return The index in unifiedText, or -1 if not found
     */
    fun getUnifiedIndex(ocrResult: OcrResult, charIndex: Int): Int {
        val resultIdx = resultToIndex[ocrResult] ?: return -1
        if (charIndex < 0 || charIndex >= ocrResult.text.length) return -1
        return localToUnified[resultIdx to charIndex] ?: -1
    }

    /**
     * Get CharMapping at a unified index.
     *
     * @param unifiedIndex Index in the unified text string
     * @return CharMapping with source OcrResult and local char index, or null if out of bounds
     */
    fun getLocalPosition(unifiedIndex: Int): CharMapping? {
        if (unifiedIndex < 0 || unifiedIndex >= unifiedToLocal.size) return null
        return unifiedToLocal[unifiedIndex]
    }

    /**
     * Get all CharMappings for a range of unified indices.
     * Useful for highlighting a match that spans multiple OcrResults.
     *
     * @param startUnified Starting index in unified text
     * @param length Number of characters in the match
     * @return List of CharMappings for each character in the range
     */
    fun getCharMappingsInRange(startUnified: Int, length: Int): List<CharMapping> {
        if (startUnified < 0 || length <= 0) return emptyList()
        val end = minOf(startUnified + length, unifiedToLocal.size)
        if (startUnified >= end) return emptyList()
        return (startUnified until end).map { unifiedToLocal[it] }
    }

    /**
     * Get the OcrResult index for a given OcrResult.
     * Useful for cache keys and comparison.
     */
    fun getResultIndex(ocrResult: OcrResult): Int {
        return resultToIndex[ocrResult] ?: -1
    }
}

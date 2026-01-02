package com.yomidroid.ocr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository for sharing OCR results between the accessibility service and UI screens.
 *
 * This allows the Grammar Analyzer screen to access the latest OCR'd text
 * without needing direct communication with the AccessibilityService.
 */
object OcrResultRepository {

    private val _latestOcrText = MutableStateFlow<String?>(null)
    /** The combined text from the latest OCR scan */
    val latestOcrText: StateFlow<String?> = _latestOcrText.asStateFlow()

    private val _latestOcrResults = MutableStateFlow<List<OcrResult>>(emptyList())
    /** The full OCR results with bounding boxes from the latest scan */
    val latestOcrResults: StateFlow<List<OcrResult>> = _latestOcrResults.asStateFlow()

    private val _lastUpdateTimestamp = MutableStateFlow(0L)
    /** Timestamp of the last update (for UI refresh triggers) */
    val lastUpdateTimestamp: StateFlow<Long> = _lastUpdateTimestamp.asStateFlow()

    /**
     * Called by AccessibilityService when OCR completes.
     * Updates the stored results for consumption by UI screens.
     */
    fun updateOcrResults(results: List<OcrResult>) {
        _latestOcrResults.value = results
        _latestOcrText.value = results.joinToString("\n") { it.text }
        _lastUpdateTimestamp.value = System.currentTimeMillis()
    }

    /**
     * Clear stored results (e.g., when overlay is dismissed).
     * Note: We intentionally keep the text available for Grammar Analyzer
     * even after the overlay closes, so this only clears the raw results.
     */
    fun clearResults() {
        _latestOcrResults.value = emptyList()
    }

    /**
     * Fully clear all data (e.g., on app restart or manual reset).
     */
    fun clearAll() {
        _latestOcrResults.value = emptyList()
        _latestOcrText.value = null
        _lastUpdateTimestamp.value = 0L
    }

    /**
     * Check if there's any OCR text available.
     */
    fun hasText(): Boolean = !_latestOcrText.value.isNullOrBlank()
}

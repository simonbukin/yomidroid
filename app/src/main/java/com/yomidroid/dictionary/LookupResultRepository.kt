package com.yomidroid.dictionary

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream

/**
 * Repository for sharing dictionary lookup results between the accessibility service
 * and in-app UI screens (decoupled mode).
 */
object LookupResultRepository {

    private const val TAG = "LookupResultRepository"
    private const val SCREENSHOT_DIR = "livelookup"
    private const val SCREENSHOT_FILENAME = "latest.jpg"

    private val _latestEntries = MutableStateFlow<List<DictionaryEntry>>(emptyList())
    val latestEntries: StateFlow<List<DictionaryEntry>> = _latestEntries.asStateFlow()

    private val _latestSentence = MutableStateFlow<String?>(null)
    val latestSentence: StateFlow<String?> = _latestSentence.asStateFlow()

    private val _latestScreenshotPath = MutableStateFlow<String?>(null)
    val latestScreenshotPath: StateFlow<String?> = _latestScreenshotPath.asStateFlow()

    // Current OCR substring the displayed entries were looked up against. Used
    // to drive the popup's kanji-correction UI in decoupled mode.
    private val _latestMatchedText = MutableStateFlow<String?>(null)
    val latestMatchedText: StateFlow<String?> = _latestMatchedText.asStateFlow()

    // The matched text from the *first* lookup in this session. Drives the
    // "← original" reset affordance after one or more corrections.
    private val _latestOriginalMatchedText = MutableStateFlow<String?>(null)
    val latestOriginalMatchedText: StateFlow<String?> = _latestOriginalMatchedText.asStateFlow()

    // True while the in-app OCR edit surface should be shown (overlays the
    // normal lookup view with a screenshot crop + editable text field).
    private val _editModeActive = MutableStateFlow(false)
    val editModeActive: StateFlow<Boolean> = _editModeActive.asStateFlow()

    // Path to the screenshot crop (matched-text region with padding) that the
    // edit surface displays above the text field as visual context.
    private val _editScreenshotCropPath = MutableStateFlow<String?>(null)
    val editScreenshotCropPath: StateFlow<String?> = _editScreenshotCropPath.asStateFlow()

    /**
     * Update the live-lookup state for a fresh lookup. Resets the
     * original-matched-text marker to [matchedText] so subsequent
     * corrections can return to this baseline.
     */
    fun updateEntries(
        entries: List<DictionaryEntry>,
        sentence: String?,
        context: Context? = null,
        screenshot: Bitmap? = null,
        matchedText: String? = null,
        originalMatchedText: String? = null
    ) {
        _latestEntries.value = entries
        _latestSentence.value = sentence
        _latestMatchedText.value = matchedText
        _latestOriginalMatchedText.value = originalMatchedText ?: matchedText
        if (context != null) {
            _latestScreenshotPath.value = if (screenshot != null) {
                writeScreenshot(context, screenshot)
            } else {
                null
            }
        }
    }

    /**
     * Update entries from an in-popup correction. Keeps the existing
     * original-matched-text marker so the "← original" affordance still
     * points back to the first lookup of this session.
     */
    fun updateEntriesFromCorrection(
        entries: List<DictionaryEntry>,
        matchedText: String
    ) {
        _latestEntries.value = entries
        _latestMatchedText.value = matchedText
    }

    fun clear() {
        _latestEntries.value = emptyList()
        _latestSentence.value = null
        _latestScreenshotPath.value = null
        _latestMatchedText.value = null
        _latestOriginalMatchedText.value = null
        endEditMode()
    }

    /** Open the edit surface, displaying [croppedScreenshotPath] above the text field. */
    fun startEditMode(croppedScreenshotPath: String?) {
        _editScreenshotCropPath.value = croppedScreenshotPath
        _editModeActive.value = true
    }

    /** Close the edit surface and forget the crop path. */
    fun endEditMode() {
        _editModeActive.value = false
        _editScreenshotCropPath.value = null
    }

    private fun writeScreenshot(context: Context, bitmap: Bitmap): String? {
        return try {
            if (bitmap.isRecycled) return null
            val dir = File(context.filesDir, SCREENSHOT_DIR).apply { mkdirs() }
            val file = File(dir, SCREENSHOT_FILENAME)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            file.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist live-lookup screenshot: ${e.message}")
            null
        }
    }
}

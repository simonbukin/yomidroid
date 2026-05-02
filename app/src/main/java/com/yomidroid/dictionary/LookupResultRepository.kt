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

    /**
     * Update the live-lookup state. If [context] and [screenshot] are provided,
     * the bitmap is persisted to a stable file under filesDir so the Live Lookup
     * UI can attach it at Anki-export time. Pass null to clear the screenshot.
     */
    fun updateEntries(
        entries: List<DictionaryEntry>,
        sentence: String?,
        context: Context? = null,
        screenshot: Bitmap? = null
    ) {
        _latestEntries.value = entries
        _latestSentence.value = sentence
        if (context != null) {
            _latestScreenshotPath.value = if (screenshot != null) {
                writeScreenshot(context, screenshot)
            } else {
                null
            }
        }
    }

    fun clear() {
        _latestEntries.value = emptyList()
        _latestSentence.value = null
        _latestScreenshotPath.value = null
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

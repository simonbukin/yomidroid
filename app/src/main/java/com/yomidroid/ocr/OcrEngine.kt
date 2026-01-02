package com.yomidroid.ocr

import android.graphics.Bitmap

/**
 * Common interface for OCR backends.
 * All engines must produce OcrResult objects with character-level bounding boxes.
 */
interface OcrEngine {
    /**
     * Unique identifier for this engine (used in settings).
     */
    val engineId: String

    /**
     * Human-readable name for UI display.
     */
    val displayName: String

    /**
     * Whether this engine requires network connectivity.
     */
    val requiresNetwork: Boolean

    /**
     * Process a bitmap and return OCR results.
     * @param bitmap The image to process (ARGB_8888 format)
     * @return Result containing list of OcrResult objects with character-level bounding boxes
     */
    suspend fun processImage(bitmap: Bitmap): Result<List<OcrResult>>

    /**
     * Release any resources held by this engine.
     */
    fun close()
}

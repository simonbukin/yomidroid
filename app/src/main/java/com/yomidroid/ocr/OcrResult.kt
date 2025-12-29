package com.yomidroid.ocr

import android.graphics.Rect

/**
 * Represents a single line of OCR-detected text with bounding boxes.
 * @param text The recognized text content
 * @param lineBounds Bounding box for the entire line (in bitmap coordinates)
 * @param charBounds Individual bounding boxes for each character (in bitmap coordinates)
 */
data class OcrResult(
    val text: String,
    val lineBounds: Rect,
    val charBounds: List<Rect>
)

package com.yomidroid.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.benjaminwan.ocrlibrary.OcrEngine as RapidEngine
import com.benjaminwan.ocrlibrary.models.DetPoint
import com.benjaminwan.ocrlibrary.models.OcrResult as RapidResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OCR engine using RapidOCR (PaddleOCR-based ONNX) for on-device Japanese text recognition.
 * Uses detection + classification + recognition pipeline for accurate text extraction.
 */
class RapidOcrEngine(private val context: Context) : OcrEngine {

    override val engineId = "rapidocr"
    override val displayName = "RapidOCR (On-Device)"
    override val requiresNetwork = false

    private var rapidEngine: RapidEngine? = null
    private var isInitialized = false

    companion object {
        private const val TAG = "RapidOcrEngine"
        private const val PADDING = 50  // Must match padding param in detect()
    }

    override suspend fun processImage(bitmap: Bitmap): Result<List<OcrResult>> {
        return withContext(Dispatchers.Default) {
            try {
                if (!isInitialized) {
                    initialize()
                }

                val engine = rapidEngine ?: return@withContext Result.failure(
                    IllegalStateException("RapidOCR not initialized")
                )

                val result = engine.detect(
                    bmp = bitmap,
                    scaleUp = false,
                    maxSideLen = 1024,
                    padding = 50,
                    boxScoreThresh = 0.5f,
                    boxThresh = 0.3f,
                    unClipRatio = 1.6f,
                    doCls = true,
                    mostCls = true
                )

                Log.d(TAG, "RapidOCR detected ${result.detResults.size} text regions in ${result.fullTime}ms")

                Result.success(convertResults(result))
            } catch (e: Exception) {
                Log.e(TAG, "RapidOCR failed: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    private fun initialize() {
        Log.d(TAG, "Initializing RapidOCR engine...")
        rapidEngine = RapidEngine(context)
        isInitialized = true
        Log.d(TAG, "RapidOCR initialized successfully")
    }

    private fun convertResults(rapid: RapidResult): List<OcrResult> {
        if (rapid.detResults.isEmpty()) return emptyList()

        return rapid.detResults.zip(rapid.recResults).mapNotNull { (det, rec) ->
            if (rec.text.isBlank()) return@mapNotNull null

            val bounds = pointsToRect(det.points)
            val charBounds = synthesizeCharBounds(rec.text, bounds)
            OcrResult(rec.text, bounds, charBounds)
        }
    }

    private fun pointsToRect(points: List<DetPoint>): Rect {
        if (points.isEmpty()) return Rect()
        // Subtract padding offset - coordinates are in padded image space
        val xs = points.map { it.x - PADDING }
        val ys = points.map { it.y - PADDING }
        return Rect(
            maxOf(xs.minOrNull() ?: 0, 0),  // Clamp to >= 0
            maxOf(ys.minOrNull() ?: 0, 0),
            xs.maxOrNull() ?: 0,
            ys.maxOrNull() ?: 0
        )
    }

    private fun synthesizeCharBounds(text: String, lineBounds: Rect): List<Rect> {
        val cleanText = text.replace("\\s".toRegex(), "")
        if (cleanText.isEmpty()) return emptyList()

        val charWidth = lineBounds.width().toFloat() / cleanText.length
        return cleanText.indices.map { i ->
            Rect(
                (lineBounds.left + i * charWidth).toInt(),
                lineBounds.top,
                (lineBounds.left + (i + 1) * charWidth).toInt(),
                lineBounds.bottom
            )
        }
    }

    override fun close() {
        rapidEngine?.close()
        rapidEngine = null
        isInitialized = false
        Log.d(TAG, "RapidOCR closed")
    }
}

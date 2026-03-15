package com.yomidroid.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.benjaminwan.ocrlibrary.OcrEngine as RapidEngine
import com.benjaminwan.ocrlibrary.models.DetPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OCR engine combining PaddleOCR detection with manga-ocr recognition.
 * Uses RapidOCR for text region detection, then manga-ocr ONNX model for recognition.
 * Best for stylized manga/comic fonts.
 */
class MangaOcrEngine(private val context: Context) : OcrEngine {

    override val engineId = "manga_ocr"
    override val displayName = "Manga OCR"
    override val requiresNetwork = false

    private var rapidEngine: RapidEngine? = null
    private var mangaOcrInference: MangaOcrInference? = null
    private var isInitialized = false

    companion object {
        private const val TAG = "MangaOcrEngine"
        private const val PADDING = 50
    }

    override suspend fun processImage(bitmap: Bitmap): Result<List<OcrResult>> {
        return withContext(Dispatchers.Default) {
            try {
                if (!isInitialized) {
                    initialize()
                }

                val rapid = rapidEngine ?: return@withContext Result.failure(
                    IllegalStateException("RapidOCR detection not initialized")
                )
                val inference = mangaOcrInference ?: return@withContext Result.failure(
                    IllegalStateException("Manga OCR inference not initialized")
                )

                // Run PaddleOCR detection pipeline
                val detResult = rapid.detect(
                    bmp = bitmap,
                    scaleUp = false,
                    maxSideLen = 1024,
                    padding = PADDING,
                    boxScoreThresh = 0.5f,
                    boxThresh = 0.3f,
                    unClipRatio = 1.6f,
                    doCls = true,
                    mostCls = true
                )

                Log.d(TAG, "PaddleOCR detected ${detResult.detResults.size} regions in ${detResult.fullTime}ms")

                // For each detected region, crop and run manga-ocr recognition
                val results = detResult.detResults.mapNotNull { det ->
                    val bounds = pointsToRect(det.points)
                    if (bounds.width() <= 0 || bounds.height() <= 0) return@mapNotNull null

                    // Clamp bounds to bitmap dimensions
                    val clampedBounds = Rect(
                        bounds.left.coerceIn(0, bitmap.width - 1),
                        bounds.top.coerceIn(0, bitmap.height - 1),
                        bounds.right.coerceIn(1, bitmap.width),
                        bounds.bottom.coerceIn(1, bitmap.height)
                    )

                    if (clampedBounds.width() <= 0 || clampedBounds.height() <= 0) return@mapNotNull null

                    val crop = Bitmap.createBitmap(
                        bitmap,
                        clampedBounds.left,
                        clampedBounds.top,
                        clampedBounds.width(),
                        clampedBounds.height()
                    )

                    try {
                        val text = inference.recognize(crop)
                        if (text.isBlank()) return@mapNotNull null

                        val charBounds = synthesizeCharBounds(text, clampedBounds)
                        OcrResult(text, clampedBounds, charBounds)
                    } finally {
                        if (crop !== bitmap) crop.recycle()
                    }
                }

                Log.d(TAG, "Manga OCR recognized ${results.size} text regions")
                Result.success(results)
            } catch (e: Exception) {
                Log.e(TAG, "Manga OCR failed: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    private fun initialize() {
        val modelManager = MangaOcrModelManager.getInstance(context)
        if (!modelManager.isModelReady()) {
            throw IllegalStateException("Manga OCR models not downloaded. Please download from OCR Settings.")
        }

        Log.d(TAG, "Initializing MangaOcrEngine...")
        rapidEngine = RapidEngine(context)
        mangaOcrInference = MangaOcrInference(
            encoderPath = modelManager.getEncoderPath(),
            decoderPath = modelManager.getDecoderPath(),
            vocabPath = modelManager.getVocabPath()
        )
        isInitialized = true
        Log.d(TAG, "MangaOcrEngine initialized successfully")
    }

    private fun pointsToRect(points: List<DetPoint>): Rect {
        if (points.isEmpty()) return Rect()
        val xs = points.map { it.x - PADDING }
        val ys = points.map { it.y - PADDING }
        return Rect(
            maxOf(xs.minOrNull() ?: 0, 0),
            maxOf(ys.minOrNull() ?: 0, 0),
            xs.maxOrNull() ?: 0,
            ys.maxOrNull() ?: 0
        )
    }

    private fun synthesizeCharBounds(text: String, lineBounds: Rect): List<Rect> =
        OcrUtils.synthesizeCharBounds(text, lineBounds)

    override fun close() {
        mangaOcrInference?.close()
        mangaOcrInference = null
        rapidEngine?.close()
        rapidEngine = null
        isInitialized = false
        Log.d(TAG, "MangaOcrEngine closed")
    }
}

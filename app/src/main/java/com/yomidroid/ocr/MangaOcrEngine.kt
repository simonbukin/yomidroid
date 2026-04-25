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
        // Small margin around each detected line crop, mirroring the 10px
        // background margin Manga109 training crops include.
        private const val LINE_MARGIN = 10
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

                // Manga-OCR was trained on whole text bubbles, but the cursor-
                // hover dictionary UX needs per-line OcrResults with per-char
                // bounds — bubble-level recognition gives no character-position
                // breakdown to recover. Trade some recognition accuracy for
                // per-line precision and run one rec call per detected line,
                // adding a small margin matching Manga109 training crops.
                val results = detResult.detResults.mapNotNull { det ->
                    val bounds = pointsToRect(det.points)
                    if (bounds.width() <= 0 || bounds.height() <= 0) return@mapNotNull null

                    val expanded = Rect(
                        (bounds.left - LINE_MARGIN).coerceAtLeast(0),
                        (bounds.top - LINE_MARGIN).coerceAtLeast(0),
                        (bounds.right + LINE_MARGIN).coerceAtMost(bitmap.width),
                        (bounds.bottom + LINE_MARGIN).coerceAtMost(bitmap.height)
                    )
                    if (expanded.width() <= 0 || expanded.height() <= 0) return@mapNotNull null

                    val crop = Bitmap.createBitmap(
                        bitmap,
                        expanded.left,
                        expanded.top,
                        expanded.width(),
                        expanded.height()
                    )

                    try {
                        val text = inference.recognize(crop)
                        if (text.isBlank()) return@mapNotNull null

                        val charBounds = synthesizeCharBounds(text, expanded)
                        OcrResult(text, expanded, charBounds)
                    } finally {
                        if (crop !== bitmap) crop.recycle()
                    }
                }

                Log.d(TAG, "Manga OCR recognized ${results.size}/${detResult.detResults.size} lines")
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

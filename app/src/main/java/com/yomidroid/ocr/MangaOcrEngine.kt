package com.yomidroid.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.benjaminwan.ocrlibrary.OcrEngine as RapidEngine
import com.benjaminwan.ocrlibrary.models.DetPoint
import com.yomidroid.config.MangaOcrCropScaling
import com.yomidroid.config.MangaOcrDetector
import com.yomidroid.config.OcrConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OCR engine wrapping the manga-ocr ONNX recognizer behind a configurable
 * detection + preprocessing pipeline. Detection can be PaddleOCR (default,
 * trained for documents/scenes) or ML Kit (hybrid mode — better for screen
 * text). Recognition is always manga-ocr, optionally in whole-bubble mode.
 *
 * Pipeline: bitmap → optional preprocess (sigmoid + invert + upscale) →
 * detect → recognize (per-line or per-bubble). All result rects are mapped
 * back to the original input bitmap's coordinate space.
 */
class MangaOcrEngine(
    private val context: Context,
    private val config: OcrConfig
) : OcrEngine {

    override val engineId = "manga_ocr"
    override val displayName = "Manga OCR"
    override val requiresNetwork = false

    private var rapidEngine: RapidEngine? = null
    private var mlKitEngine: MlKitOcrEngine? = null
    private var mangaOcrInference: MangaOcrInference? = null
    private var isInitialized = false

    companion object {
        private const val TAG = "MangaOcrEngine"
        private const val PADDING = 50
        private const val LINE_MARGIN = 10
    }

    override suspend fun processImage(bitmap: Bitmap): Result<List<OcrResult>> {
        return withContext(Dispatchers.Default) {
            try {
                if (!isInitialized) initialize()
                val totalStart = SystemClock.elapsedRealtime()

                val pre = preprocessIfEnabled(bitmap)
                val results = try {
                    runPipeline(pre.bitmap, pre.scale, bitmap)
                } finally {
                    if (pre.bitmap !== bitmap) pre.bitmap.recycle()
                }

                val totalMs = SystemClock.elapsedRealtime() - totalStart
                Log.d(
                    TAG,
                    "total ms=$totalMs lines=${results.size} " +
                        "detector=${config.mangaOcrDetector} " +
                        "mode=${if (config.mangaOcrWholeBubbleMode) "bubble" else "line"} " +
                        "scaling=${config.mangaOcrCropScaling} " +
                        "preprocess=${config.mangaOcrPreprocess}"
                )
                Result.success(results)
            } catch (e: Exception) {
                Log.e(TAG, "Manga OCR failed: ${e.message}", e)
                Result.failure(e)
            }
        }
    }

    private data class Pre(val bitmap: Bitmap, val scale: Float, val inverted: Boolean)

    private fun preprocessIfEnabled(source: Bitmap): Pre {
        if (!config.mangaOcrPreprocess) return Pre(source, 1f, false)
        val preStart = SystemClock.elapsedRealtime()
        val r = OcrPreprocessor.preprocess(source)
        val preMs = SystemClock.elapsedRealtime() - preStart
        Log.d(TAG, "preprocess ms=$preMs scale=${r.scale} inverted=${r.inverted}")
        return Pre(r.bitmap, r.scale, r.inverted)
    }

    private suspend fun runPipeline(
        preBitmap: Bitmap,
        scale: Float,
        originalBitmap: Bitmap
    ): List<OcrResult> {
        val preRects = detect(preBitmap)
        if (preRects.isEmpty()) return emptyList()

        val inference = mangaOcrInference ?: return emptyList()
        inference.bilinearScaling = config.mangaOcrCropScaling == MangaOcrCropScaling.BILINEAR

        return if (config.mangaOcrWholeBubbleMode) {
            recognizeAsBubbles(inference, preBitmap, preRects, scale, originalBitmap)
        } else {
            recognizeAsLines(inference, preBitmap, preRects, scale, originalBitmap)
        }
    }

    private suspend fun detect(preBitmap: Bitmap): List<Rect> {
        val detStart = SystemClock.elapsedRealtime()
        val rects = when (config.mangaOcrDetector) {
            MangaOcrDetector.PADDLE_OCR -> detectWithPaddleOcr(preBitmap)
            MangaOcrDetector.ML_KIT_HYBRID -> detectWithMlKit(preBitmap)
        }
        val detMs = SystemClock.elapsedRealtime() - detStart
        Log.d(TAG, "detect ms=$detMs regions=${rects.size} backend=${config.mangaOcrDetector}")
        return rects
    }

    private fun detectWithPaddleOcr(preBitmap: Bitmap): List<Rect> {
        val rapid = rapidEngine ?: return emptyList()
        val det = rapid.detect(
            bmp = preBitmap,
            scaleUp = false,
            maxSideLen = config.mangaOcrMaxSideLen,
            padding = PADDING,
            boxScoreThresh = 0.5f,
            boxThresh = 0.3f,
            unClipRatio = 1.6f,
            doCls = true,
            mostCls = true
        )
        return det.detResults.mapNotNull { d ->
            val r = pointsToRect(d.points)
            if (r.width() <= 0 || r.height() <= 0) null else r
        }
    }

    private suspend fun detectWithMlKit(preBitmap: Bitmap): List<Rect> {
        val ml = mlKitEngine ?: return emptyList()
        val res = ml.processImage(preBitmap).getOrNull() ?: return emptyList()
        return res.map { it.lineBounds }
    }

    private fun recognizeAsLines(
        inference: MangaOcrInference,
        preBitmap: Bitmap,
        preRects: List<Rect>,
        scale: Float,
        originalBitmap: Bitmap
    ): List<OcrResult> = preRects.mapNotNull { preRect ->
        val expanded = expand(preRect, preBitmap)
        val crop = Bitmap.createBitmap(
            preBitmap, expanded.left, expanded.top, expanded.width(), expanded.height()
        )
        try {
            val recStart = SystemClock.elapsedRealtime()
            val text = inference.recognize(crop)
            val recMs = SystemClock.elapsedRealtime() - recStart
            Log.d(TAG, "recognize ms=$recMs chars=${text.length}")
            if (text.isBlank()) return@mapNotNull null

            val origBounds = scaleRectDown(expanded, scale, originalBitmap)
            OcrResult(text, origBounds, OcrUtils.synthesizeCharBounds(text, origBounds))
        } finally {
            if (crop !== preBitmap) crop.recycle()
        }
    }

    private fun recognizeAsBubbles(
        inference: MangaOcrInference,
        preBitmap: Bitmap,
        preRects: List<Rect>,
        scale: Float,
        originalBitmap: Bitmap
    ): List<OcrResult> {
        val clusters = clusterRects(preRects)
        Log.d(TAG, "clusters=${clusters.size} from rects=${preRects.size}")

        return clusters.flatMap { cluster ->
            val union = unionRect(cluster)
            val expanded = expand(union, preBitmap)
            val crop = Bitmap.createBitmap(
                preBitmap, expanded.left, expanded.top, expanded.width(), expanded.height()
            )
            try {
                val recStart = SystemClock.elapsedRealtime()
                val text = inference.recognize(crop)
                val recMs = SystemClock.elapsedRealtime() - recStart
                Log.d(TAG, "recognize ms=$recMs chars=${text.length} linesInCluster=${cluster.size}")
                if (text.isBlank()) return@flatMap emptyList<OcrResult>()

                splitTextAcrossLines(text, cluster).mapIndexed { i, slice ->
                    val origBounds = scaleRectDown(cluster[i], scale, originalBitmap)
                    OcrResult(slice, origBounds, OcrUtils.synthesizeCharBounds(slice, origBounds))
                }
            } finally {
                if (crop !== preBitmap) crop.recycle()
            }
        }
    }

    private fun expand(r: Rect, bitmap: Bitmap): Rect = Rect(
        (r.left - LINE_MARGIN).coerceAtLeast(0),
        (r.top - LINE_MARGIN).coerceAtLeast(0),
        (r.right + LINE_MARGIN).coerceAtMost(bitmap.width),
        (r.bottom + LINE_MARGIN).coerceAtMost(bitmap.height)
    )

    private fun scaleRectDown(r: Rect, scale: Float, original: Bitmap): Rect {
        if (scale == 1f) return Rect(r)
        return Rect(
            (r.left / scale).toInt().coerceIn(0, original.width),
            (r.top / scale).toInt().coerceIn(0, original.height),
            (r.right / scale).toInt().coerceIn(0, original.width),
            (r.bottom / scale).toInt().coerceIn(0, original.height)
        )
    }

    private fun unionRect(rects: List<Rect>): Rect {
        val out = Rect(rects[0])
        for (i in 1 until rects.size) out.union(rects[i])
        return out
    }

    private fun clusterRects(rects: List<Rect>): List<List<Rect>> {
        val sorted = rects.sortedBy { it.top }
        val groups = mutableListOf<MutableList<Rect>>()
        for (r in sorted) {
            val match = groups.firstOrNull { g -> g.any { canCluster(it, r) } }
            if (match != null) match.add(r) else groups.add(mutableListOf(r))
        }
        return groups.map { g -> g.sortedBy { it.top } }
    }

    private fun canCluster(a: Rect, b: Rect): Boolean {
        val overlap = (minOf(a.right, b.right) - maxOf(a.left, b.left))
        val narrower = minOf(a.width(), b.width())
        if (narrower <= 0 || overlap < narrower * 0.3f) return false
        val gap = if (a.bottom <= b.top) b.top - a.bottom
        else if (b.bottom <= a.top) a.top - b.bottom
        else 0
        return gap <= maxOf(a.height(), b.height()) * 1.5f
    }

    private fun splitTextAcrossLines(text: String, lines: List<Rect>): List<String> {
        if (lines.size <= 1) return listOf(text)
        val widths = lines.map { it.width().toFloat() }
        val total = widths.sum().coerceAtLeast(1f)
        val out = mutableListOf<String>()
        var consumed = 0
        for (i in lines.indices) {
            val end = if (i == lines.size - 1) text.length
            else (text.length * widths.take(i + 1).sum() / total).toInt().coerceIn(consumed, text.length)
            out.add(text.substring(consumed, end))
            consumed = end
        }
        return out
    }

    private fun initialize() {
        val modelManager = MangaOcrModelManager.getInstance(context)
        if (!modelManager.isModelReady()) {
            throw IllegalStateException("Manga OCR models not downloaded. Please download from OCR Settings.")
        }

        Log.d(TAG, "Initializing MangaOcrEngine config=$config")
        rapidEngine = RapidEngine(context)
        mlKitEngine = MlKitOcrEngine()
        mangaOcrInference = MangaOcrInference(
            encoderPath = modelManager.getEncoderPath(),
            decoderPath = modelManager.getDecoderPath(),
            vocabPath = modelManager.getVocabPath(),
            bilinearScaling = config.mangaOcrCropScaling == MangaOcrCropScaling.BILINEAR
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

    override fun close() {
        mangaOcrInference?.close()
        mangaOcrInference = null
        rapidEngine?.close()
        rapidEngine = null
        mlKitEngine?.close()
        mlKitEngine = null
        isInitialized = false
        Log.d(TAG, "MangaOcrEngine closed")
    }
}

package com.yomidroid.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/**
 * Preprocessing for the manga-ocr pipeline. Ported from playtranslate's
 * `OcrPreprocessingRecipe.Default`: upscale → grayscale → sigmoid contrast (k=7)
 * → optional invert. Manga-OCR was trained on black-on-white Manga109 crops, so
 * light-on-dark game dialogue must be inverted before recognition or the model
 * runs badly out of distribution.
 */
object OcrPreprocessor {

    private const val TARGET_MIN_DIM = 1200
    private const val MAX_DIM = 3000
    private const val SIGMOID_K = 7f

    private val sigmoidLut: IntArray = buildSigmoidLut(SIGMOID_K)

    private val invertMatrix = ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    /**
     * The preprocessed bitmap, the uniform scale factor applied to it relative
     * to the source, and whether auto-invert was triggered (logged upstream).
     * The caller owns [bitmap] and must recycle it.
     */
    data class Result(val bitmap: Bitmap, val scale: Float, val inverted: Boolean)

    fun preprocess(source: Bitmap): Result {
        val inverted = sampleIsDarkBackground(source)
        val (scale, outW, outH) = upscaleParams(source)

        val gray = ColorMatrix().apply { setSaturation(0f) }
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(gray)
        }
        val canvas = Canvas(out)
        if (scale != 1f) canvas.scale(scale, scale)
        canvas.drawBitmap(source, 0f, 0f, paint)

        val lut = if (inverted) IntArray(256) { 255 - sigmoidLut[it] } else sigmoidLut
        applyGrayLut(out, lut)
        return Result(out, scale, inverted)
    }

    private fun sampleIsDarkBackground(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val margin = (minOf(w, h) * 0.05f).toInt().coerceAtLeast(1)
        val points = arrayOf(
            margin to margin,
            w - margin to margin,
            margin to h - margin,
            w - margin to h - margin,
            w / 2 to margin,
            w / 2 to h - margin,
            margin to h / 2,
            w - margin to h / 2
        )
        var sum = 0
        for ((x, y) in points) {
            val px = bitmap.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1))
            sum += (Color.red(px) + Color.green(px) + Color.blue(px)) / 3
        }
        return sum / points.size < 100
    }

    private fun upscaleParams(bitmap: Bitmap): Triple<Float, Int, Int> {
        val minDim = minOf(bitmap.width, bitmap.height)
        var scale = if (minDim < TARGET_MIN_DIM)
            (TARGET_MIN_DIM.toFloat() / minDim).coerceAtMost(3f)
        else 1f
        if (bitmap.width * scale > MAX_DIM || bitmap.height * scale > MAX_DIM) {
            scale = minOf(
                MAX_DIM.toFloat() / bitmap.width,
                MAX_DIM.toFloat() / bitmap.height
            )
        }
        return Triple(scale, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
    }

    private fun buildSigmoidLut(k: Float): IntArray {
        val s0 = 1.0 / (1.0 + Math.exp(k * 0.5))
        val s1 = 1.0 / (1.0 + Math.exp(-k * 0.5))
        val range = s1 - s0
        return IntArray(256) { i ->
            val x = i / 255.0
            val s = 1.0 / (1.0 + Math.exp(-k * (x - 0.5)))
            ((s - s0) / range * 255.0).toInt().coerceIn(0, 255)
        }
    }

    private fun applyGrayLut(bitmap: Bitmap, lut: IntArray) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xff
            val v = lut[(p ushr 16) and 0xff]
            pixels[i] = (a shl 24) or (v shl 16) or (v shl 8) or v
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }
}

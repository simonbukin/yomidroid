package com.yomidroid.ocr

import com.yomidroid.config.CropPreset
import com.yomidroid.config.OcrConfig

/**
 * Rectangle in bitmap-pixel space.
 */
data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height

    fun isFullBitmap(bitmapWidth: Int, bitmapHeight: Int): Boolean =
        left == 0 && top == 0 && width == bitmapWidth && height == bitmapHeight
}

object CropCalculator {

    /**
     * Compute the bitmap-space crop for the given screenshot dimensions and
     * config. Ratio presets center the largest rectangle of the target aspect
     * ratio that fits inside the bitmap; CUSTOM uses the user's normalized
     * bounds clamped to bitmap bounds.
     */
    fun computeCrop(bitmapWidth: Int, bitmapHeight: Int, cfg: OcrConfig): CropRect {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) {
            return CropRect(0, 0, bitmapWidth.coerceAtLeast(0), bitmapHeight.coerceAtLeast(0))
        }

        return when (cfg.cropPreset) {
            CropPreset.FULL -> CropRect(0, 0, bitmapWidth, bitmapHeight)

            CropPreset.CUSTOM -> {
                val l = (cfg.customCropLeft.coerceIn(0f, 1f) * bitmapWidth).toInt()
                val t = (cfg.customCropTop.coerceIn(0f, 1f) * bitmapHeight).toInt()
                val r = (cfg.customCropRight.coerceIn(0f, 1f) * bitmapWidth).toInt()
                val b = (cfg.customCropBottom.coerceIn(0f, 1f) * bitmapHeight).toInt()
                val left = minOf(l, r).coerceIn(0, bitmapWidth - 1)
                val top = minOf(t, b).coerceIn(0, bitmapHeight - 1)
                val right = maxOf(l, r).coerceIn(left + 1, bitmapWidth)
                val bottom = maxOf(t, b).coerceIn(top + 1, bitmapHeight)
                CropRect(left, top, right - left, bottom - top)
            }

            else -> centeredRatioCrop(bitmapWidth, bitmapHeight, cfg.cropPreset.ratio ?: return CropRect(0, 0, bitmapWidth, bitmapHeight))
        }
    }

    private fun centeredRatioCrop(bitmapWidth: Int, bitmapHeight: Int, ratio: Float): CropRect {
        val bitmapRatio = bitmapWidth.toFloat() / bitmapHeight
        val targetW: Int
        val targetH: Int
        if (ratio >= bitmapRatio) {
            // Target is wider than the bitmap → fit width, shrink height
            targetW = bitmapWidth
            targetH = (bitmapWidth / ratio).toInt().coerceAtLeast(1)
        } else {
            // Target is taller than the bitmap → fit height, shrink width
            targetH = bitmapHeight
            targetW = (bitmapHeight * ratio).toInt().coerceAtLeast(1)
        }
        val left = ((bitmapWidth - targetW) / 2).coerceAtLeast(0)
        val top = ((bitmapHeight - targetH) / 2).coerceAtLeast(0)
        return CropRect(left, top, targetW, targetH)
    }
}

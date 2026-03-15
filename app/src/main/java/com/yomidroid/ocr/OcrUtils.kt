package com.yomidroid.ocr

import android.graphics.Rect

object OcrUtils {

    /**
     * Synthesize per-character bounding boxes by distributing [lineBounds] width
     * proportionally using Unicode-aware character widths.
     *
     * Fullwidth characters (CJK ideographs, kana, fullwidth forms) get weight 1.0.
     * Halfwidth characters (ASCII, digits, halfwidth katakana) get weight 0.5.
     * Last character snaps to [lineBounds].right to avoid rounding gaps.
     */
    fun synthesizeCharBounds(text: String, lineBounds: Rect): List<Rect> {
        if (text.isEmpty()) return emptyList()

        val weights = text.map { charWidth(it) }
        val totalWeight = weights.sum()
        if (totalWeight <= 0f) return emptyList()

        val totalWidth = lineBounds.width().toFloat()
        var cumulative = 0f

        return text.indices.map { i ->
            val left = (lineBounds.left + cumulative / totalWeight * totalWidth).toInt()
            cumulative += weights[i]
            val right = if (i == text.length - 1) {
                lineBounds.right
            } else {
                (lineBounds.left + cumulative / totalWeight * totalWidth).toInt()
            }
            Rect(left, lineBounds.top, right, lineBounds.bottom)
        }
    }

    private fun charWidth(c: Char): Float {
        val code = c.code
        return when {
            // CJK Unified Ideographs and common Japanese ranges
            code in 0x3000..0x9FFF -> 1.0f
            // CJK Unified Ideographs Extension B+ (surrogate pairs handled as fullwidth)
            code in 0xF900..0xFAFF -> 1.0f
            // Fullwidth Latin, fullwidth punctuation (FF01-FF60)
            code in 0xFF01..0xFF60 -> 1.0f
            // Halfwidth katakana (FF61-FF9F)
            code in 0xFF61..0xFF9F -> 0.5f
            // Whitespace — minimal width to preserve index alignment
            c.isWhitespace() -> 0.1f
            // Basic Latin + common ASCII
            code in 0x0020..0x007E -> 0.5f
            // Default: treat as fullwidth (safer for CJK-heavy text)
            else -> 1.0f
        }
    }
}

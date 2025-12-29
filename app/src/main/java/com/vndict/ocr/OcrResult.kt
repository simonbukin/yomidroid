package com.vndict.ocr

import android.graphics.Rect

data class OcrResult(
    val text: String,
    val lineBounds: Rect,
    val charBounds: List<Rect>
) {
    fun getCharIndexAt(x: Float, y: Float): Int {
        for ((index, bounds) in charBounds.withIndex()) {
            if (bounds.contains(x.toInt(), y.toInt())) {
                return index
            }
        }
        // If no exact match, find closest
        return findClosestCharIndex(x, y)
    }

    private fun findClosestCharIndex(x: Float, y: Float): Int {
        if (charBounds.isEmpty()) return -1

        var closestIndex = 0
        var closestDistance = Float.MAX_VALUE

        for ((index, bounds) in charBounds.withIndex()) {
            val centerX = bounds.centerX().toFloat()
            val centerY = bounds.centerY().toFloat()
            val distance = Math.sqrt(
                ((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)).toDouble()
            ).toFloat()

            if (distance < closestDistance) {
                closestDistance = distance
                closestIndex = index
            }
        }

        return closestIndex
    }
}

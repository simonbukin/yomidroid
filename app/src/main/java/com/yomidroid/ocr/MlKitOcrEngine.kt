package com.yomidroid.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * OCR engine using Google ML Kit for on-device Japanese text recognition.
 */
class MlKitOcrEngine : OcrEngine {

    override val engineId = "mlkit"
    override val displayName = "ML Kit (On-Device)"
    override val requiresNetwork = false

    private val textRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    override suspend fun processImage(bitmap: Bitmap): Result<List<OcrResult>> {
        return suspendCancellableCoroutine { continuation ->
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val results = extractOcrResults(visionText)
                    continuation.resume(Result.success(results))
                }
                .addOnFailureListener { e ->
                    continuation.resume(Result.failure(e))
                }
        }
    }

    private fun extractOcrResults(visionText: Text): List<OcrResult> {
        val results = mutableListOf<OcrResult>()

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val lineText = line.text
                val charBounds = mutableListOf<Rect>()

                for (element in line.elements) {
                    for (symbol in element.symbols) {
                        symbol.boundingBox?.let { charBounds.add(it) }
                    }
                }

                if (charBounds.size == lineText.length) {
                    line.boundingBox?.let { lineBounds ->
                        results.add(OcrResult(lineText, lineBounds, charBounds))
                    }
                } else if (charBounds.isNotEmpty()) {
                    line.boundingBox?.let { lineBounds ->
                        results.add(OcrResult(lineText, lineBounds, charBounds))
                    }
                }
            }
        }

        return results
    }

    override fun close() {
        textRecognizer.close()
    }
}

package com.yomidroid.ocr

import android.content.Context
import com.yomidroid.config.OcrEngineType

/**
 * Factory for creating OCR engine instances.
 */
object OcrEngineFactory {

    fun createEngine(context: Context, type: OcrEngineType): OcrEngine {
        return when (type) {
            OcrEngineType.ML_KIT -> MlKitOcrEngine()
            OcrEngineType.RAPID_OCR -> RapidOcrEngine(context)
        }
    }

    fun getAvailableEngines(): List<OcrEngineType> = OcrEngineType.entries
}

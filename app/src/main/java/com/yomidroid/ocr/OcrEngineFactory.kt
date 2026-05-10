package com.yomidroid.ocr

import android.content.Context
import com.yomidroid.config.OcrConfig
import com.yomidroid.config.OcrEngineType

/**
 * Factory for creating OCR engine instances.
 */
object OcrEngineFactory {

    fun createEngine(context: Context, config: OcrConfig): OcrEngine {
        return when (config.selectedEngine) {
            OcrEngineType.ML_KIT -> MlKitOcrEngine()
            OcrEngineType.MANGA_OCR -> MangaOcrEngine(context, config)
            OcrEngineType.GEMINI_FLASH -> GeminiFlashOcrEngine(
                context,
                useMlKitBounds = config.geminiUseMlKitBounds
            )
        }
    }

    fun getAvailableEngines(): List<OcrEngineType> = OcrEngineType.entries
}

package com.yomidroid.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Available OCR engine types.
 */
enum class OcrEngineType(val displayName: String, val description: String) {
    ML_KIT("ML Kit (On-Device)", "Google's fast OCR"),
    RAPID_OCR("RapidOCR (On-Device)", "PaddleOCR-based, excellent for Japanese"),
    MANGA_OCR("Manga OCR", "manga-ocr model, best for manga (requires ~141MB download)")
}

/**
 * Configuration for OCR settings.
 */
data class OcrConfig(
    val selectedEngine: OcrEngineType = OcrEngineType.ML_KIT,
    val fallbackToMlKit: Boolean = true
)

/**
 * Manages OCR configuration persistence.
 */
class OcrConfigManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "ocr_config"
        private const val KEY_SELECTED_ENGINE = "selected_engine"
        private const val KEY_FALLBACK_TO_MLKIT = "fallback_to_mlkit"
    }

    fun getConfig(): OcrConfig {
        val engineName = prefs.getString(KEY_SELECTED_ENGINE, OcrEngineType.ML_KIT.name)
        val engine = try {
            OcrEngineType.valueOf(engineName ?: OcrEngineType.ML_KIT.name)
        } catch (e: Exception) {
            OcrEngineType.ML_KIT
        }

        return OcrConfig(
            selectedEngine = engine,
            fallbackToMlKit = prefs.getBoolean(KEY_FALLBACK_TO_MLKIT, true)
        )
    }

    fun saveConfig(config: OcrConfig) {
        prefs.edit()
            .putString(KEY_SELECTED_ENGINE, config.selectedEngine.name)
            .putBoolean(KEY_FALLBACK_TO_MLKIT, config.fallbackToMlKit)
            .apply()
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
}

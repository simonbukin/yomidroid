package com.yomidroid.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Available OCR engine types.
 */
enum class OcrEngineType(val displayName: String, val description: String) {
    ML_KIT("ML Kit (On-Device)", "Google's fast OCR"),
    MANGA_OCR("Manga OCR", "manga-ocr model, best for manga (requires ~141MB download)"),
    GEMINI_FLASH("Gemini Flash", "Online vision LLM. Best accuracy on stylized / pixel fonts. Requires API key."),
    GLENS("Google Lens", "Online OCR via Google Lens. Often best on stylized / pixel fonts. No setup required.")
}

/**
 * Detector backend for the manga-ocr engine. Recognition is always manga-ocr;
 * this picks who proposes the line bounding boxes.
 */
enum class MangaOcrDetector(val displayName: String, val description: String) {
    PADDLE_OCR("PaddleOCR", "Default. Tuned for documents/scenes."),
    ML_KIT_HYBRID("ML Kit (hybrid)", "ML Kit detect → manga-ocr recognize. Often better on screen text.")
}

/**
 * How crops are scaled to the model's 224×224 input. NEAREST preserves the
 * pixel grid for retro bitmap fonts (GBA/SNES/etc.); BILINEAR is the default
 * the original model expects (smooth ink).
 */
enum class MangaOcrCropScaling(val displayName: String) {
    BILINEAR("Bilinear (default, ink-style)"),
    NEAREST("Nearest (preserve pixels, retro fonts)")
}

/**
 * Aspect-ratio crop applied to the captured screenshot before OCR / overlay /
 * Anki export. FULL leaves the bitmap untouched; ratio presets compute the
 * largest centered rectangle of the given aspect ratio that fits inside the
 * bitmap; CUSTOM uses [OcrConfig.customCrop*] normalized coordinates.
 */
enum class CropPreset(val displayName: String, val ratio: Float?) {
    FULL("Full", null),
    RATIO_16_9("16:9", 16f / 9f),
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_3_2("3:2", 3f / 2f),
    RATIO_1_1("1:1", 1f),
    CUSTOM("Custom", null)
}

/**
 * Configuration for OCR settings.
 */
data class OcrConfig(
    val selectedEngine: OcrEngineType = OcrEngineType.ML_KIT,
    val fallbackToMlKit: Boolean = true,
    val mangaOcrWholeBubbleMode: Boolean = false,
    val mangaOcrDetector: MangaOcrDetector = MangaOcrDetector.PADDLE_OCR,
    val mangaOcrCropScaling: MangaOcrCropScaling = MangaOcrCropScaling.BILINEAR,
    val mangaOcrMaxSideLen: Int = 2400,
    val mangaOcrPreprocess: Boolean = true,
    /**
     * Gemini Flash only. Run ML Kit in parallel and use its line bounding
     * boxes; replace ML Kit's text with Gemini's. Gives Gemini-quality
     * recognition with ML-Kit-quality bounds for cursor lookup.
     */
    val geminiUseMlKitBounds: Boolean = true,
    val cropPreset: CropPreset = CropPreset.FULL,
    /** Normalized (0..1) custom crop bounds, used only when [cropPreset] == CUSTOM. */
    val customCropLeft: Float = 0f,
    val customCropTop: Float = 0f,
    val customCropRight: Float = 1f,
    val customCropBottom: Float = 1f,
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
        private const val KEY_MANGA_OCR_WHOLE_BUBBLE = "manga_ocr_whole_bubble"
        private const val KEY_MANGA_OCR_DETECTOR = "manga_ocr_detector"
        private const val KEY_MANGA_OCR_CROP_SCALING = "manga_ocr_crop_scaling"
        private const val KEY_MANGA_OCR_MAX_SIDE_LEN = "manga_ocr_max_side_len"
        private const val KEY_MANGA_OCR_PREPROCESS = "manga_ocr_preprocess"
        private const val KEY_GEMINI_USE_MLKIT_BOUNDS = "gemini_use_mlkit_bounds"
        private const val KEY_CROP_PRESET = "crop_preset"
        private const val KEY_CROP_LEFT = "crop_left"
        private const val KEY_CROP_TOP = "crop_top"
        private const val KEY_CROP_RIGHT = "crop_right"
        private const val KEY_CROP_BOTTOM = "crop_bottom"
    }

    fun getConfig(): OcrConfig {
        val engine = enumValueOrDefault(
            prefs.getString(KEY_SELECTED_ENGINE, null),
            OcrEngineType.ML_KIT
        )
        val detector = enumValueOrDefault(
            prefs.getString(KEY_MANGA_OCR_DETECTOR, null),
            MangaOcrDetector.PADDLE_OCR
        )
        val cropScaling = enumValueOrDefault(
            prefs.getString(KEY_MANGA_OCR_CROP_SCALING, null),
            MangaOcrCropScaling.BILINEAR
        )

        val cropPreset = enumValueOrDefault(
            prefs.getString(KEY_CROP_PRESET, null),
            CropPreset.FULL
        )

        return OcrConfig(
            selectedEngine = engine,
            fallbackToMlKit = prefs.getBoolean(KEY_FALLBACK_TO_MLKIT, true),
            mangaOcrWholeBubbleMode = prefs.getBoolean(KEY_MANGA_OCR_WHOLE_BUBBLE, false),
            mangaOcrDetector = detector,
            mangaOcrCropScaling = cropScaling,
            mangaOcrMaxSideLen = prefs.getInt(KEY_MANGA_OCR_MAX_SIDE_LEN, 2400),
            mangaOcrPreprocess = prefs.getBoolean(KEY_MANGA_OCR_PREPROCESS, true),
            geminiUseMlKitBounds = prefs.getBoolean(KEY_GEMINI_USE_MLKIT_BOUNDS, true),
            cropPreset = cropPreset,
            customCropLeft = prefs.getFloat(KEY_CROP_LEFT, 0f),
            customCropTop = prefs.getFloat(KEY_CROP_TOP, 0f),
            customCropRight = prefs.getFloat(KEY_CROP_RIGHT, 1f),
            customCropBottom = prefs.getFloat(KEY_CROP_BOTTOM, 1f)
        )
    }

    fun saveConfig(config: OcrConfig) {
        prefs.edit()
            .putString(KEY_SELECTED_ENGINE, config.selectedEngine.name)
            .putBoolean(KEY_FALLBACK_TO_MLKIT, config.fallbackToMlKit)
            .putBoolean(KEY_MANGA_OCR_WHOLE_BUBBLE, config.mangaOcrWholeBubbleMode)
            .putString(KEY_MANGA_OCR_DETECTOR, config.mangaOcrDetector.name)
            .putString(KEY_MANGA_OCR_CROP_SCALING, config.mangaOcrCropScaling.name)
            .putInt(KEY_MANGA_OCR_MAX_SIDE_LEN, config.mangaOcrMaxSideLen)
            .putBoolean(KEY_MANGA_OCR_PREPROCESS, config.mangaOcrPreprocess)
            .putBoolean(KEY_GEMINI_USE_MLKIT_BOUNDS, config.geminiUseMlKitBounds)
            .putString(KEY_CROP_PRESET, config.cropPreset.name)
            .putFloat(KEY_CROP_LEFT, config.customCropLeft)
            .putFloat(KEY_CROP_TOP, config.customCropTop)
            .putFloat(KEY_CROP_RIGHT, config.customCropRight)
            .putFloat(KEY_CROP_BOTTOM, config.customCropBottom)
            .apply()
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }

    private inline fun <reified E : Enum<E>> enumValueOrDefault(name: String?, default: E): E =
        try {
            if (name == null) default else enumValueOf<E>(name)
        } catch (_: Exception) {
            default
        }
}

package com.vndict.config

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

/**
 * Configuration for app colors.
 */
data class ColorConfig(
    val accentColor: Int = DEFAULT_ACCENT,           // App theme primary (buttons, links)
    val highlightColor: Int = DEFAULT_HIGHLIGHT,     // OCR text highlight overlay
    val fabColor: Int = DEFAULT_FAB,                 // FAB main circle
    val cursorDotColor: Int = DEFAULT_CURSOR         // Selection cursor dot
) {
    companion object {
        val DEFAULT_ACCENT = 0xFF2196F3.toInt()                  // Blue
        val DEFAULT_HIGHLIGHT = Color.argb(60, 255, 200, 0)      // Semi-transparent gold
        val DEFAULT_FAB = Color.WHITE                            // White
        val DEFAULT_CURSOR = 0xFFFF5722.toInt()                  // Orange
    }
}

/**
 * Manages color configuration persistence.
 */
class ColorConfigManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "color_config"
        private const val KEY_ACCENT_COLOR = "accent_color"
        private const val KEY_HIGHLIGHT_COLOR = "highlight_color"
        private const val KEY_FAB_COLOR = "fab_color"
        private const val KEY_CURSOR_DOT_COLOR = "cursor_dot_color"
    }

    fun getConfig(): ColorConfig {
        return ColorConfig(
            accentColor = prefs.getInt(KEY_ACCENT_COLOR, ColorConfig.DEFAULT_ACCENT),
            highlightColor = prefs.getInt(KEY_HIGHLIGHT_COLOR, ColorConfig.DEFAULT_HIGHLIGHT),
            fabColor = prefs.getInt(KEY_FAB_COLOR, ColorConfig.DEFAULT_FAB),
            cursorDotColor = prefs.getInt(KEY_CURSOR_DOT_COLOR, ColorConfig.DEFAULT_CURSOR)
        )
    }

    fun saveConfig(config: ColorConfig) {
        prefs.edit()
            .putInt(KEY_ACCENT_COLOR, config.accentColor)
            .putInt(KEY_HIGHLIGHT_COLOR, config.highlightColor)
            .putInt(KEY_FAB_COLOR, config.fabColor)
            .putInt(KEY_CURSOR_DOT_COLOR, config.cursorDotColor)
            .apply()
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
}

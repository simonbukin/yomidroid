package com.yomidroid.config

import android.content.Context
import android.content.SharedPreferences

enum class HistoryRetentionMode { UNLIMITED, BY_COUNT, BY_AGE }

data class HistoryConfig(
    val mode: HistoryRetentionMode = HistoryRetentionMode.UNLIMITED,
    val maxEntries: Int = 1000,
    val maxAgeDays: Int = 30
)

class HistoryConfigManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "history_config"
        private const val KEY_MODE = "mode"
        private const val KEY_MAX_ENTRIES = "max_entries"
        private const val KEY_MAX_AGE_DAYS = "max_age_days"
    }

    fun getConfig(): HistoryConfig {
        val mode = try {
            prefs.getString(KEY_MODE, null)?.let { HistoryRetentionMode.valueOf(it) }
                ?: HistoryRetentionMode.UNLIMITED
        } catch (_: Exception) {
            HistoryRetentionMode.UNLIMITED
        }
        return HistoryConfig(
            mode = mode,
            maxEntries = prefs.getInt(KEY_MAX_ENTRIES, 1000).coerceAtLeast(1),
            maxAgeDays = prefs.getInt(KEY_MAX_AGE_DAYS, 30).coerceAtLeast(1)
        )
    }

    fun saveConfig(config: HistoryConfig) {
        prefs.edit()
            .putString(KEY_MODE, config.mode.name)
            .putInt(KEY_MAX_ENTRIES, config.maxEntries.coerceAtLeast(1))
            .putInt(KEY_MAX_AGE_DAYS, config.maxAgeDays.coerceAtLeast(1))
            .apply()
    }
}

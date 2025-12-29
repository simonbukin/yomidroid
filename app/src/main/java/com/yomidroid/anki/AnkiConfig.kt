package com.yomidroid.anki

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Fields that Yomidroid can export to Anki cards.
 */
enum class YomidroidField(val displayName: String) {
    EXPRESSION("Expression"),
    READING("Reading"),
    DEFINITION("Definition"),
    SENTENCE("Sentence"),
    SCREENSHOT("Screenshot"),
    PARTS_OF_SPEECH("Parts of Speech"),
    DEINFLECTION("Deinflection Path")
}

/**
 * Configuration for AnkiDroid export.
 */
data class AnkiConfig(
    val deckId: Long = -1L,
    val deckName: String = "",
    val modelId: Long = -1L,
    val modelName: String = "",
    val fieldMappings: Map<YomidroidField, String> = emptyMap(),
    val duplicateCheckField: YomidroidField = YomidroidField.EXPRESSION  // Which field to use for duplicate checking
) {
    fun isConfigured(): Boolean {
        return deckId > 0 && modelId > 0 && fieldMappings.isNotEmpty()
    }
}

/**
 * Manages AnkiDroid configuration persistence.
 */
class AnkiConfigManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "anki_config"
        private const val KEY_DECK_ID = "deck_id"
        private const val KEY_DECK_NAME = "deck_name"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_MODEL_NAME = "model_name"
        private const val KEY_FIELD_MAPPINGS = "field_mappings"
        private const val KEY_DUPLICATE_CHECK_FIELD = "duplicate_check_field"
    }

    fun getConfig(): AnkiConfig {
        val mappingsJson = prefs.getString(KEY_FIELD_MAPPINGS, null)
        val mappings: Map<YomidroidField, String> = if (mappingsJson != null) {
            try {
                val type = object : TypeToken<Map<String, String>>() {}.type
                val rawMap: Map<String, String> = gson.fromJson(mappingsJson, type)
                rawMap.mapKeys { YomidroidField.valueOf(it.key) }
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }

        val duplicateCheckField = try {
            val fieldName = prefs.getString(KEY_DUPLICATE_CHECK_FIELD, null)
            if (fieldName != null) YomidroidField.valueOf(fieldName) else YomidroidField.EXPRESSION
        } catch (e: Exception) {
            YomidroidField.EXPRESSION
        }

        return AnkiConfig(
            deckId = prefs.getLong(KEY_DECK_ID, -1L),
            deckName = prefs.getString(KEY_DECK_NAME, "") ?: "",
            modelId = prefs.getLong(KEY_MODEL_ID, -1L),
            modelName = prefs.getString(KEY_MODEL_NAME, "") ?: "",
            fieldMappings = mappings,
            duplicateCheckField = duplicateCheckField
        )
    }

    fun saveConfig(config: AnkiConfig) {
        val mappingsJson = gson.toJson(config.fieldMappings.mapKeys { it.key.name })

        prefs.edit()
            .putLong(KEY_DECK_ID, config.deckId)
            .putString(KEY_DECK_NAME, config.deckName)
            .putLong(KEY_MODEL_ID, config.modelId)
            .putString(KEY_MODEL_NAME, config.modelName)
            .putString(KEY_FIELD_MAPPINGS, mappingsJson)
            .putString(KEY_DUPLICATE_CHECK_FIELD, config.duplicateCheckField.name)
            .apply()
    }

    fun clearConfig() {
        prefs.edit().clear().apply()
    }
}

/**
 * Data for export to AnkiDroid.
 */
data class AnkiExportData(
    val expression: String,
    val reading: String,
    val definition: String,
    val sentence: String,
    val screenshotPath: String?,
    val screenshotHtml: String? = null,  // Pre-formatted HTML from AnkiDroid API
    val partsOfSpeech: String,
    val deinflectionPath: String
) {
    fun getFieldValue(field: YomidroidField): String {
        return when (field) {
            YomidroidField.EXPRESSION -> expression
            YomidroidField.READING -> reading
            YomidroidField.DEFINITION -> definition
            YomidroidField.SENTENCE -> sentence
            YomidroidField.SCREENSHOT -> screenshotHtml ?: screenshotPath?.let { "<img src=\"$it\">" } ?: ""
            YomidroidField.PARTS_OF_SPEECH -> partsOfSpeech
            YomidroidField.DEINFLECTION -> deinflectionPath
        }
    }
}

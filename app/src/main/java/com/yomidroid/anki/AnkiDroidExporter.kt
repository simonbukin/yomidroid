package com.yomidroid.anki

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.ichi2.anki.api.AddContentApi
import com.yomidroid.dictionary.DictionaryEntry
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Result of an export operation.
 */
sealed class ExportResult {
    object Success : ExportResult()
    object AlreadyExists : ExportResult()
    data class Error(val message: String) : ExportResult()
    object AnkiNotInstalled : ExportResult()
    object PermissionDenied : ExportResult()
    object ApiNotEnabled : ExportResult()  // AnkiDroid API not enabled in AnkiDroid settings
    object NotConfigured : ExportResult()
}

/**
 * Handles exporting flashcards to AnkiDroid.
 */
class AnkiDroidExporter(private val context: Context) {

    private val configManager = AnkiConfigManager(context)

    // Track exported entries in this session to prevent duplicates
    private val exportedEntries = mutableSetOf<String>()

    companion object {
        private const val TAG = "AnkiDroidExporter"
        private const val ANKIDROID_PACKAGE = "com.ichi2.anki"
        private const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=com.ichi2.anki"
        private const val ANKIDROID_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        const val PERMISSION_REQUEST_CODE = 1001
    }

    /**
     * Generate a unique key for an entry to track duplicates.
     */
    private fun getEntryKey(entry: DictionaryEntry, sentence: String): String {
        return "${entry.expression}|${entry.reading}|$sentence"
    }

    /**
     * Check if an entry was already exported in this session.
     */
    fun isAlreadyExported(entry: DictionaryEntry, sentence: String): Boolean {
        return exportedEntries.contains(getEntryKey(entry, sentence))
    }

    /**
     * Clear the exported entries cache (e.g., when overlay is dismissed).
     */
    fun clearExportedCache() {
        exportedEntries.clear()
    }

    /**
     * Check if AnkiDroid is installed on the device.
     */
    fun isAnkiDroidInstalled(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    ANKIDROID_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(ANKIDROID_PACKAGE, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Check if we have permission to access AnkiDroid API.
     * This checks the runtime permission, API availability, and actually tests the connection.
     */
    fun hasPermission(): Boolean {
        // First check if we have the runtime permission
        val hasRuntimePermission = ContextCompat.checkSelfPermission(
            context,
            ANKIDROID_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED

        // Also check if the API reports it's available
        val apiAvailable = AddContentApi.getAnkiDroidPackageName(context) != null

        Log.d(TAG, "Permission check - runtime: $hasRuntimePermission, api: $apiAvailable")

        // If basic checks pass, try an actual API call to verify
        if (hasRuntimePermission || apiAvailable) {
            return testApiAccess()
        }

        return false
    }

    /**
     * Actually test if we can access the AnkiDroid API.
     * This catches the case where Android permission is granted but AnkiDroid
     * hasn't enabled API access for this app in its settings.
     */
    private fun testApiAccess(): Boolean {
        return try {
            val api = AddContentApi(context)
            // Try to get deck list - this will throw SecurityException if not permitted
            api.deckList
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "API access test failed - AnkiDroid API not enabled for this app: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "API access test failed: ${e.message}")
            false
        }
    }

    /**
     * Request AnkiDroid API permission from the user.
     * Should be called from an Activity.
     */
    fun requestPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(ANKIDROID_PERMISSION),
            PERMISSION_REQUEST_CODE
        )
    }

    /**
     * Get an intent to open AnkiDroid's settings.
     * User can manually enable API access there.
     */
    fun getAnkiDroidSettingsIntent(): Intent {
        return Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$ANKIDROID_PACKAGE")
        }
    }

    /**
     * Get the Play Store intent for installing AnkiDroid.
     */
    fun getPlayStoreIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_URL))
    }

    /**
     * Get available decks from AnkiDroid.
     * Returns map of deck ID to deck name.
     */
    fun getDecks(): Map<Long, String> {
        if (!isAnkiDroidInstalled()) {
            Log.d(TAG, "getDecks: AnkiDroid not installed")
            return emptyMap()
        }

        if (!hasPermission()) {
            Log.d(TAG, "getDecks: No permission")
            return emptyMap()
        }

        return try {
            val api = AddContentApi(context)
            val decks = api.deckList
            Log.d(TAG, "getDecks: Got ${decks?.size ?: 0} decks")
            decks ?: emptyMap()
        } catch (e: SecurityException) {
            Log.e(TAG, "getDecks: Security exception - ${e.message}")
            emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get decks: ${e.message}", e)
            emptyMap()
        }
    }

    /**
     * Get available note types (models) from AnkiDroid.
     * Returns map of model ID to model name.
     */
    fun getModels(): Map<Long, String> {
        if (!isAnkiDroidInstalled()) {
            Log.d(TAG, "getModels: AnkiDroid not installed")
            return emptyMap()
        }

        if (!hasPermission()) {
            Log.d(TAG, "getModels: No permission")
            return emptyMap()
        }

        return try {
            val api = AddContentApi(context)
            val models = api.modelList
            Log.d(TAG, "getModels: Got ${models?.size ?: 0} models")
            models ?: emptyMap()
        } catch (e: SecurityException) {
            Log.e(TAG, "getModels: Security exception - ${e.message}")
            emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get models: ${e.message}", e)
            emptyMap()
        }
    }

    /**
     * Get field names for a specific model.
     */
    fun getModelFields(modelId: Long): List<String> {
        if (!isAnkiDroidInstalled()) return emptyList()

        return try {
            val api = AddContentApi(context)
            api.getFieldList(modelId)?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get model fields: ${e.message}")
            emptyList()
        }
    }

    /**
     * Export a card to AnkiDroid.
     */
    fun exportCard(
        entry: DictionaryEntry,
        sentence: String,
        screenshot: Bitmap?
    ): ExportResult {
        // Check if AnkiDroid is installed
        if (!isAnkiDroidInstalled()) {
            return ExportResult.AnkiNotInstalled
        }

        // Check configuration
        val config = configManager.getConfig()
        if (!config.isConfigured()) {
            return ExportResult.NotConfigured
        }

        // Check if already exported in this session (prevents spam clicking)
        val entryKey = getEntryKey(entry, sentence)
        if (exportedEntries.contains(entryKey)) {
            return ExportResult.AlreadyExists
        }

        return try {
            val api = AddContentApi(context)

            // Get field names for the model
            val fieldNames = api.getFieldList(config.modelId) ?: return ExportResult.Error("Could not get model fields")

            // Check for duplicates based on configured duplicate check field
            val duplicateCheckField = config.duplicateCheckField
            val duplicateCheckAnkiField = config.fieldMappings[duplicateCheckField]

            if (duplicateCheckAnkiField != null) {
                val fieldIndex = fieldNames.indexOf(duplicateCheckAnkiField)
                // findDuplicateNotes only works on the first field, so only check if mapped there
                if (fieldIndex == 0) {
                    val checkValue = when (duplicateCheckField) {
                        YomidroidField.EXPRESSION -> entry.expression
                        YomidroidField.READING -> entry.reading
                        YomidroidField.DEFINITION -> entry.glossary.firstOrNull() ?: ""
                        YomidroidField.SENTENCE -> sentence
                        else -> entry.expression
                    }
                    val duplicates = api.findDuplicateNotes(config.modelId, checkValue)
                    if (duplicates != null && duplicates.isNotEmpty()) {
                        Log.d(TAG, "Found ${duplicates.size} duplicate notes for $checkValue")
                        exportedEntries.add(entryKey)
                        return ExportResult.AlreadyExists
                    }
                }
            }

            // Save screenshot and add to AnkiDroid media
            var screenshotHtml: String? = null
            if (screenshot != null) {
                try {
                    val stableFilename = "yomidroid_${entry.expression.hashCode()}_${System.currentTimeMillis()}.jpg"
                    val savedFilename = saveScreenshotWithName(screenshot, stableFilename)

                    if (savedFilename != null) {
                        val screenshotFile = File(context.cacheDir, savedFilename)
                        if (screenshotFile.exists()) {
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                screenshotFile
                            )
                            context.grantUriPermission(
                                ANKIDROID_PACKAGE,
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                            // addMediaFromUri returns the properly formatted HTML img tag
                            screenshotHtml = api.addMediaFromUri(uri, savedFilename, "image")
                            Log.d(TAG, "Added media file, result HTML: $screenshotHtml")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to add media: ${e.message}", e)
                }
            }

            // Build export data with the HTML from addMediaFromUri
            val exportData = AnkiExportData(
                expression = entry.expression,
                reading = entry.reading,
                definition = entry.glossary.joinToString("; "),
                sentence = sentence,
                screenshotPath = null,  // Not used anymore
                screenshotHtml = screenshotHtml,  // Use the HTML from API
                partsOfSpeech = entry.partsOfSpeech.joinToString(", "),
                deinflectionPath = entry.deinflectionPath
            )

            // Build field values array based on mappings
            val fieldValues = Array(fieldNames.size) { "" }
            for ((vnField, ankiFieldName) in config.fieldMappings) {
                val fieldIndex = fieldNames.indexOf(ankiFieldName)
                if (fieldIndex >= 0) {
                    fieldValues[fieldIndex] = exportData.getFieldValue(vnField)
                }
            }

            // Add the note with Yomidroid tag
            val tags = setOf("Yomidroid")
            val noteId = api.addNote(config.modelId, config.deckId, fieldValues, tags)

            if (noteId != null && noteId > 0) {
                Log.d(TAG, "Successfully added note with ID: $noteId")
                exportedEntries.add(entryKey)
                ExportResult.Success
            } else {
                // addNote returns null for duplicates
                exportedEntries.add(entryKey)
                ExportResult.AlreadyExists
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "API not enabled in AnkiDroid: ${e.message}")
            ExportResult.ApiNotEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}", e)
            ExportResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Save screenshot to cache directory and return filename.
     */
    private fun saveScreenshot(bitmap: Bitmap): String? {
        return saveScreenshotWithName(bitmap, "yomidroid_${UUID.randomUUID()}.jpg")
    }

    /**
     * Save screenshot to cache directory with a specific filename.
     */
    private fun saveScreenshotWithName(bitmap: Bitmap, filename: String): String? {
        return try {
            val file = File(context.cacheDir, filename)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            Log.d(TAG, "Saved screenshot: ${file.absolutePath}, size: ${file.length()} bytes")
            filename
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot: ${e.message}")
            null
        }
    }

    /**
     * Clean up old screenshot files from cache.
     */
    fun cleanupOldScreenshots() {
        try {
            val cacheDir = context.cacheDir
            val cutoffTime = System.currentTimeMillis() - 24 * 60 * 60 * 1000 // 24 hours ago
            cacheDir.listFiles()?.filter {
                it.name.startsWith("yomidroid_") && it.lastModified() < cutoffTime
            }?.forEach {
                it.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup screenshots: ${e.message}")
        }
    }
}

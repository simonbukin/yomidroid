package com.yomidroid.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.yomidroid.config.DictSourceType
import com.yomidroid.config.DictionaryConfigManager
import com.yomidroid.config.InstalledDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ImportResult(
    val success: Boolean,
    val dictTitle: String? = null,
    val entryCount: Int = 0,
    val error: String? = null
)

class DictionaryImporter(private val context: Context) {

    companion object {
        private const val TAG = "DictionaryImporter"
    }

    private val dictDir: File
        get() = File(context.filesDir, "dictionaries").also { it.mkdirs() }

    private val tempDir: File
        get() = File(context.cacheDir, "dict_imports").also { it.mkdirs() }

    suspend fun importFromUri(
        uri: Uri,
        onProgress: (phase: String, progress: Float) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            // Copy URI to temp file (SAF URIs can't be used with ZipFile)
            onProgress("Copying file...", 0f)
            tempFile = File(tempDir, "import_${System.currentTimeMillis()}.zip")

            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext ImportResult(false, error = "Could not open file")

            Log.d(TAG, "Copied to temp: ${tempFile.length() / 1024}KB")

            importZipFile(tempFile, onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            ImportResult(false, error = e.message ?: "Unknown error")
        } finally {
            tempFile?.let { cleanup(it) }
        }
    }

    /**
     * Import from a local file (e.g. downloaded ZIP). The file is NOT deleted after import.
     */
    suspend fun importFromFile(
        file: File,
        onProgress: (phase: String, progress: Float) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            importZipFile(file, onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed: ${e.message}", e)
            ImportResult(false, error = e.message ?: "Unknown error")
        }
    }

    private suspend fun importZipFile(
        zipFile: File,
        onProgress: (phase: String, progress: Float) -> Unit
    ): ImportResult {
        // Read index.json and detect type
        onProgress("Reading dictionary info...", 0.1f)
        val converter = YomitanConverter()
        val info = converter.readDictInfo(zipFile)
            ?: return ImportResult(false, error = "Invalid Yomitan dictionary (no index.json)")

        Log.d(TAG, "Dict info: title=${info.title}, type=${info.type}, revision=${info.revision}")

        // Check for duplicate
        val configManager = DictionaryConfigManager(context)
        val existing = configManager.getInstalledDictionaries()
        if (existing.any { it.title == info.title }) {
            return ImportResult(false, dictTitle = info.title, error = "\"${info.title}\" is already installed")
        }

        // Convert
        val dictId = sanitizeId(info.title)
        val dbFile = File(dictDir, "$dictId.db")

        onProgress("Converting...", 0.2f)
        val result = when (info.type) {
            DictSourceType.FREQUENCY -> {
                converter.convertFrequency(zipFile, dbFile, info) { progress ->
                    onProgress("Converting...", 0.2f + progress * 0.7f)
                }
            }
            DictSourceType.PITCH -> {
                converter.convertPitchAccents(zipFile, dbFile, info) { progress ->
                    onProgress("Converting...", 0.2f + progress * 0.7f)
                }
            }
            DictSourceType.KANJI -> {
                converter.convertKanji(zipFile, dbFile, info) { progress ->
                    onProgress("Converting...", 0.2f + progress * 0.7f)
                }
            }
            else -> {
                converter.convertDictionary(zipFile, dbFile, info) { progress ->
                    onProgress("Converting...", 0.2f + progress * 0.7f)
                }
            }
        }

        if (!result.success) {
            cleanup(dbFile)
            return ImportResult(false, dictTitle = info.title, error = result.error ?: "Conversion failed")
        }

        // Update config
        onProgress("Finishing...", 0.95f)
        val maxPriority = existing.maxOfOrNull { it.priority } ?: -1

        configManager.addDictionary(
            InstalledDictionary(
                id = dictId,
                title = info.title,
                revision = info.revision,
                type = info.type,
                dbFileName = "$dictId.db",
                priority = maxPriority + 1,
                enabled = true,
                entryCount = result.entryCount,
                installedAt = System.currentTimeMillis(),
                frequencyMode = info.frequencyMode
            )
        )

        // Extract and store dictionary-shipped CSS (styles.css from Yomitan ZIP)
        val dictCss = converter.extractDictionaryCss(zipFile)
        if (dictCss != null) {
            configManager.setDictionaryCss(dictId, dictCss)
            Log.d(TAG, "Stored ${dictCss.length} bytes of dictionary CSS for ${info.title}")
        }

        // Extract images from the ZIP (if any)
        val imageDir = File(dictDir, "$dictId/images")
        val imageCount = converter.extractImages(zipFile, imageDir)
        if (imageCount > 0) {
            Log.d(TAG, "Extracted $imageCount images for ${info.title}")
        }

        // Read and store tag banks (if any)
        val tagBanks = converter.readTagBanks(zipFile)
        if (tagBanks.isNotEmpty() && dbFile.exists()) {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            converter.storeTagBanks(db, tagBanks)
            db.close()
            Log.d(TAG, "Stored ${tagBanks.size} tag definitions for ${info.title}")
        }

        // Reload DictionaryDb
        DictionaryDb.getInstance(context).reloadFromConfig(configManager)

        onProgress("Done", 1f)
        Log.d(TAG, "Imported ${info.title}: ${result.entryCount} entries")
        return ImportResult(true, dictTitle = info.title, entryCount = result.entryCount)
    }

    /**
     * Backfill rich content into an existing dictionary from its original Yomitan ZIP.
     * The user picks the same ZIP they originally imported; we match entries and add glossary_rich.
     */
    suspend fun backfillRichContent(
        dictId: String,
        zipUri: Uri,
        onProgress: (phase: String, progress: Float) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            val configManager = DictionaryConfigManager(context)
            val dict = configManager.getInstalledDictionaries().find { it.id == dictId }
                ?: return@withContext ImportResult(false, error = "Dictionary not found")

            // Copy URI to temp
            onProgress("Copying file...", 0f)
            tempFile = File(tempDir, "backfill_${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext ImportResult(false, error = "Could not open file")

            // Verify it's a valid Yomitan ZIP
            val converter = YomitanConverter()
            val info = converter.readDictInfo(tempFile)
                ?: return@withContext ImportResult(false, error = "Invalid Yomitan dictionary")

            onProgress("Backfilling rich content...", 0.1f)

            val dbFile = File(dictDir, dict.dbFileName)
            if (!dbFile.exists()) {
                return@withContext ImportResult(false, error = "Dictionary DB file not found")
            }

            val result = converter.backfillRichContent(tempFile, dbFile) { progress ->
                onProgress("Backfilling rich content...", 0.1f + progress * 0.85f)
            }

            if (!result.success) {
                return@withContext ImportResult(false, dictTitle = dict.title, error = result.error)
            }

            // Reload
            DictionaryDb.getInstance(context).reloadFromConfig(configManager)

            onProgress("Done", 1f)
            ImportResult(true, dictTitle = dict.title, entryCount = result.entryCount)
        } catch (e: Exception) {
            Log.e(TAG, "Backfill failed: ${e.message}", e)
            ImportResult(false, error = e.message)
        } finally {
            tempFile?.let { cleanup(it) }
        }
    }

    suspend fun deleteDictionary(dictId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val configManager = DictionaryConfigManager(context)
            val installed = configManager.getInstalledDictionaries()
            val dict = installed.find { it.id == dictId } ?: return@withContext false

            // Unregister from DictionaryDb
            DictionaryDb.getInstance(context).unregisterDictionary(dictId)

            // Delete the .db file
            val dbFile = File(dictDir, dict.dbFileName)
            if (dbFile.exists()) dbFile.delete()

            // Update config
            configManager.removeDictionary(dictId)

            Log.d(TAG, "Deleted dictionary: $dictId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed for $dictId: ${e.message}", e)
            false
        }
    }

    private fun sanitizeId(title: String): String {
        val sanitized = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(40)
        return if (sanitized.isEmpty()) UUID.randomUUID().toString().take(8) else sanitized
    }

    private fun cleanup(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup failed: ${e.message}")
        }
    }
}

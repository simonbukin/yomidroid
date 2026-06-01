package com.yomidroid.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.yomidroid.config.DictSourceType
import com.yomidroid.config.DictionaryConfigManager
import com.yomidroid.config.InstalledDictionary
import com.yomidroid.dictionary.HoshiDicts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

    /**
     * Use Hoshidicts' low-RAM import on memory-constrained devices to avoid OOM
     * when importing large dictionaries (it trades a little import speed for a
     * much smaller peak footprint).
     */
    private val lowRamImport: Boolean by lazy {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        am?.isLowRamDevice == true || (am?.memoryClass ?: 256) < 192
    }

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

        // Each dictionary gets its own folder; the Hoshidicts importer writes
        // the dictionary into outputDir/<title>.
        val dictId = uniqueId(info.title, existing.map { it.id }.toSet())
        val outputDir = File(dictDir, dictId)
        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.mkdirs()

        onProgress("Importing...", 0.2f)
        val result = HoshiDicts.import(zipFile.absolutePath, outputDir.absolutePath, lowRam = lowRamImport)
        if (!result.success) {
            outputDir.deleteRecursively()
            return ImportResult(
                false,
                dictTitle = info.title,
                error = result.errors.firstOrNull() ?: "Import failed"
            )
        }

        // The importer names the folder after the dictionary's own title.
        val folder = File(outputDir, result.title.ifEmpty { info.title })
        if (!File(folder, ".hoshidicts_1").exists()) {
            outputDir.deleteRecursively()
            return ImportResult(false, dictTitle = info.title, error = "Imported dictionary folder not found")
        }
        val relativeFolder = "$dictId/${folder.name}"
        val entryCount = result.termCount + result.kanjiCount + result.metaCount

        // Update config (dbFileName now stores the dictionary folder, relative to dictionaries/)
        onProgress("Finishing...", 0.9f)
        val maxPriority = existing.maxOfOrNull { it.priority } ?: -1
        configManager.addDictionary(
            InstalledDictionary(
                id = dictId,
                title = info.title,
                revision = info.revision,
                type = info.type,
                dbFileName = relativeFolder,
                priority = maxPriority + 1,
                enabled = true,
                entryCount = entryCount,
                installedAt = System.currentTimeMillis(),
                frequencyMode = info.frequencyMode
            )
        )

        // Dictionary-shipped CSS (styles.css from Yomitan ZIP) for popup styling.
        converter.extractDictionaryCss(zipFile)?.let { css ->
            configManager.setDictionaryCss(dictId, css)
            Log.d(TAG, "Stored ${css.length} bytes of dictionary CSS for ${info.title}")
        }

        // Build the kanji → example-words index (term dictionaries only) so the
        // Kanji Detail screen can show words containing a kanji — Hoshidicts has
        // no substring scan of its own.
        if (info.type == DictSourceType.DICTIONARY || info.type == DictSourceType.NAMES) {
            onProgress("Indexing kanji...", 0.93f)
            KanjiWordIndex.build(zipFile, File(outputDir, KanjiWordIndex.FILE_NAME))
        }

        // Extract images to disk for the popup's file:// image base path. Hoshidicts
        // stores media in its own format, but the WebView renderer resolves image
        // src paths from the extracted files.
        val imageCount = converter.extractImages(zipFile, File(outputDir, "images"))
        if (imageCount > 0) Log.d(TAG, "Extracted $imageCount images for ${info.title}")

        // Hoshidicts' query API doesn't expose tag-bank descriptions, so persist
        // them next to the dictionary for the result mapper (tag chip tooltips).
        val tagBanks = converter.readTagBanks(zipFile)
        if (tagBanks.isNotEmpty()) {
            val tagMeta = tagBanks.mapValues { (_, t) -> TagMeta(t.category, t.notes, t.score) }
            File(outputDir, "tags.json").writeText(Gson().toJson(tagMeta))
            Log.d(TAG, "Stored ${tagMeta.size} tag descriptions for ${info.title}")
        }

        DictionaryDb.getInstance(context).reloadFromConfig(configManager)

        onProgress("Done", 1f)
        Log.d(TAG, "Imported ${info.title}: $entryCount entries")
        return ImportResult(true, dictTitle = info.title, entryCount = entryCount)
    }

    /**
     * Backfill rich content into an existing dictionary from its original Yomitan ZIP.
     * The user picks the same ZIP they originally imported; we match entries and add glossary_rich.
     */
    suspend fun deleteDictionary(dictId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val configManager = DictionaryConfigManager(context)
            val dict = configManager.getInstalledDictionaries().find { it.id == dictId }
                ?: return@withContext false

            configManager.removeDictionary(dictId)
            configManager.setDictionaryCss(dictId, null)

            // Remove the per-dictionary folder (Hoshidicts data + images + tags.json).
            File(dictDir, dictId).takeIf { it.exists() }?.deleteRecursively()
            // Clean up any leftover legacy .db file.
            File(dictDir, dict.dbFileName).takeIf { it.exists() && it.isFile }?.delete()

            DictionaryDb.getInstance(context).reloadFromConfig(configManager)
            Log.d(TAG, "Deleted dictionary: $dictId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed for $dictId: ${e.message}", e)
            false
        }
    }

    /**
     * Folder/id for a dictionary, derived from its title. Titles with no ASCII
     * alphanumerics (e.g. all-Japanese names) collapse to a short or empty base,
     * so we append a stable hash of the title and disambiguate against existing
     * ids — otherwise two distinct dictionaries could share a folder and the
     * second import would wipe the first.
     */
    private fun uniqueId(title: String, taken: Set<String>): String {
        val base = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(40)
        val titleHash = Integer.toHexString(title.hashCode())
        // Weak bases (empty or very short) get the hash up front; otherwise keep
        // the readable base and only add the hash if it would collide.
        var candidate = if (base.length < 3) "dict_$titleHash" else base
        if (candidate in taken) candidate = "${base}_$titleHash"
        var n = 2
        while (candidate in taken) candidate = "${base}_${titleHash}_${n++}"
        return candidate
    }

    private fun cleanup(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup failed: ${e.message}")
        }
    }
}

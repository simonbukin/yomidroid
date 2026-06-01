package com.yomidroid.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.yomidroid.config.DictSourceType
import com.yomidroid.config.DictionaryConfigManager
import com.yomidroid.config.InstalledDictionary
import com.yomidroid.dictionary.HoshiDicts
import com.yomidroid.dictionary.HoshiKanji
import com.yomidroid.dictionary.HoshiTerm
import java.io.File

/**
 * Coordinator for the Hoshidicts dictionary backend.
 *
 * Replaces the former multi-SQLite manager: dictionaries now live in the compact
 * Hoshidicts on-disk format (one folder per dictionary under
 * `filesDir/dictionaries/<dictId>/<title>`), and term/frequency/pitch/kanji
 * lookups + deconjugation happen natively via [HoshiDicts]. This class owns the
 * config→backend wiring (reset + add dicts in priority order) and the per-dict
 * metadata (titles, priority, source, tag descriptions) the result mapper needs.
 *
 * The public surface (getInstance / reloadFromConfig / count / isValid /
 * findWordsContainingKanji) is preserved so existing callers keep working.
 */
class DictionaryDb private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DictionaryDb"

        @Volatile
        private var INSTANCE: DictionaryDb? = null

        fun getInstance(context: Context): DictionaryDb {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DictionaryDb(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /** Per-dictionary metadata used to map Hoshidicts results to DictionaryEntry. */
    data class DictMeta(
        val id: String,
        val title: String,
        val priority: Int,
        val type: DictSourceType,
        val source: String,
        val isNames: Boolean
    )

    private val gson = Gson()

    private val dictDir: File
        get() = File(context.filesDir, "dictionaries").also { it.mkdirs() }

    // Snapshot of the currently-loaded dictionary set.
    @Volatile private var metaByTitleMap: Map<String, DictMeta> = emptyMap()
    @Volatile private var tagMetaByDict: Map<String, Map<String, TagMeta>> = emptyMap()
    @Volatile private var freqTitles: List<String> = emptyList()
    @Volatile private var termDictCount: Int = 0
    @Volatile private var totalEntryCount: Int = 0

    // Term dictionary ids in priority order, for the kanji→words index.
    @Volatile private var termDictIds: List<String> = emptyList()
    // Lazily-loaded, reload-invalidated kanji→words indices (dictId → kanji → words).
    private val kanjiIndexCache = HashMap<String, Map<String, List<String>>>()

    init {
        cleanupLegacyDatabase()
        reloadFromConfig(DictionaryConfigManager(context))
    }

    /** Remove the old bundled dictionary.db from before the import system, if present. */
    private fun cleanupLegacyDatabase() {
        try {
            val legacyDb = context.getDatabasePath("dictionary.db")
            if (legacyDb.exists()) {
                legacyDb.delete()
                File(legacyDb.path + "-journal").takeIf { it.exists() }?.delete()
                File(legacyDb.path + "-shm").takeIf { it.exists() }?.delete()
                File(legacyDb.path + "-wal").takeIf { it.exists() }?.delete()
                Log.d(TAG, "Cleaned up legacy bundled dictionary.db")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up legacy DB: ${e.message}")
        }
    }

    /**
     * Resolve the Hoshidicts dictionary folder for an installed entry. Returns
     * null for legacy (pre-Hoshidicts, `.db`) installs or missing folders.
     */
    private fun hoshiFolder(dict: InstalledDictionary): File? {
        if (dict.dbFileName.endsWith(".db")) return null
        val folder = File(dictDir, dict.dbFileName)
        return if (File(folder, ".hoshidicts_1").exists()) folder else null
    }

    /**
     * Rebuild the native dictionary set + metadata from the current config.
     *
     * Migration: any leftover legacy `.db` installs (from the SQLite era) are
     * dropped here — Hoshidicts can't read them, so they're removed from config
     * and their files deleted; the user re-imports them through the new path.
     */
    @Synchronized
    fun reloadFromConfig(configManager: DictionaryConfigManager) {
        // Migrate away legacy .db installs.
        val all = configManager.getInstalledDictionaries()
        val legacy = all.filter { it.dbFileName.endsWith(".db") }
        if (legacy.isNotEmpty()) {
            Log.w(TAG, "Dropping ${legacy.size} legacy SQLite dictionaries (re-import required): " +
                    legacy.joinToString { it.title })
            for (d in legacy) {
                File(dictDir, d.dbFileName).takeIf { it.exists() }?.delete()
                configManager.removeDictionary(d.id)
            }
        }

        val installed = configManager.getInstalledDictionaries()
            .filter { it.enabled }
            .sortedBy { it.priority }

        val metaMap = HashMap<String, DictMeta>()
        val tagMap = HashMap<String, Map<String, TagMeta>>()
        val freqOrder = mutableListOf<String>()
        val termPaths = mutableListOf<String>()
        val freqPaths = mutableListOf<String>()
        val pitchPaths = mutableListOf<String>()
        val kanjiPaths = mutableListOf<String>()
        val termIds = mutableListOf<String>()
        var entryTotal = 0

        installed.forEachIndexed { index, dict ->
            val folder = hoshiFolder(dict)
            if (folder == null) {
                Log.w(TAG, "Dictionary folder missing for ${dict.title} (${dict.dbFileName})")
                return@forEachIndexed
            }
            val path = folder.absolutePath
            val isNames = dict.type == DictSourceType.NAMES
            when (dict.type) {
                DictSourceType.FREQUENCY -> { freqPaths.add(path); freqOrder.add(dict.title) }
                DictSourceType.PITCH -> pitchPaths.add(path)
                DictSourceType.KANJI -> kanjiPaths.add(path)
                else -> { termPaths.add(path); termIds.add(dict.id); entryTotal += dict.entryCount }
            }
            metaMap[dict.title] = DictMeta(
                id = dict.id,
                title = dict.title,
                priority = index,
                type = dict.type,
                source = inferSource(dict.title, dict.type),
                isNames = isNames
            )
            loadTagMeta(dict.id)?.let { tagMap[dict.id] = it }
        }

        // Single atomic rebuild so concurrent lookups never see a partial set.
        HoshiDicts.load(termPaths, freqPaths, pitchPaths, kanjiPaths)

        val termCount = termPaths.size
        metaByTitleMap = metaMap
        tagMetaByDict = tagMap
        freqTitles = freqOrder
        termDictCount = termCount
        totalEntryCount = entryTotal
        termDictIds = termIds
        synchronized(kanjiIndexCache) { kanjiIndexCache.clear() }

        sweepOrphanFolders(configManager)
        Log.d(TAG, "Loaded ${metaMap.size} dictionaries ($termCount term, ${freqOrder.size} freq)")
    }

    /**
     * Delete anything under dictionaries/ that isn't a current install's folder
     * — sweeps legacy `.db` files and stale image/dict folders left by old
     * versions so the directory doesn't accumulate orphans across upgrades.
     */
    private fun sweepOrphanFolders(configManager: DictionaryConfigManager) {
        try {
            val keep = configManager.getInstalledDictionaries().map { it.id }.toHashSet()
            val dir = File(context.filesDir, "dictionaries")
            dir.listFiles()?.forEach { child ->
                // A current install's folder is named by its dictId; legacy DBs were "<id>.db".
                val baseId = child.name.removeSuffix(".db")
                if (baseId !in keep) {
                    val ok = child.deleteRecursively()
                    Log.d(TAG, "Swept orphan ${child.name} (deleted=$ok)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Orphan sweep failed: ${e.message}")
        }
    }

    private fun inferSource(title: String, type: DictSourceType): String {
        if (type == DictSourceType.NAMES) return "jmnedict"
        val lower = title.lowercase()
        return when {
            "jmnedict" in lower || "neologism" in lower -> "jmnedict"
            "jitendex" in lower -> "jitendex"
            else -> "custom"
        }
    }

    private fun loadTagMeta(dictId: String): Map<String, TagMeta>? {
        val file = File(File(dictDir, dictId), "tags.json")
        if (!file.exists()) return null
        return try {
            val type = object : TypeToken<Map<String, TagMeta>>() {}.type
            gson.fromJson<Map<String, TagMeta>>(file.readText(), type)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load tag meta for $dictId: ${e.message}")
            null
        }
    }

    // --- Lookup passthrough (mapping to DictionaryEntry lives in the engine) ---

    /** Longest-match lookup from the start of [text] (deconjugation-aware). */
    fun lookup(text: String, maxResults: Int = 16, scanLength: Int = 20): List<HoshiTerm> =
        HoshiDicts.lookup(text, maxResults, scanLength)

    /** Exact lookup by expression or reading (no deconjugation). */
    fun query(expression: String): List<HoshiTerm> = HoshiDicts.query(expression)

    // --- Metadata accessors for the result mapper ---

    fun metaByTitle(title: String): DictMeta? = metaByTitleMap[title]

    fun tagMetaFor(dictId: String): Map<String, TagMeta> = tagMetaByDict[dictId] ?: emptyMap()

    /** Frequency dictionary titles in priority order (for primary-rank selection). */
    val freqDictTitles: List<String> get() = freqTitles

    // --- Kanji ---

    /** Look up a single kanji across enabled kanji dictionaries (first hit). */
    fun findKanji(character: String): KanjiData? {
        val result: HoshiKanji = HoshiDicts.queryKanji(character)
        val entry = result.entries.firstOrNull() ?: return null
        return KanjiData(
            character = result.character,
            onyomi = entry.onyomi,
            kunyomi = entry.kunyomi,
            tags = entry.tags,
            meanings = org.json.JSONArray(entry.definitions).toString(),
            stats = org.json.JSONObject(entry.stats as Map<*, *>).toString()
        )
    }

    /** Load (and cache) the kanji→words index for a term dictionary. */
    private fun kanjiIndexFor(dictId: String): Map<String, List<String>> {
        synchronized(kanjiIndexCache) {
            kanjiIndexCache[dictId]?.let { return it }
            val file = File(File(dictDir, dictId), KanjiWordIndex.FILE_NAME)
            val loaded = KanjiWordIndex.load(file)
            kanjiIndexCache[dictId] = loaded
            return loaded
        }
    }

    /**
     * Find words whose expression contains [character], served from the
     * per-dictionary kanji→words index built at import time. Returns lightweight
     * [TermData] (expression only) — the Kanji Detail screen re-looks-up each for
     * full entries. Buckets are pre-ranked by dictionary score (common words
     * first); dictionaries are consulted in priority order.
     */
    fun findWordsContainingKanji(character: String, limit: Int = 10): List<TermData> {
        if (character.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (dictId in termDictIds) {
            kanjiIndexFor(dictId)[character]?.forEach { if (it != character) out.add(it) }
            if (out.size >= limit) break
        }
        return out.take(limit).map { expr ->
            TermData(
                id = 0L,
                expression = expr,
                reading = "",
                glossary = "[]",
                partsOfSpeech = "[]",
                score = 0,
                sequence = 0
            )
        }
    }

    /** Total entries across enabled term dictionaries (from config metadata). */
    fun count(): Int = totalEntryCount

    fun isValid(): Boolean = termDictCount > 0

    /**
     * Deprecated no-op: the native query holds no removable handles. Deletion is
     * handled by removing the config entry + folder and calling reloadFromConfig.
     */
    fun unregisterDictionary(dictId: String) {
        // intentionally empty; see reloadFromConfig
    }

    fun close() {
        HoshiDicts.reset()
    }
}

/**
 * Simple data class for term data (not a Room entity). Retained for the kanji
 * "words containing" path and any callers that consume raw term rows.
 */
data class TermData(
    val id: Long,
    val expression: String,
    val reading: String,
    val glossary: String, // JSON array
    val partsOfSpeech: String, // JSON array
    val score: Int,
    val sequence: Int,
    val source: String = "custom",
    val nameType: String? = null,
    val frequencyRank: Int? = null,
    val jpdbRank: Int? = null,
    val pitchDownstep: Int? = null,
    val pitchDownsteps: List<Int> = emptyList(),
    val sourceDictId: String = "",
    val dictionaryTitle: String = "",
    val additionalFrequencies: Map<String, Int> = emptyMap(),
    val glossaryRich: String? = null,
    val definitionTags: String? = null,
    val tagMeta: Map<String, TagMeta> = emptyMap()
)

data class TagMeta(
    val category: String,
    val notes: String,
    val score: Int
)

data class KanjiData(
    val character: String,
    val onyomi: String,
    val kunyomi: String,
    val tags: String,
    val meanings: String, // JSON array
    val stats: String     // JSON object
)

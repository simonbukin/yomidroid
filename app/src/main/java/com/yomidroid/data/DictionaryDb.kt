package com.yomidroid.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.yomidroid.config.DictSourceType
import com.yomidroid.config.DictionaryConfigManager
import java.io.File

/**
 * Multi-dictionary database manager.
 * Manages multiple SQLite databases (term + frequency) loaded from user-imported dictionaries.
 * Starts empty — dictionaries are added via the import flow.
 */
class DictionaryDb private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DictionaryDb"

        @Volatile
        private var INSTANCE: DictionaryDb? = null

        fun getInstance(context: Context): DictionaryDb {
            return INSTANCE ?: synchronized(this) {
                val instance = DictionaryDb(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    // Dictionary databases (dictId -> open database)
    private val dictionaryDatabases = mutableMapOf<String, SQLiteDatabase>()

    // Frequency databases (dictId -> open database)
    private val frequencyDatabases = mutableMapOf<String, SQLiteDatabase>()

    // Pitch accent databases (dictId -> open database)
    private val pitchDatabases = mutableMapOf<String, SQLiteDatabase>()

    // Kanji databases (dictId -> open database)
    private val kanjiDatabases = mutableMapOf<String, SQLiteDatabase>()

    // Priority-ordered list of enabled dictionary IDs
    private var dictionaryPriority = listOf<String>()

    // Priority-ordered list of enabled frequency IDs
    private var frequencyPriority = listOf<String>()

    // Priority-ordered list of enabled pitch IDs
    private var pitchPriority = listOf<String>()

    // Map dictId → title from InstalledDictionary (for CSS scoping)
    private var dictTitleMap = mapOf<String, String>()

    init {
        cleanupLegacyDatabase()
        cleanupOrphanFiles(DictionaryConfigManager(context))
        reloadFromConfig(DictionaryConfigManager(context))
    }

    /**
     * Remove the old bundled dictionary.db from the databases folder.
     * This was the 187MB asset-copied DB from before the import system.
     */
    private fun cleanupLegacyDatabase() {
        try {
            val legacyDb = context.getDatabasePath("dictionary.db")
            if (legacyDb.exists()) {
                legacyDb.delete()
                // Also clean up journal/wal files
                File(legacyDb.path + "-journal").takeIf { it.exists() }?.delete()
                File(legacyDb.path + "-shm").takeIf { it.exists() }?.delete()
                File(legacyDb.path + "-wal").takeIf { it.exists() }?.delete()
                // Clear the old version pref
                context.getSharedPreferences("dictionary_prefs", Context.MODE_PRIVATE)
                    .edit().clear().apply()
                Log.d(TAG, "Cleaned up legacy bundled dictionary.db")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up legacy DB: ${e.message}")
        }
    }

    /**
     * Remove .db files in the dictionaries/ folder that aren't referenced by any installed dictionary.
     */
    private fun cleanupOrphanFiles(configManager: DictionaryConfigManager) {
        try {
            val dictDir = File(context.filesDir, "dictionaries")
            if (!dictDir.exists()) return

            val installedFileNames = configManager.getInstalledDictionaries()
                .map { it.dbFileName }
                .toSet()

            val files = dictDir.listFiles() ?: return
            for (file in files) {
                if (file.name !in installedFileNames) {
                    val deleted = file.delete()
                    Log.d(TAG, "Cleaned up orphan file: ${file.name} (deleted=$deleted)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up orphan files: ${e.message}")
        }
    }

    /**
     * Reload all databases based on current config.
     */
    fun reloadFromConfig(configManager: DictionaryConfigManager) {
        // Close existing databases
        dictionaryDatabases.values.forEach { it.close() }
        dictionaryDatabases.clear()
        frequencyDatabases.values.forEach { it.close() }
        frequencyDatabases.clear()
        pitchDatabases.values.forEach { it.close() }
        pitchDatabases.clear()
        kanjiDatabases.values.forEach { it.close() }
        kanjiDatabases.clear()

        val installed = configManager.getInstalledDictionaries()
            .filter { it.enabled }
            .sortedBy { it.priority }

        val dictDir = File(context.filesDir, "dictionaries")

        val dictPriority = mutableListOf<String>()
        val freqPriority = mutableListOf<String>()
        val pitchPriorityList = mutableListOf<String>()

        for (dict in installed) {
            val dbFile = File(dictDir, dict.dbFileName)
            if (!dbFile.exists()) {
                Log.w(TAG, "Dictionary file missing: ${dict.dbFileName}")
                continue
            }

            try {
                val db = SQLiteDatabase.openDatabase(
                    dbFile.path, null, SQLiteDatabase.OPEN_READONLY
                )

                when (dict.type) {
                    DictSourceType.FREQUENCY -> {
                        frequencyDatabases[dict.id] = db
                        freqPriority.add(dict.id)
                        Log.d(TAG, "Loaded frequency DB: ${dict.id}")
                    }
                    DictSourceType.PITCH -> {
                        pitchDatabases[dict.id] = db
                        pitchPriorityList.add(dict.id)
                        Log.d(TAG, "Loaded pitch DB: ${dict.id}")
                    }
                    DictSourceType.KANJI -> {
                        kanjiDatabases[dict.id] = db
                        Log.d(TAG, "Loaded kanji DB: ${dict.id}")
                    }
                    else -> {
                        dictionaryDatabases[dict.id] = db
                        dictPriority.add(dict.id)
                        Log.d(TAG, "Loaded dictionary DB: ${dict.id}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open DB ${dict.dbFileName}: ${e.message}")
            }
        }

        dictionaryPriority = dictPriority
        frequencyPriority = freqPriority
        pitchPriority = pitchPriorityList
        dictTitleMap = installed.associate { it.id to it.title }

        Log.d(TAG, "Loaded dictionaries: $dictionaryPriority, frequencies: $frequencyPriority, pitch: $pitchPriority")
    }

    fun unregisterDictionary(dictId: String) {
        dictionaryDatabases.remove(dictId)?.close()
        frequencyDatabases.remove(dictId)?.close()
        pitchDatabases.remove(dictId)?.close()
        kanjiDatabases.remove(dictId)?.close()
        dictionaryPriority = dictionaryPriority.filter { it != dictId }
        frequencyPriority = frequencyPriority.filter { it != dictId }
        pitchPriority = pitchPriority.filter { it != dictId }
    }

    /**
     * Find terms by expression or reading (exact match).
     * Queries all enabled dictionaries in priority order.
     * Enriches results with frequency data from enabled frequency databases.
     */
    fun findByExpressionOrReading(query: String): List<TermData> {
        val results = mutableListOf<TermData>()

        // Query dictionaries in priority order
        for (dictId in dictionaryPriority) {
            val db = dictionaryDatabases[dictId] ?: continue
            results.addAll(queryDatabase(db, query, dictId))
        }

        // Enrich with frequency data
        if (frequencyDatabases.isNotEmpty() && results.isNotEmpty()) {
            for (i in results.indices) {
                val term = results[i]
                val additionalFreqs = mutableMapOf<String, Int>()

                for ((freqId, freqDb) in frequencyDatabases) {
                    val rank = lookupFrequency(freqDb, term.expression)
                    if (rank != null) {
                        additionalFreqs[freqId] = rank
                    }
                }

                if (additionalFreqs.isNotEmpty()) {
                    val primaryFreq = term.frequencyRank ?: run {
                        val firstFreqId = frequencyPriority.firstOrNull { it in additionalFreqs }
                        firstFreqId?.let { additionalFreqs[it] }
                    }

                    results[i] = term.copy(
                        frequencyRank = primaryFreq ?: term.frequencyRank,
                        additionalFrequencies = additionalFreqs,
                        sourceDictId = term.sourceDictId
                    )
                }
            }
        }

        // Enrich with pitch accent data from external pitch databases
        if (pitchDatabases.isNotEmpty() && results.isNotEmpty()) {
            for (i in results.indices) {
                val term = results[i]
                if (term.pitchDownsteps.isNotEmpty()) continue // already has pitch from in-DB data

                for (pitchDb in pitchDatabases.values) {
                    val downsteps = lookupPitch(pitchDb, term.expression, term.reading)
                    if (downsteps.isNotEmpty()) {
                        results[i] = term.copy(
                            pitchDownstep = downsteps.first(),
                            pitchDownsteps = downsteps
                        )
                        break // use first match (highest priority pitch DB)
                    }
                }
            }
        }

        return results
    }

    private fun queryDatabase(db: SQLiteDatabase, query: String, sourceDictId: String): List<TermData> {
        val hasJpdb = hasColumn(db, "terms", "jpdb_rank")
        val hasPitch = hasTable(db, "pitch_accents")
        val hasGlossaryRich = hasColumn(db, "terms", "glossary_rich")
        val hasDefTags = hasColumn(db, "terms", "definition_tags")
        val hasTags = hasTable(db, "tags")

        // Load tag metadata once per query (cached per DB open anyway)
        val tagMetaMap = if (hasTags) loadTagMeta(db) else emptyMap()

        // Query without pitch JOIN to avoid row duplication
        val sql = buildString {
            append("SELECT t.id, t.expression, t.reading, t.glossary, t.pos, t.score, t.sequence,")
            append(" COALESCE(t.source, 'custom') as source, t.name_type, t.frequency_rank")
            if (hasJpdb) append(", t.jpdb_rank")
            if (hasGlossaryRich) append(", t.glossary_rich")
            if (hasDefTags) append(", t.definition_tags")
            append(" FROM terms t")
            append(" WHERE t.expression = ? OR t.reading = ?")
            append(" ORDER BY CASE WHEN t.frequency_rank IS NOT NULL THEN 0 ELSE 1 END,")
            append(" t.frequency_rank ASC, t.score DESC,")
            append(" CASE WHEN t.source = 'jmnedict' THEN 1 ELSE 0 END")
            append(" LIMIT 30")
        }

        val results = mutableListOf<TermData>()
        try {
            db.rawQuery(sql, arrayOf(query, query)).use { cursor ->
                while (cursor.moveToNext()) {
                    var col = 0
                    val id = cursor.getLong(col++)
                    val expression = cursor.getString(col++)
                    val reading = cursor.getString(col++)
                    val glossary = cursor.getString(col++)
                    val pos = cursor.getString(col++) ?: ""
                    val score = cursor.getInt(col++)
                    val sequence = cursor.getInt(col++)
                    val source = cursor.getString(col++) ?: "custom"
                    val nameType = cursor.getString(col++)
                    val frequencyRank = if (cursor.isNull(col)) null else cursor.getInt(col); col++
                    val jpdbRank = if (hasJpdb) { if (cursor.isNull(col)) null else cursor.getInt(col).also { col++ } } else null
                    val glossaryRich = if (hasGlossaryRich) { if (cursor.isNull(col)) null else cursor.getString(col).also { col++ } } else null
                    val definitionTags = if (hasDefTags) { if (cursor.isNull(col)) null else cursor.getString(col).also { col++ } } else null

                    // Look up in-DB pitch accents separately (returns all distinct downsteps)
                    val downsteps = if (hasPitch) lookupPitch(db, expression, reading) else emptyList()

                    // Build tag meta for this entry's tags (pos + definitionTags)
                    // Tags are space-separated but may be multi-word; match greedily against known tag names
                    val entryTagMeta = if (tagMetaMap.isNotEmpty()) {
                        val rawTags = buildString {
                            append(pos)
                            if (!definitionTags.isNullOrEmpty()) {
                                append(" ")
                                append(definitionTags)
                            }
                        }
                        matchTags(rawTags, tagMetaMap)
                    } else emptyMap()

                    results.add(
                        TermData(
                            id = id,
                            expression = expression,
                            reading = reading,
                            glossary = glossary,
                            partsOfSpeech = pos,
                            score = score,
                            sequence = sequence,
                            source = source,
                            nameType = nameType,
                            frequencyRank = frequencyRank,
                            jpdbRank = jpdbRank,
                            pitchDownstep = downsteps.firstOrNull(),
                            pitchDownsteps = downsteps,
                            sourceDictId = sourceDictId,
                            dictionaryTitle = dictTitleMap[sourceDictId] ?: "",
                            glossaryRich = glossaryRich,
                            definitionTags = definitionTags,
                            tagMeta = entryTagMeta
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Query failed on $sourceDictId: ${e.message}")
        }

        return results
    }

    /**
     * Match a space-separated tag string against known tag names (which may contain spaces).
     * Uses greedy longest-match: tries longest known tag names first.
     */
    private fun matchTags(raw: String, knownTags: Map<String, TagMeta>): Map<String, TagMeta> {
        if (raw.isBlank()) return emptyMap()
        val result = mutableMapOf<String, TagMeta>()
        // Sort known tag names by length descending for greedy matching
        val sortedNames = knownTags.keys.sortedByDescending { it.length }
        var remaining = raw.trim()
        while (remaining.isNotEmpty()) {
            val matched = sortedNames.firstOrNull { remaining.startsWith(it) && (remaining.length == it.length || remaining[it.length] == ' ') }
            if (matched != null) {
                result[matched] = knownTags[matched]!!
                remaining = remaining.removePrefix(matched).trimStart()
            } else {
                // Skip to next space
                val spaceIdx = remaining.indexOf(' ')
                remaining = if (spaceIdx >= 0) remaining.substring(spaceIdx + 1).trimStart() else ""
            }
        }
        return result
    }

    private fun loadTagMeta(db: SQLiteDatabase): Map<String, TagMeta> {
        val map = mutableMapOf<String, TagMeta>()
        try {
            db.rawQuery("SELECT name, category, notes, score FROM tags", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    map[name] = TagMeta(
                        category = cursor.getString(1) ?: "",
                        notes = cursor.getString(2) ?: "",
                        score = cursor.getInt(3)
                    )
                }
            }
        } catch (e: Exception) {
            // tags table doesn't exist or query failed
        }
        return map
    }

    private fun lookupFrequency(db: SQLiteDatabase, expression: String): Int? {
        return try {
            db.rawQuery(
                "SELECT rank FROM frequencies WHERE expression = ? LIMIT 1",
                arrayOf(expression)
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun lookupPitch(db: SQLiteDatabase, expression: String, reading: String): List<Int> {
        return try {
            // Try exact match on expression + reading first
            val results = mutableListOf<Int>()
            db.rawQuery(
                "SELECT DISTINCT downstep_position FROM pitch_accents WHERE expression = ? AND reading = ?",
                arrayOf(expression, reading)
            ).use { cursor ->
                while (cursor.moveToNext()) results.add(cursor.getInt(0))
            }
            if (results.isNotEmpty()) return results
            // Fallback: match by expression only
            db.rawQuery(
                "SELECT DISTINCT downstep_position FROM pitch_accents WHERE expression = ?",
                arrayOf(expression)
            ).use { cursor ->
                while (cursor.moveToNext()) results.add(cursor.getInt(0))
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        return try {
            db.rawQuery("SELECT $column FROM $table LIMIT 0", null).use { true }
        } catch (e: Exception) {
            false
        }
    }

    private fun hasTable(db: SQLiteDatabase, table: String): Boolean {
        return try {
            db.rawQuery("SELECT 1 FROM $table LIMIT 0", null).use { true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Look up a single kanji character across all enabled kanji databases.
     */
    fun findKanji(character: String): KanjiData? {
        for (db in kanjiDatabases.values) {
            try {
                db.rawQuery(
                    "SELECT character, onyomi, kunyomi, tags, meanings, stats FROM kanji WHERE character = ? LIMIT 1",
                    arrayOf(character)
                ).use { cursor ->
                    if (cursor.moveToFirst()) {
                        return KanjiData(
                            character = cursor.getString(0),
                            onyomi = cursor.getString(1) ?: "",
                            kunyomi = cursor.getString(2) ?: "",
                            tags = cursor.getString(3) ?: "",
                            meanings = cursor.getString(4) ?: "[]",
                            stats = cursor.getString(5) ?: "{}"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Kanji lookup failed: ${e.message}")
            }
        }
        return null
    }

    /**
     * Get total count of entries across all loaded dictionaries.
     */
    fun count(): Int {
        var total = 0
        for (db in dictionaryDatabases.values) {
            try {
                db.rawQuery("SELECT COUNT(*) FROM terms", null).use { cursor ->
                    if (cursor.moveToFirst()) total += cursor.getInt(0)
                }
            } catch (e: Exception) {
                // Skip databases that fail
            }
        }
        return total
    }

    /**
     * Check if any dictionary is loaded and has entries.
     */
    fun isValid(): Boolean {
        return dictionaryDatabases.isNotEmpty()
    }

    fun close() {
        dictionaryDatabases.values.forEach { it.close() }
        dictionaryDatabases.clear()
        frequencyDatabases.values.forEach { it.close() }
        frequencyDatabases.clear()
        pitchDatabases.values.forEach { it.close() }
        pitchDatabases.clear()
        kanjiDatabases.values.forEach { it.close() }
        kanjiDatabases.clear()
    }
}

/**
 * Simple data class for term data (not a Room entity).
 */
data class TermData(
    val id: Long,
    val expression: String,
    val reading: String,
    val glossary: String, // JSON array
    val partsOfSpeech: String, // JSON array
    val score: Int,
    val sequence: Int,
    // Multi-dictionary support
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

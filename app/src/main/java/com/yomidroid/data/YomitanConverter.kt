package com.yomidroid.data

import android.database.sqlite.SQLiteDatabase
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import com.yomidroid.config.DictSourceType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipFile

data class ConversionResult(
    val entryCount: Int,
    val success: Boolean,
    val error: String? = null
)

data class TagInfo(
    val name: String,
    val category: String,
    val order: Int,
    val notes: String,
    val score: Int
)

data class YomitanDictInfo(
    val title: String,
    val revision: String,
    val format: Int,
    val type: DictSourceType,
    val frequencyMode: String?,
    val description: String?,
    val author: String?
)

class YomitanConverter {

    companion object {
        private const val TAG = "YomitanConverter"
        private const val BATCH_SIZE = 5000
    }

    /**
     * Extract image files from a Yomitan dictionary ZIP to [outputDir].
     * Returns the number of images extracted.
     */
    fun extractImages(zipFile: File, outputDir: File): Int {
        return try {
            val zip = ZipFile(zipFile)
            val imageEntries = zip.entries().toList().filter { entry ->
                !entry.isDirectory && entry.name.let { name ->
                    name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
                    name.endsWith(".jpeg", true) || name.endsWith(".webp", true) ||
                    name.endsWith(".gif", true) || name.endsWith(".svg", true)
                }
            }

            if (imageEntries.isEmpty()) {
                zip.close()
                return 0
            }

            outputDir.mkdirs()
            var count = 0
            for (entry in imageEntries) {
                try {
                    // Use just the filename (strip directory path)
                    val fileName = entry.name.substringAfterLast('/')
                    val outFile = File(outputDir, fileName)
                    zip.getInputStream(entry).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    count++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to extract image ${entry.name}: ${e.message}")
                }
            }
            zip.close()
            Log.d(TAG, "Extracted $count images to ${outputDir.path}")
            count
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract images: ${e.message}")
            0
        }
    }

    /**
     * Read tag_bank_*.json files from a Yomitan dictionary ZIP.
     * Returns a map of tag name → TagInfo (category, order, notes, score).
     */
    fun readTagBanks(zipFile: File): Map<String, TagInfo> {
        val tags = mutableMapOf<String, TagInfo>()
        try {
            val zip = ZipFile(zipFile)
            val tagBanks = zip.entries().toList().filter {
                it.name.startsWith("tag_bank_") && it.name.endsWith(".json")
            }.sortedBy { it.name }

            for (entry in tagBanks) {
                val content = zip.getInputStream(entry).bufferedReader().readText()
                val arr = JSONArray(content)
                for (i in 0 until arr.length()) {
                    val tagArr = arr.optJSONArray(i) ?: continue
                    if (tagArr.length() < 3) continue
                    val name = tagArr.optString(0, "")
                    if (name.isEmpty()) continue
                    val category = tagArr.optString(1, "")
                    val order = tagArr.optInt(2, 0)
                    val notes = if (tagArr.length() > 3) tagArr.optString(3, "") else ""
                    val score = if (tagArr.length() > 4) tagArr.optInt(4, 0) else 0
                    tags[name] = TagInfo(name, category, order, notes, score)
                }
            }
            zip.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read tag banks: ${e.message}")
        }
        return tags
    }

    /**
     * Extract styles.css from a Yomitan dictionary ZIP, if present.
     * Returns the CSS string or null if the dictionary has no custom styles.
     */
    fun extractDictionaryCss(zipFile: File): String? {
        return try {
            val zip = ZipFile(zipFile)
            val stylesEntry = zip.getEntry("styles.css")
            if (stylesEntry == null) {
                zip.close()
                return null
            }
            val css = zip.getInputStream(stylesEntry).bufferedReader().readText()
            zip.close()
            if (css.isBlank()) null else css
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract styles.css: ${e.message}")
            null
        }
    }

    fun readDictInfo(zipFile: File): YomitanDictInfo? {
        return try {
            val zip = ZipFile(zipFile)
            val indexEntry = zip.getEntry("index.json")
            if (indexEntry == null) {
                zip.close()
                Log.e(TAG, "No index.json found in ZIP")
                return null
            }

            val indexJson = zip.getInputStream(indexEntry).bufferedReader().readText()
            val index = JSONObject(indexJson)

            val title = index.optString("title", "Unknown Dictionary")
            val revision = index.optString("revision", "")
            val format = index.optInt("format", 3)
            val description = index.optString("description", "").ifEmpty { null }
            val author = index.optString("author", "").ifEmpty { null }
            val frequencyMode = index.optString("frequencyMode", "")
                .takeIf { it.isNotEmpty() && it != "null" }

            // Detect type from bank files present in ZIP
            val entries = zip.entries().toList().map { it.name }
            val type = detectType(entries, frequencyMode, zipFile)

            zip.close()

            YomitanDictInfo(
                title = title,
                revision = revision,
                format = format,
                type = type,
                frequencyMode = frequencyMode,
                description = description,
                author = author
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read dict info: ${e.message}", e)
            null
        }
    }

    private fun detectType(fileNames: List<String>, frequencyMode: String?, zipFile: File): DictSourceType {
        val hasTermMetaBanks = fileNames.any { it.startsWith("term_meta_bank_") && it.endsWith(".json") }
        val hasTermBanks = fileNames.any { it.startsWith("term_bank_") && it.endsWith(".json") }
        val hasKanjiBanks = fileNames.any { it.startsWith("kanji_bank_") && it.endsWith(".json") }

        if (frequencyMode != null) return DictSourceType.FREQUENCY

        // Meta-only dictionaries: detect pitch vs frequency by peeking at the data
        if (hasTermMetaBanks && !hasTermBanks && !hasKanjiBanks) {
            return detectMetaType(zipFile, fileNames)
        }

        if (hasKanjiBanks) return DictSourceType.KANJI
        return DictSourceType.DICTIONARY
    }

    /**
     * Peek at the first term_meta_bank to distinguish pitch accent dicts from frequency dicts.
     * Pitch dicts have entries like [term, "pitch", {...}], frequency dicts have [term, "freq", ...].
     */
    private fun detectMetaType(zipFile: File, fileNames: List<String>): DictSourceType {
        val firstMetaBank = fileNames
            .filter { it.startsWith("term_meta_bank_") && it.endsWith(".json") }
            .sorted()
            .firstOrNull() ?: return DictSourceType.FREQUENCY

        return try {
            val zip = ZipFile(zipFile)
            val entry = zip.getEntry(firstMetaBank)
            val reader = JsonReader(InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8))

            var hasPitch = false
            var hasFreq = false

            reader.beginArray()
            var checked = 0
            while (reader.hasNext() && checked < 20) {
                val item = readJsonArray(reader)
                if (item.length() >= 3) {
                    when (item.optString(1, "")) {
                        "pitch" -> hasPitch = true
                        "freq" -> hasFreq = true
                    }
                }
                checked++
            }
            reader.close()
            zip.close()

            when {
                hasPitch && !hasFreq -> DictSourceType.PITCH
                hasFreq -> DictSourceType.FREQUENCY
                else -> DictSourceType.FREQUENCY // fallback
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect meta type, defaulting to FREQUENCY: ${e.message}")
            DictSourceType.FREQUENCY
        }
    }

    /**
     * Convert a Yomitan term dictionary ZIP to SQLite.
     * Uses streaming JSON parsing to avoid OOM on large dictionaries.
     */
    fun convertDictionary(
        zipFile: File,
        outputDb: File,
        info: YomitanDictInfo,
        onProgress: (Float) -> Unit
    ): ConversionResult {
        return try {
            outputDb.parentFile?.mkdirs()
            if (outputDb.exists()) outputDb.delete()

            val db = SQLiteDatabase.openOrCreateDatabase(outputDb, null)
            createTermsSchema(db)

            val zip = ZipFile(zipFile)
            val termBanks = zip.entries().toList().filter {
                it.name.startsWith("term_bank_") && it.name.endsWith(".json")
            }.sortedBy { it.name }

            if (termBanks.isEmpty()) {
                zip.close()
                db.close()
                return ConversionResult(0, false, "No term_bank files found in ZIP")
            }

            var totalInserted = 0
            val totalBanks = termBanks.size
            val sourceName = info.title
            val isNames = info.type == DictSourceType.NAMES ||
                    sourceName.lowercase().let { it.contains("jmnedict") || it.contains("name") }

            for ((bankIndex, entry) in termBanks.withIndex()) {
                val inserted = processTermBankStreaming(
                    zip, entry, db, sourceName, isNames
                )
                totalInserted += inserted
                onProgress((bankIndex + 1).toFloat() / totalBanks)
            }

            zip.close()
            db.close()

            Log.d(TAG, "Converted $totalInserted entries from $sourceName")
            ConversionResult(totalInserted, true)
        } catch (e: Exception) {
            Log.e(TAG, "Conversion failed: ${e.message}", e)
            ConversionResult(0, false, e.message)
        }
    }

    /**
     * Process a single term_bank JSON file using streaming JsonReader.
     * Each bank is a JSON array of term arrays: [[expr, reading, tags, ...], ...]
     */
    private fun processTermBankStreaming(
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        db: SQLiteDatabase,
        sourceName: String,
        isNames: Boolean
    ): Int {
        var inserted = 0

        val reader = JsonReader(InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8))
        reader.beginArray() // outer array

        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT INTO terms (expression, reading, glossary, pos, score, sequence, source, name_type, frequency_rank, glossary_rich, definition_tags) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            )

            while (reader.hasNext()) {
                // Each term is an array: [expression, reading, tags, deinflectionRule, score, definitions, sequence, definitionTags]
                val termArray = readJsonArray(reader)
                if (termArray.length() < 6) continue

                val expression = termArray.optString(0, "")
                if (expression.isEmpty()) continue
                val reading = termArray.optString(1, "").ifEmpty { expression }
                val tags = termArray.optString(2, "")
                val score = termArray.optInt(4, 0)
                val definitions = termArray.get(5)
                val sequence = if (termArray.length() > 6) termArray.optInt(6, 0) else 0
                val definitionTags = if (termArray.length() > 7) termArray.optString(7, "") else ""

                val glossary = extractGlossary(definitions)
                if (glossary.isEmpty()) continue

                val glossaryJson = JSONArray(glossary).toString()

                // Store raw structured-content JSON if any definition uses it
                val glossaryRich = if (definitions is JSONArray && hasStructuredContent(definitions)) {
                    definitions.toString()
                } else {
                    null
                }

                val nameDetected = isNames || tags.contains("name")
                val nameType = if (nameDetected) detectNameType(tags) else null

                stmt.clearBindings()
                stmt.bindString(1, expression)
                stmt.bindString(2, reading)
                stmt.bindString(3, glossaryJson)
                stmt.bindString(4, tags)
                stmt.bindLong(5, score.toLong())
                stmt.bindLong(6, sequence.toLong())
                stmt.bindString(7, sourceName)
                if (nameType != null) stmt.bindString(8, nameType) else stmt.bindNull(8)
                stmt.bindNull(9)
                if (glossaryRich != null) stmt.bindString(10, glossaryRich) else stmt.bindNull(10)
                if (definitionTags.isNotEmpty()) stmt.bindString(11, definitionTags) else stmt.bindNull(11)

                stmt.executeInsert()
                inserted++

                // Commit in batches to avoid holding a huge transaction
                if (inserted % BATCH_SIZE == 0) {
                    db.setTransactionSuccessful()
                    db.endTransaction()
                    db.beginTransaction()
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        reader.endArray()
        reader.close()

        return inserted
    }

    /**
     * Read a single JSON array from a JsonReader.
     * Used to read one term entry at a time without loading the full bank.
     * Falls back to reading the raw JSON token string for complex nested content.
     */
    private fun readJsonArray(reader: JsonReader): JSONArray {
        // We need to read an arbitrary JSON array element.
        // Use a StringBuilder to collect the raw JSON, then parse it.
        val sb = StringBuilder()
        sb.append('[')
        reader.beginArray()
        var first = true

        while (reader.hasNext()) {
            if (!first) sb.append(',')
            first = false
            appendJsonValue(reader, sb)
        }

        reader.endArray()
        sb.append(']')

        return JSONArray(sb.toString())
    }

    private fun appendJsonValue(reader: JsonReader, sb: StringBuilder) {
        when (reader.peek()) {
            JsonToken.STRING -> {
                val s = reader.nextString()
                sb.append(JSONObject.quote(s))
            }
            JsonToken.NUMBER -> {
                val s = reader.nextString()
                sb.append(s)
            }
            JsonToken.BOOLEAN -> sb.append(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                sb.append("null")
            }
            JsonToken.BEGIN_ARRAY -> {
                sb.append('[')
                reader.beginArray()
                var first = true
                while (reader.hasNext()) {
                    if (!first) sb.append(',')
                    first = false
                    appendJsonValue(reader, sb)
                }
                reader.endArray()
                sb.append(']')
            }
            JsonToken.BEGIN_OBJECT -> {
                sb.append('{')
                reader.beginObject()
                var first = true
                while (reader.hasNext()) {
                    if (!first) sb.append(',')
                    first = false
                    val name = reader.nextName()
                    sb.append(JSONObject.quote(name))
                    sb.append(':')
                    appendJsonValue(reader, sb)
                }
                reader.endObject()
                sb.append('}')
            }
            else -> reader.skipValue()
        }
    }

    /**
     * Convert a Yomitan frequency dictionary ZIP to SQLite.
     * Uses streaming JSON parsing.
     */
    fun convertFrequency(
        zipFile: File,
        outputDb: File,
        info: YomitanDictInfo,
        onProgress: (Float) -> Unit
    ): ConversionResult {
        return try {
            outputDb.parentFile?.mkdirs()
            if (outputDb.exists()) outputDb.delete()

            val db = SQLiteDatabase.openOrCreateDatabase(outputDb, null)
            createFrequencySchema(db)

            val zip = ZipFile(zipFile)
            val metaBanks = zip.entries().toList().filter {
                it.name.startsWith("term_meta_bank_") && it.name.endsWith(".json")
            }.sortedBy { it.name }

            if (metaBanks.isEmpty()) {
                zip.close()
                db.close()
                return ConversionResult(0, false, "No term_meta_bank files found in ZIP")
            }

            val isRankBased = info.frequencyMode == "rank-based"

            // Collect all frequency values — meta banks are small enough for streaming into a map
            val freqCounts = mutableMapOf<String, Int>()
            val totalBanks = metaBanks.size

            for ((bankIndex, entry) in metaBanks.withIndex()) {
                processFrequencyBankStreaming(zip, entry, freqCounts)
                onProgress(0.5f * (bankIndex + 1).toFloat() / totalBanks)
            }

            zip.close()

            // For rank-based dicts, values are already ranks
            // For occurrence-based, convert counts to ranks
            val rankedTerms = if (isRankBased) {
                freqCounts.entries.map { it.key to it.value }
            } else {
                freqCounts.entries
                    .sortedByDescending { it.value }
                    .mapIndexed { index, e -> e.key to (index + 1) }
            }

            // Insert ranks
            db.beginTransaction()
            try {
                val stmt = db.compileStatement(
                    "INSERT OR IGNORE INTO frequencies (expression, rank) VALUES (?, ?)"
                )

                for ((index, pair) in rankedTerms.withIndex()) {
                    stmt.clearBindings()
                    stmt.bindString(1, pair.first)
                    stmt.bindLong(2, pair.second.toLong())
                    stmt.executeInsert()

                    if (index % BATCH_SIZE == 0) {
                        onProgress(0.5f + 0.5f * index.toFloat() / rankedTerms.size)
                    }
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            db.close()

            Log.d(TAG, "Converted ${rankedTerms.size} frequency entries")
            ConversionResult(rankedTerms.size, true)
        } catch (e: Exception) {
            Log.e(TAG, "Frequency conversion failed: ${e.message}", e)
            ConversionResult(0, false, e.message)
        }
    }

    /**
     * Stream-process a single term_meta_bank file for frequency data.
     */
    private fun processFrequencyBankStreaming(
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        freqCounts: MutableMap<String, Int>
    ) {
        val reader = JsonReader(InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8))
        reader.beginArray()

        while (reader.hasNext()) {
            val item = readJsonArray(reader)
            if (item.length() < 3 || item.optString(1, "") != "freq") continue

            val term = item.getString(0)
            val freqData = item.get(2)
            val freqValue = extractFrequencyValue(freqData)

            if (freqValue != null && term !in freqCounts) {
                freqCounts[term] = freqValue
            }
        }

        reader.endArray()
        reader.close()
    }

    /**
     * Convert a Yomitan pitch accent dictionary ZIP to SQLite.
     * Pitch entries in term_meta_bank: [term, "pitch", {"reading": "...", "pitches": [{"position": N}]}]
     */
    fun convertPitchAccents(
        zipFile: File,
        outputDb: File,
        info: YomitanDictInfo,
        onProgress: (Float) -> Unit
    ): ConversionResult {
        return try {
            outputDb.parentFile?.mkdirs()
            if (outputDb.exists()) outputDb.delete()

            val db = SQLiteDatabase.openOrCreateDatabase(outputDb, null)
            createPitchSchema(db)

            val zip = ZipFile(zipFile)
            val metaBanks = zip.entries().toList().filter {
                it.name.startsWith("term_meta_bank_") && it.name.endsWith(".json")
            }.sortedBy { it.name }

            if (metaBanks.isEmpty()) {
                zip.close()
                db.close()
                return ConversionResult(0, false, "No term_meta_bank files found in ZIP")
            }

            var totalInserted = 0
            val totalBanks = metaBanks.size

            db.beginTransaction()
            try {
                val stmt = db.compileStatement(
                    "INSERT OR IGNORE INTO pitch_accents (expression, reading, downstep_position) VALUES (?, ?, ?)"
                )

                for ((bankIndex, entry) in metaBanks.withIndex()) {
                    val reader = JsonReader(InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8))
                    reader.beginArray()

                    while (reader.hasNext()) {
                        val item = readJsonArray(reader)
                        if (item.length() < 3 || item.optString(1, "") != "pitch") continue

                        val expression = item.getString(0)
                        val pitchData = item.optJSONObject(2) ?: continue
                        val reading = pitchData.optString("reading", expression)
                        val pitches = pitchData.optJSONArray("pitches") ?: continue

                        for (i in 0 until pitches.length()) {
                            val pitch = pitches.optJSONObject(i) ?: continue
                            val position = pitch.optInt("position", -1)
                            if (position < 0) continue

                            stmt.clearBindings()
                            stmt.bindString(1, expression)
                            stmt.bindString(2, reading)
                            stmt.bindLong(3, position.toLong())
                            stmt.executeInsert()
                            totalInserted++

                            if (totalInserted % BATCH_SIZE == 0) {
                                db.setTransactionSuccessful()
                                db.endTransaction()
                                db.beginTransaction()
                            }
                        }
                    }

                    reader.endArray()
                    reader.close()
                    onProgress((bankIndex + 1).toFloat() / totalBanks)
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            zip.close()
            db.close()

            Log.d(TAG, "Converted $totalInserted pitch accent entries from ${info.title}")
            ConversionResult(totalInserted, true)
        } catch (e: Exception) {
            Log.e(TAG, "Pitch accent conversion failed: ${e.message}", e)
            ConversionResult(0, false, e.message)
        }
    }

    /**
     * Convert a Yomitan kanji dictionary ZIP to SQLite.
     * Kanji entries in kanji_bank: [character, onyomi, kunyomi, tags, [meanings], {stats}]
     */
    fun convertKanji(
        zipFile: File,
        outputDb: File,
        info: YomitanDictInfo,
        onProgress: (Float) -> Unit
    ): ConversionResult {
        return try {
            outputDb.parentFile?.mkdirs()
            if (outputDb.exists()) outputDb.delete()

            val db = SQLiteDatabase.openOrCreateDatabase(outputDb, null)
            createKanjiSchema(db)

            val zip = ZipFile(zipFile)
            val kanjiBanks = zip.entries().toList().filter {
                it.name.startsWith("kanji_bank_") && it.name.endsWith(".json")
            }.sortedBy { it.name }

            if (kanjiBanks.isEmpty()) {
                zip.close()
                db.close()
                return ConversionResult(0, false, "No kanji_bank files found in ZIP")
            }

            var totalInserted = 0
            val totalBanks = kanjiBanks.size

            db.beginTransaction()
            try {
                val stmt = db.compileStatement(
                    "INSERT OR IGNORE INTO kanji (character, onyomi, kunyomi, tags, meanings, stats) VALUES (?, ?, ?, ?, ?, ?)"
                )

                for ((bankIndex, entry) in kanjiBanks.withIndex()) {
                    val reader = JsonReader(InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8))
                    reader.beginArray()

                    while (reader.hasNext()) {
                        val item = readJsonArray(reader)
                        if (item.length() < 5) continue

                        val character = item.optString(0, "")
                        if (character.isEmpty()) continue
                        val onyomi = item.optString(1, "")
                        val kunyomi = item.optString(2, "")
                        val tags = item.optString(3, "")
                        val meanings = item.optJSONArray(4)?.let { arr ->
                            (0 until arr.length()).mapNotNull { arr.optString(it) }
                        } ?: emptyList()
                        val stats = if (item.length() > 5) item.optJSONObject(5)?.toString() ?: "{}" else "{}"

                        stmt.clearBindings()
                        stmt.bindString(1, character)
                        stmt.bindString(2, onyomi)
                        stmt.bindString(3, kunyomi)
                        stmt.bindString(4, tags)
                        stmt.bindString(5, JSONArray(meanings).toString())
                        stmt.bindString(6, stats)
                        stmt.executeInsert()
                        totalInserted++

                        if (totalInserted % BATCH_SIZE == 0) {
                            db.setTransactionSuccessful()
                            db.endTransaction()
                            db.beginTransaction()
                        }
                    }

                    reader.endArray()
                    reader.close()
                    onProgress((bankIndex + 1).toFloat() / totalBanks)
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }

            zip.close()
            db.close()

            Log.d(TAG, "Converted $totalInserted kanji entries from ${info.title}")
            ConversionResult(totalInserted, true)
        } catch (e: Exception) {
            Log.e(TAG, "Kanji conversion failed: ${e.message}", e)
            ConversionResult(0, false, e.message)
        }
    }

    private fun createKanjiSchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS kanji (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                character TEXT NOT NULL UNIQUE,
                onyomi TEXT,
                kunyomi TEXT,
                tags TEXT,
                meanings TEXT NOT NULL,
                stats TEXT
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_kanji_char ON kanji(character)")
    }

    private fun createPitchSchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pitch_accents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                expression TEXT NOT NULL,
                reading TEXT NOT NULL,
                downstep_position INTEGER NOT NULL,
                UNIQUE(expression, reading, downstep_position)
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pitch_expression ON pitch_accents(expression)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pitch_reading ON pitch_accents(expression, reading)")
    }

    /**
     * Store tag bank metadata into the dictionary database.
     */
    fun storeTagBanks(db: SQLiteDatabase, tags: Map<String, TagInfo>) {
        if (tags.isEmpty()) return
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS tags (
                name TEXT PRIMARY KEY,
                category TEXT,
                sort_order INTEGER DEFAULT 0,
                notes TEXT,
                score INTEGER DEFAULT 0
            )
        """)
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO tags (name, category, sort_order, notes, score) VALUES (?, ?, ?, ?, ?)"
            )
            for ((_, tag) in tags) {
                stmt.clearBindings()
                stmt.bindString(1, tag.name)
                stmt.bindString(2, tag.category)
                stmt.bindLong(3, tag.order.toLong())
                stmt.bindString(4, tag.notes)
                stmt.bindLong(5, tag.score.toLong())
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        Log.d(TAG, "Stored ${tags.size} tag definitions")
    }

    private fun createTermsSchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS terms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                expression TEXT NOT NULL,
                reading TEXT NOT NULL,
                glossary TEXT NOT NULL,
                pos TEXT,
                score INTEGER DEFAULT 0,
                sequence INTEGER,
                source TEXT NOT NULL DEFAULT 'custom',
                name_type TEXT,
                frequency_rank INTEGER,
                glossary_rich TEXT,
                definition_tags TEXT
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_expression ON terms(expression)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_reading ON terms(reading)")
    }

    private fun createFrequencySchema(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS frequencies (
                expression TEXT PRIMARY KEY,
                rank INTEGER NOT NULL
            )
        """)
    }

    /**
     * Check if any definition in the array uses structured-content (JSONObject with type/tag).
     */
    private fun hasStructuredContent(definitions: JSONArray): Boolean {
        for (i in 0 until definitions.length()) {
            val item = definitions.opt(i)
            if (item is JSONObject) return true
        }
        return false
    }

    private fun extractGlossary(definitions: Any): List<String> {
        val glossary = mutableListOf<String>()

        when (definitions) {
            is String -> {
                if (definitions.isNotBlank()) glossary.add(definitions)
            }
            is JSONArray -> {
                for (i in 0 until definitions.length()) {
                    val item = definitions.get(i)
                    val text = extractTextFromContent(item)
                    if (text.isNotBlank()) glossary.add(text.trim())
                }
            }
        }

        return glossary
    }

    private fun extractTextFromContent(content: Any?): String {
        if (content == null) return ""

        if (content is String) return content

        if (content is JSONObject) {
            val type = content.optString("type", "")
            when (type) {
                "structured-content" -> {
                    val inner = content.opt("content")
                    val glosses = extractGlossaryFromStructured(inner)
                    if (glosses.isNotEmpty()) return glosses.joinToString("; ")
                    return extractTextRecursive(inner)
                }
                "text" -> return content.optString("text", "")
                "image" -> return ""
            }
        }

        if (content is JSONArray) {
            val parts = mutableListOf<String>()
            for (i in 0 until content.length()) {
                val text = extractTextFromContent(content.get(i))
                if (text.isNotBlank()) parts.add(text)
            }
            return parts.joinToString(" ")
        }

        return extractTextRecursive(content)
    }

    private fun extractTextRecursive(content: Any?): String {
        if (content == null) return ""
        if (content is String) return content

        if (content is JSONArray) {
            val parts = mutableListOf<String>()
            for (i in 0 until content.length()) {
                val text = extractTextRecursive(content.get(i))
                if (text.isNotBlank()) parts.add(text)
            }
            return parts.joinToString(" ")
        }

        if (content is JSONObject) {
            val tag = content.optString("tag", "")
            if (tag in listOf("img", "a")) return ""
            if (tag == "ruby") {
                val inner = content.opt("content")
                if (inner is JSONArray) {
                    val sb = StringBuilder()
                    for (i in 0 until inner.length()) {
                        val item = inner.get(i)
                        if (item is String) sb.append(item)
                    }
                    return sb.toString()
                }
                return extractTextRecursive(inner)
            }
            if (tag in listOf("rt", "rp")) return ""
            return extractTextRecursive(content.opt("content"))
        }

        return ""
    }

    private fun extractGlossaryFromStructured(content: Any?): List<String> {
        val glosses = mutableListOf<String>()
        findGlossaries(content, glosses)
        return glosses
    }

    private fun findGlossaries(node: Any?, glosses: MutableList<String>) {
        if (node == null || node is String) return

        if (node is JSONArray) {
            for (i in 0 until node.length()) {
                findGlossaries(node.get(i), glosses)
            }
            return
        }

        if (node is JSONObject) {
            val data = node.optJSONObject("data")
            if (data != null && data.optString("content") == "glossary") {
                val inner = node.opt("content")
                if (inner is JSONObject && inner.optString("tag") == "li") {
                    val text = extractTextRecursive(inner.opt("content"))
                    if (text.isNotBlank()) glosses.add(text.trim())
                } else if (inner is JSONArray) {
                    for (i in 0 until inner.length()) {
                        val item = inner.get(i)
                        if (item is JSONObject && item.optString("tag") == "li") {
                            val text = extractTextRecursive(item.opt("content"))
                            if (text.isNotBlank()) glosses.add(text.trim())
                        }
                    }
                }
            } else {
                findGlossaries(node.opt("content"), glosses)
            }
        }
    }

    /**
     * Backfill glossary_rich into an existing dictionary database from its original Yomitan ZIP.
     * Adds the column if missing, then matches entries by (expression, reading, sequence)
     * and updates glossary_rich with the raw JSON definitions.
     */
    fun backfillRichContent(
        zipFile: File,
        dbFile: File,
        onProgress: (Float) -> Unit
    ): ConversionResult {
        return try {
            val db = SQLiteDatabase.openDatabase(
                dbFile.path, null, SQLiteDatabase.OPEN_READWRITE
            )

            // Add column if missing
            try {
                db.rawQuery("SELECT glossary_rich FROM terms LIMIT 0", null).use {}
            } catch (e: Exception) {
                db.execSQL("ALTER TABLE terms ADD COLUMN glossary_rich TEXT")
                Log.d(TAG, "Added glossary_rich column to ${dbFile.name}")
            }

            val zip = ZipFile(zipFile)
            val termBanks = zip.entries().toList().filter {
                it.name.startsWith("term_bank_") && it.name.endsWith(".json")
            }.sortedBy { it.name }

            if (termBanks.isEmpty()) {
                zip.close()
                db.close()
                return ConversionResult(0, false, "No term_bank files found in ZIP")
            }

            var totalUpdated = 0
            val totalBanks = termBanks.size

            for ((bankIndex, entry) in termBanks.withIndex()) {
                totalUpdated += backfillTermBank(zip, entry, db)
                onProgress((bankIndex + 1).toFloat() / totalBanks)
            }

            zip.close()
            db.close()

            Log.d(TAG, "Backfilled $totalUpdated entries with rich content")
            ConversionResult(totalUpdated, true)
        } catch (e: Exception) {
            Log.e(TAG, "Backfill failed: ${e.message}", e)
            ConversionResult(0, false, e.message)
        }
    }

    /**
     * Process a single term_bank for backfilling glossary_rich.
     * Matches by (expression, reading, sequence) and updates glossary_rich.
     */
    private fun backfillTermBank(
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        db: SQLiteDatabase
    ): Int {
        var updated = 0

        val reader = JsonReader(InputStreamReader(zip.getInputStream(entry), Charsets.UTF_8))
        reader.beginArray()

        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "UPDATE terms SET glossary_rich = ? WHERE expression = ? AND reading = ? AND sequence = ?"
            )

            while (reader.hasNext()) {
                val termArray = readJsonArray(reader)
                if (termArray.length() < 6) continue

                val expression = termArray.optString(0, "")
                if (expression.isEmpty()) continue
                val reading = termArray.optString(1, "").ifEmpty { expression }
                val definitions = termArray.get(5)
                val sequence = if (termArray.length() > 6) termArray.optInt(6, 0) else 0

                // Only store if there's structured content
                if (definitions is JSONArray && hasStructuredContent(definitions)) {
                    stmt.clearBindings()
                    stmt.bindString(1, definitions.toString())
                    stmt.bindString(2, expression)
                    stmt.bindString(3, reading)
                    stmt.bindLong(4, sequence.toLong())
                    val rowsAffected = stmt.executeUpdateDelete()
                    updated += rowsAffected

                    if (updated % BATCH_SIZE == 0) {
                        db.setTransactionSuccessful()
                        db.endTransaction()
                        db.beginTransaction()
                    }
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        reader.endArray()
        reader.close()

        return updated
    }

    private fun extractFrequencyValue(freqData: Any?): Int? {
        if (freqData is Number) return freqData.toInt()

        if (freqData is JSONObject) {
            if (freqData.has("value")) return freqData.optInt("value")
            if (freqData.has("frequency")) {
                val freq = freqData.get("frequency")
                if (freq is Number) return freq.toInt()
                if (freq is JSONObject) return freq.optInt("value", -1).takeIf { it >= 0 }
            }
            if (freqData.has("displayValue")) {
                return try {
                    freqData.getString("displayValue").replace(",", "").toInt()
                } catch (e: Exception) {
                    null
                }
            }
        }

        return null
    }

    private fun detectNameType(tags: String): String {
        val lower = tags.lowercase()
        return when {
            "surname" in lower -> "surname"
            "place" in lower -> "place"
            "given" in lower || "fem" in lower || "masc" in lower -> "given"
            "company" in lower || "org" in lower -> "company"
            "product" in lower -> "product"
            "work" in lower -> "work"
            "person" in lower || "unclass" in lower -> "person"
            else -> "other"
        }
    }
}

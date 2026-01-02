package com.yomidroid.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.io.FileOutputStream

/**
 * Separate SQLite database for the read-only dictionary.
 * This is NOT managed by Room - it's a pre-built database copied from assets.
 */
class DictionaryDb private constructor(context: Context) {

    companion object {
        private const val ASSET_DB_NAME = "dictionary.db"
        private const val DB_NAME = "dictionary.db" // Stored separately from Room's yomidroid_history.db
        private const val DB_VERSION = 3 // Increment when schema changes to force re-copy
        private const val PREFS_NAME = "dictionary_prefs"
        private const val PREF_DB_VERSION = "db_version"

        @Volatile
        private var INSTANCE: DictionaryDb? = null

        fun getInstance(context: Context): DictionaryDb {
            return INSTANCE ?: synchronized(this) {
                val instance = DictionaryDb(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        fun getDatabasePath(context: Context): File {
            return context.getDatabasePath(DB_NAME)
        }
    }

    private val database: SQLiteDatabase

    init {
        val dbPath = context.getDatabasePath(DB_NAME)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedVersion = prefs.getInt(PREF_DB_VERSION, 0)

        // Copy from assets if DB doesn't exist OR if version changed (schema update)
        if (!dbPath.exists() || storedVersion < DB_VERSION) {
            // Delete old database if it exists
            if (dbPath.exists()) {
                dbPath.delete()
            }
            copyDatabaseFromAssets(context, dbPath)
            // Store the new version
            prefs.edit().putInt(PREF_DB_VERSION, DB_VERSION).apply()
        }

        database = SQLiteDatabase.openDatabase(
            dbPath.path,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
    }

    private fun copyDatabaseFromAssets(context: Context, dbPath: File) {
        dbPath.parentFile?.mkdirs()

        context.assets.open(ASSET_DB_NAME).use { input ->
            FileOutputStream(dbPath).use { output ->
                input.copyTo(output)
            }
        }
    }

    /**
     * Find terms by expression or reading (exact match).
     * Returns results sorted by: frequency rank (if present), score, then names last.
     */
    fun findByExpressionOrReading(query: String): List<TermData> {
        val results = mutableListOf<TermData>()

        // Query with new columns, handling backwards compatibility
        database.rawQuery(
            """
            SELECT id, expression, reading, glossary, pos, score, sequence,
                   COALESCE(source, 'jitendex') as source,
                   name_type,
                   frequency_rank
            FROM terms
            WHERE expression = ? OR reading = ?
            ORDER BY
                CASE WHEN frequency_rank IS NOT NULL THEN 0 ELSE 1 END,
                frequency_rank ASC,
                score DESC,
                CASE WHEN source = 'jmnedict' THEN 1 ELSE 0 END
            LIMIT 30
            """.trimIndent(),
            arrayOf(query, query)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(
                    TermData(
                        id = cursor.getLong(0),
                        expression = cursor.getString(1),
                        reading = cursor.getString(2),
                        glossary = cursor.getString(3),
                        partsOfSpeech = cursor.getString(4) ?: "",
                        score = cursor.getInt(5),
                        sequence = cursor.getInt(6),
                        source = cursor.getString(7) ?: "jitendex",
                        nameType = cursor.getString(8),
                        frequencyRank = if (cursor.isNull(9)) null else cursor.getInt(9)
                    )
                )
            }
        }

        return results
    }

    /**
     * Get total count of entries.
     */
    fun count(): Int {
        database.rawQuery("SELECT COUNT(*) FROM terms", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /**
     * Check if database is loaded and valid.
     */
    fun isValid(): Boolean {
        return try {
            count() > 0
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        database.close()
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
    val source: String = "jitendex", // 'jitendex' | 'jmnedict'
    val nameType: String? = null, // For JMnedict: 'person' | 'place' | 'surname' | etc.
    val frequencyRank: Int? = null // VN frequency rank (1 = most common)
)

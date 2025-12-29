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
        private const val DB_VERSION = 1

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

        // Copy from assets if needed
        if (!dbPath.exists()) {
            copyDatabaseFromAssets(context, dbPath)
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
     */
    fun findByExpressionOrReading(query: String): List<TermData> {
        val results = mutableListOf<TermData>()

        database.rawQuery(
            "SELECT id, expression, reading, glossary, pos, score, sequence FROM terms WHERE expression = ? OR reading = ? ORDER BY score DESC LIMIT 20",
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
                        sequence = cursor.getInt(6)
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
    val sequence: Int
)

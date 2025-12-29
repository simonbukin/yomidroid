package com.yomidroid.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handles loading the dictionary database from assets on first launch.
 */
object DictionaryLoader {

    /**
     * Check if dictionary is available and can be loaded.
     * This triggers the copy from assets if needed.
     */
    suspend fun ensureDictionaryLoaded(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val db = DictionaryDb.getInstance(context)
            db.isValid()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Check if the bundled dictionary exists in assets.
     */
    fun hasBundledDictionary(context: Context): Boolean {
        return try {
            context.assets.open("dictionary.db").close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the path where the dictionary is stored.
     */
    fun getDictionaryPath(context: Context): File {
        return DictionaryDb.getDatabasePath(context)
    }
}

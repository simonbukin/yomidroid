package com.yomidroid.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles checking dictionary availability.
 */
object DictionaryLoader {

    /**
     * Check if any dictionary is available and loaded.
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
     * Check if any dictionaries are installed.
     */
    fun hasDictionaries(context: Context): Boolean {
        return try {
            DictionaryDb.getInstance(context).isValid()
        } catch (e: Exception) {
            false
        }
    }
}

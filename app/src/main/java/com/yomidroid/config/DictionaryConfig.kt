package com.yomidroid.config

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

enum class DictSourceType { DICTIONARY, FREQUENCY, NAMES, KANJI, PITCH }

data class InstalledDictionary(
    val id: String,
    val title: String,
    val revision: String = "",
    val type: DictSourceType,
    val dbFileName: String,
    val priority: Int,
    val enabled: Boolean,
    val entryCount: Int,
    val installedAt: Long,
    val isBundled: Boolean = false,
    val frequencyMode: String? = null
)

class DictionaryConfigManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "dictionary_config"
        private const val KEY_INSTALLED = "installed_dictionaries"
    }

    fun getInstalledDictionaries(): List<InstalledDictionary> {
        val json = prefs.getString(KEY_INSTALLED, null) ?: return emptyList()
        return try {
            // Detect old format (had "catalogId"/"name" instead of "id"/"title")
            if (json.contains("\"catalogId\"")) {
                prefs.edit().remove(KEY_INSTALLED).apply()
                return emptyList()
            }
            val type = object : TypeToken<List<InstalledDictionary>>() {}.type
            gson.fromJson<List<InstalledDictionary>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveInstalledDictionaries(list: List<InstalledDictionary>) {
        prefs.edit().putString(KEY_INSTALLED, gson.toJson(list)).apply()
    }

    fun addDictionary(dict: InstalledDictionary) {
        val list = getInstalledDictionaries().toMutableList()
        list.add(dict)
        saveInstalledDictionaries(list)
    }

    fun removeDictionary(id: String) {
        val list = getInstalledDictionaries().toMutableList()
        list.removeAll { it.id == id }
        saveInstalledDictionaries(list)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val list = getInstalledDictionaries().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            list[index] = list[index].copy(enabled = enabled)
            saveInstalledDictionaries(list)
        }
    }

    fun reorderDictionaries(orderedIds: List<String>) {
        val list = getInstalledDictionaries().toMutableList()
        val map = list.associateBy { it.id }.toMutableMap()
        val reordered = orderedIds.mapIndexedNotNull { index, id ->
            map[id]?.copy(priority = index)
        }
        saveInstalledDictionaries(reordered)
    }

    fun isInstalled(id: String): Boolean {
        return getInstalledDictionaries().any { it.id == id }
    }

    fun moveDictionaryUp(id: String) {
        val list = getInstalledDictionaries().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index > 0) {
            val item = list.removeAt(index)
            list.add(index - 1, item)
            saveInstalledDictionaries(list.mapIndexed { i, d -> d.copy(priority = i) })
        }
    }

    fun moveDictionaryDown(id: String) {
        val list = getInstalledDictionaries().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0 && index < list.size - 1) {
            val item = list.removeAt(index)
            list.add(index + 1, item)
            saveInstalledDictionaries(list.mapIndexed { i, d -> d.copy(priority = i) })
        }
    }

    /**
     * Get user custom CSS for dictionary rendering (WebView popups and Compose screens).
     */
    fun getCustomCss(): String? {
        return prefs.getString("custom_css", null)
    }

    /**
     * Set user custom CSS. Pass null to clear.
     */
    fun setCustomCss(css: String?) {
        if (css.isNullOrBlank()) {
            prefs.edit().remove("custom_css").apply()
        } else {
            prefs.edit().putString("custom_css", css).apply()
        }
    }

    /**
     * Get dictionary-shipped CSS (from styles.css in Yomitan ZIP).
     * @return CSS string or null if the dictionary has no custom styles
     */
    fun getDictionaryCss(dictId: String): String? {
        return prefs.getString("dict_css_$dictId", null)
    }

    /**
     * Store dictionary-shipped CSS extracted during import.
     */
    fun setDictionaryCss(dictId: String, css: String?) {
        if (css.isNullOrBlank()) {
            prefs.edit().remove("dict_css_$dictId").apply()
        } else {
            prefs.edit().putString("dict_css_$dictId", css).apply()
        }
    }

    /**
     * Get all dictionary CSS as a map of dictTitle → CSS for injection.
     */
    fun getAllDictionaryCss(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (dict in getInstalledDictionaries()) {
            if (!dict.enabled) continue
            val css = getDictionaryCss(dict.id)
            if (css != null) result[dict.title] = css
        }
        return result
    }
}

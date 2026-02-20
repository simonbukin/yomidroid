package com.yomidroid.dictionary

import android.content.Context
import android.content.res.Configuration
import com.yomidroid.data.TagMeta
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared serializer that converts DictionaryEntry lists to the JSON format
 * consumed by popup.js. Used by both the overlay popup and in-app screens.
 */
object EntrySerializer {

    /**
     * Serialize a list of entries to the JSON payload expected by popup.js `setEntries()`.
     *
     * @param context  Android context (for theme detection and file paths)
     * @param entries  Dictionary entries to serialize
     * @param customCss  Optional user custom CSS
     * @param dictionaryCssMap  Dictionary-scoped CSS (dictTitle → CSS string)
     */
    fun serialize(
        context: Context,
        entries: List<DictionaryEntry>,
        customCss: String? = null,
        dictionaryCssMap: Map<String, String> = emptyMap()
    ): String {
        val root = JSONObject()

        val nightMode = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK
        val theme = if (nightMode == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
        root.put("theme", theme)

        if (customCss != null) root.put("customCss", customCss)

        val dictCssObj = JSONObject()
        for ((title, css) in dictionaryCssMap) {
            dictCssObj.put(title, css)
        }
        root.put("dictionaryCss", dictCssObj)

        val arr = JSONArray()
        for (entry in entries) {
            arr.put(serializeEntry(entry))
        }

        val imageBaseDir = java.io.File(context.filesDir, "dictionaries")
        root.put("imageBasePath", "file://${imageBaseDir.absolutePath}")
        root.put("entries", arr)
        return root.toString()
    }

    /**
     * Serialize a single entry to a JSONObject.
     */
    fun serializeEntry(entry: DictionaryEntry): JSONObject {
        val obj = JSONObject()
        obj.put("expression", entry.expression)
        obj.put("reading", entry.reading)
        obj.put("glossary", JSONArray(entry.glossary))
        if (entry.glossaryRich != null) obj.put("glossaryRich", entry.glossaryRich)
        obj.put("deinflectionPath", entry.deinflectionPath)
        obj.put("sourceLabel", entry.sourceLabel)
        obj.put("sourceDictId", entry.sourceDictId)
        obj.put("dictionaryTitle", entry.dictionaryTitle.ifEmpty { entry.sourceLabel })

        // Tags
        entry.posDisplayLabel?.let { obj.put("posDisplayLabel", it) }
        entry.frequencyBadge?.let { obj.put("frequencyBadge", it) }
        obj.put("frequencyBadgeColor", colorIntToHex(entry.frequencyBadgeColor))
        entry.jpdbBadge?.let { obj.put("jpdbBadge", it) }
        obj.put("jpdbBadgeColor", colorIntToHex(entry.jpdbBadgeColor))
        entry.nameTypeLabel?.let { obj.put("nameTypeLabel", it) }

        // Pitch accent
        entry.pitchDownstep?.let { obj.put("pitchDownstep", it) }
        if (entry.pitchDownsteps.isNotEmpty()) {
            obj.put("pitchDownsteps", JSONArray(entry.pitchDownsteps))
        }

        // Definition tags with metadata
        if (entry.definitionTags.isNotEmpty()) {
            val tagsArr = JSONArray()
            for (tag in entry.definitionTags) {
                val tagObj = JSONObject()
                tagObj.put("name", tag)
                val meta = entry.tagMeta[tag]
                if (meta != null) {
                    tagObj.put("category", meta.category)
                    if (meta.notes.isNotEmpty()) tagObj.put("notes", meta.notes)
                }
                tagsArr.put(tagObj)
            }
            obj.put("definitionTags", tagsArr)
        }

        return obj
    }

    private fun colorIntToHex(color: Int): String {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return "#%02x%02x%02x".format(r, g, b)
    }
}

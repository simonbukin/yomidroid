package com.yomidroid.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Extracts plain-text glossaries and detects structured-content from a Yomitan
 * definitions array.
 *
 * Ported from the (previously private) logic in [YomitanConverter] so the
 * Hoshidicts query path can split a raw Yomitan definitions array into the same
 * `glossary` (plain strings) + `glossaryRich` (raw structured-content JSON) pair
 * the SQLite import produced — keeping popup rendering identical.
 */
object GlossaryExtractor {

    /** Result of splitting a raw definitions array. */
    data class Split(val glossary: List<String>, val glossaryRich: String?)

    /**
     * Split a raw Yomitan definitions array (as a JSON string) into plain-text
     * glossaries plus the raw structured-content JSON (when any definition uses
     * structured content).
     */
    fun split(definitionsJson: String): Split {
        if (definitionsJson.isBlank()) return Split(emptyList(), null)
        return try {
            val arr = JSONArray(definitionsJson)
            val glossary = extractGlossary(arr)
            val rich = if (hasStructuredContent(arr)) definitionsJson else null
            Split(glossary, rich)
        } catch (e: Exception) {
            // Not an array — treat as a single plain string definition.
            Split(listOf(definitionsJson), null)
        }
    }

    fun hasStructuredContent(definitions: JSONArray): Boolean {
        for (i in 0 until definitions.length()) {
            if (definitions.opt(i) is JSONObject) return true
        }
        return false
    }

    fun extractGlossary(definitions: Any): List<String> {
        val glossary = mutableListOf<String>()
        when (definitions) {
            is String -> if (definitions.isNotBlank()) glossary.add(definitions)
            is JSONArray -> for (i in 0 until definitions.length()) {
                val text = extractTextFromContent(definitions.get(i))
                if (text.isNotBlank()) glossary.add(text.trim())
            }
        }
        return glossary
    }

    private fun extractTextFromContent(content: Any?): String {
        if (content == null) return ""
        if (content is String) return content
        if (content is JSONObject) {
            when (content.optString("type", "")) {
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
            for (i in 0 until node.length()) findGlossaries(node.get(i), glosses)
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
}

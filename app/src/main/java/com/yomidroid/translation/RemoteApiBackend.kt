package com.yomidroid.translation

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Translation backend using OpenAI-compatible API.
 *
 * Works with:
 * - Ollama: http://host:11434/v1
 * - llama.cpp server: http://host:8080/v1
 * - LM Studio: http://host:1234/v1
 * - Any OpenAI-compatible endpoint
 *
 * Recommended models for Japanese VN translation:
 * - vntl-llama3-8b-v2 (best balance)
 * - vntl-gemma2-27b (highest quality)
 */
class RemoteApiBackend(
    private val context: Context
) : RemoteBackend {

    override val name = "Remote API"
    override val priority = 100  // Highest priority

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("translation_settings", Context.MODE_PRIVATE)
    }

    private val gson = Gson()

    override var endpoint: String
        get() = prefs.getString(PREF_ENDPOINT, DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT
        set(value) = prefs.edit().putString(PREF_ENDPOINT, value).apply()

    override var modelName: String
        get() = prefs.getString(PREF_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(PREF_MODEL, value).apply()

    var apiKey: String
        get() = prefs.getString(PREF_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(PREF_API_KEY, value).apply()

    var enabled: Boolean
        get() = prefs.getBoolean(PREF_ENABLED, false)
        set(value) = prefs.edit().putBoolean(PREF_ENABLED, value).apply()

    var timeoutMs: Int
        get() = prefs.getInt(PREF_TIMEOUT, DEFAULT_TIMEOUT_MS)
        set(value) = prefs.edit().putInt(PREF_TIMEOUT, value).apply()

    override suspend fun getStatus(): BackendStatus = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext BackendStatus.Unavailable

        try {
            if (testConnection()) {
                BackendStatus.Available
            } else {
                BackendStatus.Error("Connection failed")
            }
        } catch (e: Exception) {
            BackendStatus.Error(e.message ?: "Unknown error")
        }
    }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Use a minimal chat completion instead of /models
            // because Gemini's OpenAI-compat /models endpoint returns 401
            val result = callApi(null, "Reply with OK")
            result != null
        } catch (e: Exception) {
            Log.e("TranslationParse", "testConnection failed", e)
            false
        }
    }

    override fun supportedModes(): Set<TranslationMode> = setOf(
        TranslationMode.NATURAL,
        TranslationMode.LITERAL,
        TranslationMode.INTERLINEAR
    )

    override suspend fun translate(request: TranslationRequest): TranslationResult? =
        withContext(Dispatchers.IO) {
            if (!enabled) return@withContext null
            val (systemPrompt, userPrompt) = buildPrompt(request)
            val response = callApi(systemPrompt, userPrompt, useJsonMode = true) ?: return@withContext null
            parseResponse(request.text, response)
        }

    private fun buildPrompt(request: TranslationRequest): Pair<String?, String> {
        val wantNatural = TranslationMode.NATURAL in request.modes
        val wantLiteral = TranslationMode.LITERAL in request.modes

        val systemPrompt = "You are a Japanese language tutor helping a learner play a Japanese game. Translate accurately and explain key grammar."

        return when {
            wantNatural && wantLiteral -> systemPrompt to """Translate this Japanese to English. Respond with a JSON object containing:
- "natural": fluent English translation
- "literal": word-order-preserving translation with (implied words) in parentheses
- "notes": array of 1-3 strings about key grammar, nuance, or cultural context (only non-obvious points)

Japanese: ${request.text}"""

            wantLiteral -> null to """Translate this Japanese literally. Respond with a JSON object containing:
- "literal": word-order-preserving translation with (implied words) in parentheses

Japanese: ${request.text}"""

            else -> systemPrompt to """Translate this Japanese to English. Respond with a JSON object containing:
- "natural": fluent English translation
- "literal": word-order-preserving translation with (implied words) in parentheses
- "notes": array of 1-3 strings about key grammar, nuance, or cultural context (only non-obvious points)

Japanese: ${request.text}"""
        }
    }

    private fun callApi(systemPrompt: String?, userPrompt: String, useJsonMode: Boolean = false): String? {
        val url = URL("${endpoint.trimEnd('/')}/chat/completions")
        val conn = url.openConnection() as HttpURLConnection

        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        if (apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
        }
        conn.doOutput = true
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs

        val messages = buildList {
            if (systemPrompt != null) {
                add(ChatMessage(role = "system", content = systemPrompt))
            }
            add(ChatMessage(role = "user", content = userPrompt))
        }

        val requestBody = ChatRequest(
            model = modelName,
            messages = messages,
            temperature = 0.3,
            maxTokens = 500,
            responseFormat = if (useJsonMode) ResponseFormat("json_object") else null
        )

        conn.outputStream.use { os ->
            os.write(gson.toJson(requestBody).toByteArray())
        }

        if (conn.responseCode != 200) {
            val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            throw Exception("API error ${conn.responseCode}: $errorBody")
        }

        val response = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val chatResponse = gson.fromJson(response, ChatResponse::class.java)
        return chatResponse.choices.firstOrNull()?.message?.content
    }

    private fun parseResponse(originalText: String, response: String): TranslationResult {
        // Strip markdown code fences if present (e.g. ```json ... ```)
        val jsonCandidate = response.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        // Try JSON parse using JsonObject (avoids Gson data class reflection issues on Android)
        Log.d("TranslationParse", "Raw response: $response")
        Log.d("TranslationParse", "JSON candidate: $jsonCandidate")
        try {
            val obj = JsonParser.parseString(sanitizeJsonNewlines(jsonCandidate)).asJsonObject
            val natural = obj.get("natural")?.takeIf { !it.isJsonNull }?.asString
            val literal = obj.get("literal")?.takeIf { !it.isJsonNull }?.asString
            val notes = obj.get("notes")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.map { it.asString }
            Log.d("TranslationParse", "Parsed: natural=$natural, literal=$literal, notes=$notes")
            if (natural != null || literal != null) {
                return TranslationResult(
                    originalText = originalText,
                    natural = natural,
                    literal = literal,
                    interlinear = null,
                    backend = "$name ($modelName)",
                    notes = notes?.joinToString("\n") { "• $it" }
                )
            }
        } catch (e: Exception) {
            Log.e("TranslationParse", "JSON parse failed", e)
        }

        // Fall back to text parsing for backends that ignore JSON mode
        var natural: String? = null
        var literal: String? = null
        var notes: String? = null

        val lines = response.lines()
        var inNotes = false
        val notesLines = mutableListOf<String>()

        for (line in lines) {
            when {
                line.startsWith("Natural:", ignoreCase = true) -> {
                    inNotes = false
                    natural = line.substringAfter(":").trim().removeSurrounding("\"")
                }
                line.startsWith("Literal:", ignoreCase = true) -> {
                    inNotes = false
                    literal = line.substringAfter(":").trim().removeSurrounding("\"")
                }
                line.startsWith("Translation:", ignoreCase = true) -> {
                    inNotes = false
                    natural = line.substringAfter(":").trim().removeSurrounding("\"")
                }
                line.startsWith("Notes:", ignoreCase = true) -> {
                    inNotes = true
                    val rest = line.substringAfter(":").trim()
                    if (rest.isNotBlank()) notesLines.add(rest)
                }
                inNotes && line.isNotBlank() -> {
                    notesLines.add(line.trim())
                }
            }
        }

        if (notesLines.isNotEmpty()) {
            notes = notesLines.joinToString("\n")
        }

        // If parsing failed, try to extract just the English part
        if (natural == null && literal == null) {
            val cleaned = response.lines()
                .filterNot { line ->
                    line.any { c -> c in '\u3040'..'\u30FF' || c in '\u4E00'..'\u9FFF' }
                }
                .joinToString(" ")
                .trim()

            natural = cleaned.ifBlank { response.trim() }
        }

        return TranslationResult(
            originalText = originalText,
            natural = natural,
            literal = literal,
            interlinear = null,
            backend = "$name ($modelName)",
            notes = notes
        )
    }

    /** Escape literal newlines/carriage-returns inside JSON string values so Gson can parse them. */
    private fun sanitizeJsonNewlines(raw: String): String {
        val sb = StringBuilder(raw.length)
        var inString = false
        var escape = false
        for (c in raw) {
            if (escape) { sb.append(c); escape = false; continue }
            if (c == '\\') { sb.append(c); escape = true; continue }
            if (c == '"') { inString = !inString; sb.append(c); continue }
            if (inString && c == '\n') { sb.append("\\n"); continue }
            if (inString && c == '\r') { sb.append("\\r"); continue }
            sb.append(c)
        }
        return sb.toString()
    }

    companion object {
        private const val PREF_ENDPOINT = "remote_api_endpoint"
        private const val PREF_MODEL = "remote_api_model"
        private const val PREF_API_KEY = "remote_api_key"
        private const val PREF_ENABLED = "remote_api_enabled"
        private const val PREF_TIMEOUT = "remote_api_timeout"

        private const val DEFAULT_ENDPOINT = "http://localhost:11434/v1"
        private const val DEFAULT_MODEL = "vntl-llama3-8b-v2"
        private const val DEFAULT_TIMEOUT_MS = 120000  // 2 minutes for slow local models
    }
}

// OpenAI API data classes
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    @SerializedName("max_tokens") val maxTokens: Int = 500,
    @SerializedName("response_format") val responseFormat: ResponseFormat? = null
)

private data class ResponseFormat(val type: String)

private data class ChatMessage(
    val role: String,
    val content: String
)

private data class ChatResponse(
    val choices: List<ChatChoice>
)

private data class ChatChoice(
    val message: ChatMessage
)

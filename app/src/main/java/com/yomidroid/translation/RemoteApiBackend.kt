package com.yomidroid.translation

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            val url = URL("${endpoint.trimEnd('/')}/models")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val responseCode = conn.responseCode
            conn.disconnect()

            responseCode == 200
        } catch (e: Exception) {
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

            try {
                val prompt = buildPrompt(request)
                val response = callApi(prompt) ?: return@withContext null

                parseResponse(request.text, response)
            } catch (e: Exception) {
                null
            }
        }

    private fun buildPrompt(request: TranslationRequest): String {
        val wantNatural = TranslationMode.NATURAL in request.modes
        val wantLiteral = TranslationMode.LITERAL in request.modes

        return when {
            wantNatural && wantLiteral -> """Translate this Japanese to English. Provide two versions:
1. Natural: fluent, idiomatic English
2. Literal: preserve Japanese word order, use (parentheses) for implied words

Japanese: ${request.text}

Format:
Natural: [translation]
Literal: [translation]"""

            wantLiteral -> """Translate this Japanese to English literally.
Preserve the original word order and structure. Use (parentheses) for implied subjects/objects.
Output only the translation, nothing else.

Japanese: ${request.text}"""

            else -> """Translate this Japanese to English naturally and fluently.
Output only the translation, nothing else.

Japanese: ${request.text}"""
        }
    }

    private fun callApi(prompt: String): String? {
        val url = URL("${endpoint.trimEnd('/')}/chat/completions")
        val conn = url.openConnection() as HttpURLConnection

        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs

        val requestBody = ChatRequest(
            model = modelName,
            messages = listOf(
                ChatMessage(role = "user", content = prompt)
            ),
            temperature = 0.3,
            maxTokens = 500
        )

        conn.outputStream.use { os ->
            os.write(gson.toJson(requestBody).toByteArray())
        }

        if (conn.responseCode != 200) {
            conn.disconnect()
            return null
        }

        val response = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val chatResponse = gson.fromJson(response, ChatResponse::class.java)
        return chatResponse.choices.firstOrNull()?.message?.content
    }

    private fun parseResponse(originalText: String, response: String): TranslationResult {
        var natural: String? = null
        var literal: String? = null

        // Parse "Natural: ...", "Literal: ...", or "Translation: ..." lines
        val lines = response.lines()
        for (line in lines) {
            when {
                line.startsWith("Natural:", ignoreCase = true) -> {
                    natural = line.substringAfter(":").trim().removeSurrounding("\"")
                }
                line.startsWith("Literal:", ignoreCase = true) -> {
                    literal = line.substringAfter(":").trim().removeSurrounding("\"")
                }
                line.startsWith("Translation:", ignoreCase = true) -> {
                    // Generic "Translation:" maps to natural
                    natural = line.substringAfter(":").trim().removeSurrounding("\"")
                }
            }
        }

        // If parsing failed, try to extract just the English part
        if (natural == null && literal == null) {
            // Remove any Japanese text that was echoed back
            val cleaned = response.lines()
                .filterNot { line ->
                    // Skip lines that are mostly Japanese (hiragana, katakana, kanji)
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
            backend = "$name ($modelName)"
        )
    }

    companion object {
        private const val PREF_ENDPOINT = "remote_api_endpoint"
        private const val PREF_MODEL = "remote_api_model"
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
    @SerializedName("max_tokens") val maxTokens: Int = 500
)

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

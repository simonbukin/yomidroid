package com.yomidroid.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * OCR via Gemini's OpenAI-compatible vision endpoint. Reuses the same
 * SharedPreferences (`translation_settings`) the translation backend writes
 * — endpoint + API key — so configuring once gets you both.
 *
 * Approach: send the bitmap inline as base64 JPEG with a strict JSON-mode
 * prompt asking for one line of Japanese text per output array entry.
 * Bounding boxes are synthesized by splitting the image height evenly across
 * the returned lines (Gemini doesn't reliably produce pixel-accurate boxes).
 *
 * Approximate boxes are fine for the AYN Thor's decoupled-mode workflow
 * (Now → Lookup tab); for coupled mode, the popup will appear in roughly the
 * right place even without exact per-glyph bounds.
 */
class GeminiFlashOcrEngine(
    context: Context,
    private val useMlKitBounds: Boolean = true
) : OcrEngine {

    override val engineId = "gemini_flash"
    override val displayName = "Gemini Flash"
    override val requiresNetwork = true

    private val prefs = context.getSharedPreferences("translation_settings", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val mlKit by lazy { MlKitOcrEngine() }

    private val endpoint: String
        get() = prefs.getString(PREF_ENDPOINT, "") ?: ""
    private val apiKey: String
        get() = prefs.getString(PREF_API_KEY, "") ?: ""
    private val timeoutMs: Int
        get() = prefs.getInt(PREF_TIMEOUT, 30000).coerceAtLeast(10000)

    /** Fixed to a vision-capable Gemini model regardless of the translation
     *  model setting (which might be a text-only model on a different host). */
    private val ocrModel: String = "gemini-2.5-flash"

    companion object {
        private const val TAG = "GeminiFlashOcr"
        private const val PREF_ENDPOINT = "remote_api_endpoint"
        private const val PREF_API_KEY = "remote_api_key"
        private const val PREF_TIMEOUT = "remote_api_timeout"

        // Cap image side before sending to keep latency + token cost down. OCR
        // accuracy plateaus well before 4K — 1600 px short side reads game text
        // cleanly.
        private const val MAX_SIDE_PX = 1600
    }

    override suspend fun processImage(bitmap: Bitmap): Result<List<OcrResult>> {
        if (apiKey.isBlank() || endpoint.isBlank()) {
            return Result.failure(
                IllegalStateException(
                    "Gemini OCR needs the translation endpoint + API key. " +
                        "Settings → Translation → Remote API."
                )
            )
        }

        return try {
            val totalStart = SystemClock.elapsedRealtime()

            // Run ML Kit (for line bounds) and Gemini (for text) in parallel
            // when hybrid bounds are enabled. ML Kit is fast (~100ms) so it
            // rarely extends total latency beyond the network call.
            val (lines, mlKitResults) = coroutineScope {
                val mlDeferred = if (useMlKitBounds) async(Dispatchers.Default) {
                    mlKit.processImage(bitmap).getOrNull().orEmpty()
                } else null

                val geminiDeferred = async(Dispatchers.IO) {
                    val payload = downscaleAndEncodeJpeg(bitmap)
                    val text = callVision(payload)
                    parseLines(text) to payload.length
                }

                val (geminiLines, payloadSize) = geminiDeferred.await()
                val mlResults = mlDeferred?.await() ?: emptyList()
                Log.d(TAG, "gemini lines=${geminiLines.size} mlkit lines=${mlResults.size} bytes=$payloadSize")
                geminiLines to mlResults
            }

            val results = if (useMlKitBounds && mlKitResults.isNotEmpty()) {
                pairWithMlKitBounds(lines, mlKitResults, bitmap)
            } else {
                synthesizeResults(bitmap, lines)
            }

            val totalMs = SystemClock.elapsedRealtime() - totalStart
            Log.d(TAG, "total ms=$totalMs lines=${results.size} hybrid=${useMlKitBounds && mlKitResults.isNotEmpty()}")
            Result.success(results)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini OCR failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Pair Gemini's text lines with ML Kit's line bounds in reading order.
     *
     * Top-to-bottom is the same in both, so a positional zip works for the
     * common case. When line counts disagree we still emit Gemini text — the
     * model is the source of truth — but the leftover lines fall back to a
     * synthesized strip box. Better than dropping accurate text on the floor.
     */
    private fun pairWithMlKitBounds(
        geminiLines: List<String>,
        mlKitResults: List<OcrResult>,
        bitmap: Bitmap
    ): List<OcrResult> {
        // Sort ML Kit lines top-to-bottom (ML Kit usually returns this way
        // already, but be explicit since pairing depends on it).
        val sortedBounds = mlKitResults.sortedBy { it.lineBounds.top }

        val out = mutableListOf<OcrResult>()
        val pairCount = minOf(geminiLines.size, sortedBounds.size)
        for (i in 0 until pairCount) {
            val text = geminiLines[i]
            val bounds = sortedBounds[i].lineBounds
            out.add(OcrResult(text, bounds, OcrUtils.synthesizeCharBounds(text, bounds)))
        }
        // Extra Gemini lines (Gemini saw text ML Kit missed) — synthesize a
        // strip below the last known bounds so the cursor still has something
        // to land on.
        if (geminiLines.size > sortedBounds.size) {
            val extras = geminiLines.drop(pairCount)
            val startY = sortedBounds.lastOrNull()?.lineBounds?.bottom ?: 0
            val remainingHeight = (bitmap.height - startY).coerceAtLeast(extras.size * 40)
            val per = remainingHeight / extras.size
            extras.forEachIndexed { idx, text ->
                val top = startY + idx * per
                val bottom = if (idx == extras.size - 1) bitmap.height else top + per
                val bounds = Rect(0, top, bitmap.width, bottom)
                out.add(OcrResult(text, bounds, OcrUtils.synthesizeCharBounds(text, bounds)))
            }
        }
        return out
    }

    private fun downscaleAndEncodeJpeg(bitmap: Bitmap): String {
        val short = minOf(bitmap.width, bitmap.height).toFloat()
        val scale = if (short > MAX_SIDE_PX) MAX_SIDE_PX / short else 1f
        val source = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true
            )
        } else bitmap

        val baos = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        if (source !== bitmap) source.recycle()
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun callVision(b64Jpeg: String): String {
        val url = URL("${endpoint.trimEnd('/')}/chat/completions")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }

        val body = VisionRequest(
            model = ocrModel,
            messages = listOf(
                VisionMessage(
                    role = "user",
                    content = listOf(
                        VisionPart(type = "text", text = OCR_PROMPT),
                        VisionPart(
                            type = "image_url",
                            image_url = ImageUrl(url = "data:image/jpeg;base64,$b64Jpeg")
                        )
                    )
                )
            ),
            temperature = 0.0,
            maxTokens = 1500,
            responseFormat = ResponseFormat("json_object")
        )

        try {
            conn.outputStream.use { it.write(gson.toJson(body).toByteArray()) }
            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw RuntimeException("Gemini ${conn.responseCode}: ${err.take(400)}")
            }
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val parsed = gson.fromJson(response, ChatResponse::class.java)
            return parsed.choices.firstOrNull()?.message?.content
                ?: throw RuntimeException("Empty Gemini response")
        } finally {
            conn.disconnect()
        }
    }

    private fun parseLines(content: String): List<String> {
        val candidate = content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()

        return try {
            val obj = JsonParser.parseString(candidate).asJsonObject
            val arr = obj.getAsJsonArray("lines") ?: return listOf(candidate)
            arr.mapNotNull { el ->
                if (el.isJsonNull) null
                else if (el.isJsonPrimitive) el.asString.takeIf { it.isNotBlank() }
                else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "non-JSON response, falling back to whole text: ${e.message}")
            // Last-resort fallback: split on newlines.
            candidate.lines().map { it.trim() }.filter { it.isNotBlank() }
        }
    }

    /**
     * Map line texts to OcrResults with bounds synthesized as evenly-spaced
     * horizontal strips covering the full bitmap. Yes, approximate. Real game
     * dialogue boxes are usually evenly stacked anyway, and the user's main
     * workflow is decoupled mode (Now → Lookup tab) where bounds don't matter.
     */
    private fun synthesizeResults(bitmap: Bitmap, lines: List<String>): List<OcrResult> {
        if (lines.isEmpty()) return emptyList()
        val w = bitmap.width
        val h = bitmap.height
        val perLine = h / lines.size
        return lines.mapIndexed { i, text ->
            val top = i * perLine
            val bottom = if (i == lines.size - 1) h else (i + 1) * perLine
            val lineBounds = Rect(0, top, w, bottom)
            OcrResult(text, lineBounds, OcrUtils.synthesizeCharBounds(text, lineBounds))
        }
    }

    override fun close() {
        if (useMlKitBounds) mlKit.close()
    }
}

private const val OCR_PROMPT = """You are an OCR engine for Japanese video-game screenshots.

Extract every visible Japanese text line from the image, in natural reading order (top-to-bottom, then right-to-left for vertical text). Preserve original characters exactly: kanji, hiragana, katakana, fullwidth punctuation, dialogue markers like ・・・ or ▼.

Do NOT translate. Do NOT romanize. Do NOT include UI labels in non-Japanese languages. Do NOT include the user's HUD/menu text unless it's part of the dialogue.

Respond with strict JSON only, no commentary:
{"lines": ["line 1", "line 2", ...]}

If there is no Japanese text, return {"lines": []}."""

// ── OpenAI-compatible vision request DTOs ──────────────────────────────────

private data class VisionRequest(
    val model: String,
    val messages: List<VisionMessage>,
    val temperature: Double,
    @SerializedName("max_tokens") val maxTokens: Int,
    @SerializedName("response_format") val responseFormat: ResponseFormat
)

private data class VisionMessage(
    val role: String,
    val content: List<VisionPart>
)

private data class VisionPart(
    val type: String,
    val text: String? = null,
    @SerializedName("image_url") val image_url: ImageUrl? = null
)

private data class ImageUrl(val url: String)

private data class ResponseFormat(val type: String)

private data class ChatResponse(val choices: List<ChatChoice>)
private data class ChatChoice(val message: ChatMessageOut)
private data class ChatMessageOut(val content: String)

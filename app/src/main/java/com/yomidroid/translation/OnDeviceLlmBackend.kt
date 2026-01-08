package com.yomidroid.translation

import android.content.Context
import android.content.SharedPreferences
import com.yomidroid.llm.LlamaCpp
import com.yomidroid.llm.ModelDownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-device LLM translation backend using llama.cpp.
 *
 * Uses gemma-2-2b-jpn-it-translate model (1.71 GB Q4_K_M quantization)
 * for high-quality Japanese to English translation on device.
 *
 * Expected performance on Snapdragon 8s Gen 3:
 * - 8-15 tokens/second
 * - Typical sentence: 2-6 seconds
 */
class OnDeviceLlmBackend(
    private val context: Context
) : DownloadableBackend {

    override val name = "On-Device LLM"
    override val priority = 50  // Between Remote API (100) and ML Kit (10)

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("translation_settings", Context.MODE_PRIVATE)
    }

    private val downloadManager = ModelDownloadManager.getInstance(context)
    private var llamaCpp: LlamaCpp? = null
    private var isModelLoadedInMemory = false

    var enabled: Boolean
        get() = prefs.getBoolean(PREF_ENABLED, false)
        set(value) = prefs.edit().putBoolean(PREF_ENABLED, value).apply()

    override val modelSizeBytes: Long
        get() = downloadManager.getModelSizeBytes()

    override suspend fun isModelDownloaded(): Boolean = withContext(Dispatchers.IO) {
        downloadManager.isModelDownloaded()
    }

    override suspend fun downloadModel(onProgress: (Float) -> Unit): Boolean {
        return downloadManager.downloadModel(onProgress)
    }

    override suspend fun deleteModel(): Boolean = withContext(Dispatchers.IO) {
        unloadModel()
        downloadManager.deleteModel()
    }

    override suspend fun getStatus(): BackendStatus = withContext(Dispatchers.IO) {
        when {
            !enabled -> BackendStatus.Unavailable
            !downloadManager.isModelDownloaded() -> BackendStatus.Unavailable
            else -> BackendStatus.Available
        }
    }

    override fun supportedModes(): Set<TranslationMode> = setOf(
        TranslationMode.NATURAL,
        TranslationMode.LITERAL
    )

    override suspend fun translate(request: TranslationRequest): TranslationResult? =
        withContext(Dispatchers.IO) {
            if (!enabled || !downloadManager.isModelDownloaded()) {
                return@withContext null
            }

            try {
                // Ensure model is loaded
                if (!ensureModelLoaded()) {
                    return@withContext null
                }

                val llm = llamaCpp ?: return@withContext null

                // Get natural translation if requested
                val natural = if (TranslationMode.NATURAL in request.modes) {
                    llm.translateNatural(request.text)
                } else null

                // Get literal translation if requested
                val literal = if (TranslationMode.LITERAL in request.modes) {
                    llm.translateLiteral(request.text)
                } else null

                // Return null only if both failed
                if (natural == null && literal == null) {
                    return@withContext null
                }

                TranslationResult(
                    originalText = request.text,
                    natural = natural,
                    literal = literal,
                    interlinear = null,
                    backend = name
                )
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    /**
     * Ensure the model is loaded into memory.
     * Loads on first translation request.
     */
    private suspend fun ensureModelLoaded(): Boolean = withContext(Dispatchers.IO) {
        if (isModelLoadedInMemory && llamaCpp?.isModelLoaded() == true) {
            return@withContext true
        }

        try {
            val llm = LlamaCpp.getInstance()
            val modelPath = downloadManager.getModelPath()

            if (!llm.isModelLoaded()) {
                val success = llm.loadModelAsync(modelPath)
                if (!success) {
                    return@withContext false
                }
            }

            llamaCpp = llm
            isModelLoadedInMemory = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Unload the model from memory to free RAM.
     */
    fun unloadModel() {
        try {
            llamaCpp?.unloadModel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        llamaCpp = null
        isModelLoadedInMemory = false
    }

    override fun shutdown() {
        unloadModel()
        try {
            LlamaCpp.getInstance().shutdown()
        } catch (e: Exception) {
            // Ignore - may not be initialized
        }
    }

    companion object {
        private const val PREF_ENABLED = "ondevice_llm_enabled"
    }
}

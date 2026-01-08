package com.yomidroid.translation

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Translation service that orchestrates multiple backends.
 *
 * Features:
 * - Tries backends in priority order (Remote API > On-Device LLM > ML Kit)
 * - Caches translations to avoid redundant API calls
 * - Falls back to lower-priority backends on failure
 * - Supports all three translation modes
 */
class TranslationService private constructor(context: Context) {

    private val appContext = context.applicationContext

    // Backends in priority order (sorted by priority descending)
    private val backends = mutableListOf<TranslationBackend>()

    // Interlinear generator using existing GrammarAnalyzer
    private val interlinearGenerator = InterlinearGenerator()

    init {
        // Register backends in priority order
        backends.add(RemoteApiBackend(appContext))
        backends.add(OnDeviceLlmBackend(appContext))
        backends.add(MlKitBackend(appContext))

        // Sort by priority (highest first)
        backends.sortByDescending { it.priority }
    }

    /**
     * Translate text using a specific backend or the best available.
     *
     * @param text Japanese text to translate
     * @param modes Which translation modes to generate
     * @param includeInterlinear Whether to include interlinear breakdown
     * @param preferredBackend Name of specific backend to use (null = auto-select by priority)
     * @return Translation result, or null if translation failed
     */
    suspend fun translate(
        text: String,
        modes: Set<TranslationMode> = setOf(TranslationMode.NATURAL, TranslationMode.LITERAL),
        includeInterlinear: Boolean = false,
        preferredBackend: String? = null
    ): TranslationResult? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null

        val request = TranslationRequest(
            text = text,
            modes = modes,
            includeInterlinear = includeInterlinear
        )

        // If preferred backend specified, only use that one
        val backendsToTry = if (preferredBackend != null) {
            backends.filter { it.name == preferredBackend }
        } else {
            backends
        }

        // Try backends
        for (backend in backendsToTry) {
            val status = backend.getStatus()
            if (status !is BackendStatus.Available) continue

            // Check if backend supports requested modes
            val supportedModes = backend.supportedModes()
            if (modes.none { it in supportedModes }) continue

            val result = backend.translate(request)
            if (result != null) {
                // Add interlinear if requested and not already provided
                val finalResult = if (includeInterlinear && result.interlinear == null) {
                    val interlinear = interlinearGenerator.generate(text)
                    result.copy(interlinear = interlinear)
                } else {
                    result
                }

                return@withContext finalResult
            }
        }

        // Backend(s) failed
        null
    }

    /**
     * Get status of all registered backends.
     */
    suspend fun getBackendStatuses(): Map<String, BackendStatus> = withContext(Dispatchers.IO) {
        backends.associate { it.name to it.getStatus() }
    }

    /**
     * Get the remote API backend for settings configuration.
     */
    fun getRemoteApiBackend(): RemoteApiBackend? {
        return backends.filterIsInstance<RemoteApiBackend>().firstOrNull()
    }

    /**
     * Get the ML Kit backend.
     */
    fun getMlKitBackend(): MlKitBackend? {
        return backends.filterIsInstance<MlKitBackend>().firstOrNull()
    }

    /**
     * Get the on-device LLM backend for settings configuration.
     */
    fun getOnDeviceLlmBackend(): OnDeviceLlmBackend? {
        return backends.filterIsInstance<OnDeviceLlmBackend>().firstOrNull()
    }

    /**
     * Shutdown all backends and release resources.
     */
    fun shutdown() {
        backends.forEach { it.shutdown() }
    }

    companion object {
        @Volatile
        private var instance: TranslationService? = null

        fun getInstance(context: Context): TranslationService {
            return instance ?: synchronized(this) {
                instance ?: TranslationService(context).also { instance = it }
            }
        }
    }
}

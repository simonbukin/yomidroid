package com.yomidroid.translation

/**
 * Interface for translation backends.
 *
 * Implementations include:
 * - RemoteApiBackend: OpenAI-compatible API (Ollama, llama.cpp, LM Studio)
 * - OnDeviceLlmBackend: Local LLM via llama.cpp or MediaPipe
 * - MlKitBackend: Google ML Kit Translation (fast fallback)
 */
interface TranslationBackend {

    /** Human-readable name for this backend */
    val name: String

    /** Priority (higher = preferred). Used for automatic fallback ordering. */
    val priority: Int

    /** Check if this backend is currently available for translation */
    suspend fun getStatus(): BackendStatus

    /**
     * Translate text using this backend.
     *
     * @param request Translation request with text and desired modes
     * @return Translation result, or null if translation failed
     */
    suspend fun translate(request: TranslationRequest): TranslationResult?

    /**
     * Check if this backend supports generating specific translation modes.
     * ML Kit only supports NATURAL, while LLM backends support all modes.
     */
    fun supportedModes(): Set<TranslationMode>

    /**
     * Perform any cleanup when the backend is no longer needed.
     */
    fun shutdown() {}
}

/**
 * Backend that requires downloading a model before use.
 */
interface DownloadableBackend : TranslationBackend {

    /** Size of the model in bytes */
    val modelSizeBytes: Long

    /** Check if the model is already downloaded */
    suspend fun isModelDownloaded(): Boolean

    /** Download the model with progress callback */
    suspend fun downloadModel(onProgress: (Float) -> Unit): Boolean

    /** Delete the downloaded model to free space */
    suspend fun deleteModel(): Boolean
}

/**
 * Backend that connects to a remote API.
 */
interface RemoteBackend : TranslationBackend {

    /** Current API endpoint URL */
    var endpoint: String

    /** Model name to use for requests */
    var modelName: String

    /** Test the connection to the remote API */
    suspend fun testConnection(): Boolean
}

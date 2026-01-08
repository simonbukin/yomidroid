package com.yomidroid.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kotlin wrapper for llama.cpp JNI interface.
 * Provides on-device LLM inference for Japanese translation.
 */
class LlamaCpp private constructor() {

    companion object {
        private var instance: LlamaCpp? = null
        private var libraryLoaded = false

        @Synchronized
        fun getInstance(): LlamaCpp {
            if (instance == null) {
                instance = LlamaCpp()
            }
            return instance!!
        }

        private fun loadLibrary() {
            if (!libraryLoaded) {
                try {
                    System.loadLibrary("yomidroid_llm")
                    libraryLoaded = true
                } catch (e: UnsatisfiedLinkError) {
                    throw RuntimeException("Failed to load LLM library: ${e.message}", e)
                }
            }
        }
    }

    init {
        loadLibrary()
        init()
    }

    // Native methods
    private external fun init()
    external fun loadModel(modelPath: String): Boolean
    external fun generate(prompt: String, maxTokens: Int): String
    external fun unloadModel()
    external fun shutdown()
    external fun isModelLoaded(): Boolean

    /**
     * Generate text from a prompt on a background thread.
     */
    suspend fun generateAsync(prompt: String, maxTokens: Int = 256): String =
        withContext(Dispatchers.Default) {
            generate(prompt, maxTokens)
        }

    /**
     * Load model on a background thread.
     */
    suspend fun loadModelAsync(modelPath: String): Boolean =
        withContext(Dispatchers.IO) {
            loadModel(modelPath)
        }

    /**
     * Natural translation: fluent, idiomatic English.
     * Returns null if model is not loaded or translation fails.
     */
    suspend fun translateNatural(japaneseText: String): String? {
        if (!isModelLoaded()) return null

        val prompt = """<start_of_turn>user
Translate this Japanese text to natural, fluent English.
Focus on conveying the meaning idiomatically, as a native English speaker would express it.
Do not include any explanation or notes - output ONLY the English translation.

Japanese: $japaneseText<end_of_turn>
<start_of_turn>model
"""
        val result = generateAsync(prompt, maxTokens = 256)
        return result.takeIf { it.isNotBlank() }?.trim()
    }

    /**
     * Literal translation: word-by-word, preserving Japanese structure.
     * Returns null if model is not loaded or translation fails.
     */
    suspend fun translateLiteral(japaneseText: String): String? {
        if (!isModelLoaded()) return null

        val prompt = """<start_of_turn>user
Translate this Japanese text to English as literally as possible.
Preserve the original word order and grammatical structure from Japanese.
Keep the translation close to the source, even if it sounds unnatural in English.
Do not include any explanation or notes - output ONLY the English translation.

Japanese: $japaneseText<end_of_turn>
<start_of_turn>model
"""
        val result = generateAsync(prompt, maxTokens = 256)
        return result.takeIf { it.isNotBlank() }?.trim()
    }
}

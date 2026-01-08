package com.yomidroid.translation

import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * ML Kit Translation backend as fast fallback.
 *
 * Pros:
 * - Small model size (~30MB)
 * - Fast inference (<500ms)
 * - Always available offline after first download
 * - Same SDK as OCR
 *
 * Cons:
 * - Quality comparable to Google Translate (~54% on VNTL benchmark)
 * - Only supports NATURAL translation mode
 * - No control over translation style
 */
class MlKitBackend(
    private val context: Context
) : DownloadableBackend {

    override val name = "ML Kit"
    override val priority = 10  // Lowest priority (fallback)
    override val modelSizeBytes = 30_000_000L  // ~30MB

    private var translator: Translator? = null
    private var modelDownloaded = false

    private fun getOrCreateTranslator(): Translator {
        return translator ?: run {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.JAPANESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
            Translation.getClient(options).also { translator = it }
        }
    }

    override suspend fun getStatus(): BackendStatus = withContext(Dispatchers.IO) {
        if (isModelDownloaded()) {
            BackendStatus.Available
        } else {
            BackendStatus.Unavailable
        }
    }

    override suspend fun isModelDownloaded(): Boolean = withContext(Dispatchers.IO) {
        if (modelDownloaded) return@withContext true

        suspendCancellableCoroutine { cont ->
            val conditions = DownloadConditions.Builder().build()
            getOrCreateTranslator().downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    modelDownloaded = true
                    cont.resume(true)
                }
                .addOnFailureListener {
                    cont.resume(false)
                }
        }
    }

    override suspend fun downloadModel(onProgress: (Float) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            // ML Kit doesn't provide progress, so we just report start/end
            onProgress(0f)

            val result = suspendCancellableCoroutine { cont ->
                val conditions = DownloadConditions.Builder()
                    .requireWifi()  // Only download on WiFi to save data
                    .build()

                getOrCreateTranslator().downloadModelIfNeeded(conditions)
                    .addOnSuccessListener {
                        modelDownloaded = true
                        cont.resume(true)
                    }
                    .addOnFailureListener {
                        cont.resume(false)
                    }
            }

            onProgress(1f)
            result
        }

    override suspend fun deleteModel(): Boolean = withContext(Dispatchers.IO) {
        // ML Kit models are managed by ML Kit, we can't directly delete them
        // But we can close the translator to release resources
        translator?.close()
        translator = null
        modelDownloaded = false
        true
    }

    override fun supportedModes(): Set<TranslationMode> = setOf(
        TranslationMode.NATURAL  // ML Kit only provides natural translation
    )

    override suspend fun translate(request: TranslationRequest): TranslationResult? =
        withContext(Dispatchers.IO) {
            if (!isModelDownloaded()) return@withContext null

            try {
                val translation = suspendCancellableCoroutine { cont ->
                    getOrCreateTranslator().translate(request.text)
                        .addOnSuccessListener { result ->
                            cont.resume(result)
                        }
                        .addOnFailureListener {
                            cont.resume(null)
                        }
                }

                if (translation != null) {
                    TranslationResult(
                        originalText = request.text,
                        natural = translation,
                        literal = null,  // ML Kit doesn't support literal translation
                        interlinear = null,
                        backend = name
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

    override fun shutdown() {
        translator?.close()
        translator = null
    }
}

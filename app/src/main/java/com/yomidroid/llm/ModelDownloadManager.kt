package com.yomidroid.llm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages downloading and storage of the on-device LLM model.
 */
class ModelDownloadManager(private val context: Context) {

    companion object {
        // gemma-2-2b-jpn-it-translate Q4_K_M (1.71 GB)
        private const val MODEL_URL = "https://huggingface.co/webbigdata/gemma-2-2b-jpn-it-translate-gguf/resolve/main/gemma-2-2b-jpn-it-translate-Q4_K_M.gguf"
        private const val MODEL_FILENAME = "translation-model.gguf"
        private const val MODEL_SIZE_BYTES = 1_710_000_000L // ~1.71 GB

        @Volatile
        private var instance: ModelDownloadManager? = null

        fun getInstance(context: Context): ModelDownloadManager {
            return instance ?: synchronized(this) {
                instance ?: ModelDownloadManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val modelDir = File(context.filesDir, "models")
    private val modelFile = File(modelDir, MODEL_FILENAME)

    /**
     * Check if the model is downloaded and valid.
     */
    fun isModelDownloaded(): Boolean {
        return modelFile.exists() && modelFile.length() > MODEL_SIZE_BYTES * 0.9
    }

    /**
     * Get the path to the model file.
     */
    fun getModelPath(): String = modelFile.absolutePath

    /**
     * Get the expected model size in bytes.
     */
    fun getModelSizeBytes(): Long = MODEL_SIZE_BYTES

    /**
     * Delete the downloaded model.
     */
    fun deleteModel(): Boolean {
        return if (modelFile.exists()) {
            modelFile.delete()
        } else {
            true
        }
    }

    /**
     * Download the model with progress callback.
     * @param onProgress Called with progress from 0.0 to 1.0
     * @return true if download succeeded
     */
    suspend fun downloadModel(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            modelDir.mkdirs()

            val url = URL(MODEL_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.setRequestProperty("User-Agent", "Yomidroid/1.0")

            // Handle redirects
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("HTTP error: $responseCode")
            }

            val contentLength = connection.contentLengthLong
            val totalBytes = if (contentLength > 0) contentLength else MODEL_SIZE_BYTES

            val tempFile = File(modelDir, "$MODEL_FILENAME.tmp")
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        onProgress(downloadedBytes.toFloat() / totalBytes)
                    }
                }
            }

            // Rename temp file to final file
            if (tempFile.renameTo(modelFile)) {
                true
            } else {
                tempFile.delete()
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

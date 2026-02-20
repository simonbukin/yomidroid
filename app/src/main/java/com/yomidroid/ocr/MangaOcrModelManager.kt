package com.yomidroid.ocr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages downloading and storage of manga-ocr ONNX model files.
 * Models are downloaded from HuggingFace (l0wgear/manga-ocr-2025-onnx).
 */
class MangaOcrModelManager(private val context: Context) {

    companion object {
        private const val TAG = "MangaOcrModelManager"
        private const val BASE_URL = "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/main"
        private const val MODEL_DIR_NAME = "manga_ocr_models"

        private val MODEL_FILES = listOf(
            ModelFile("encoder_model.onnx", 22_400_000L),
            ModelFile("decoder_model.onnx", 118_000_000L),
            ModelFile("vocab.txt", 24_000L)
        )

        /** Total expected download size in bytes (~141 MB) */
        const val TOTAL_SIZE_BYTES = 140_424_000L

        @Volatile
        private var instance: MangaOcrModelManager? = null

        fun getInstance(context: Context): MangaOcrModelManager {
            return instance ?: synchronized(this) {
                instance ?: MangaOcrModelManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private data class ModelFile(val name: String, val expectedSize: Long)

    private val modelDir = File(context.filesDir, MODEL_DIR_NAME)

    init {
        cleanStaleTempFiles()
    }

    fun isModelReady(): Boolean {
        return MODEL_FILES.all { modelFile ->
            val file = File(modelDir, modelFile.name)
            file.exists() && file.length() > modelFile.expectedSize * 0.9
        }
    }

    fun getEncoderPath(): String = File(modelDir, "encoder_model.onnx").absolutePath
    fun getDecoderPath(): String = File(modelDir, "decoder_model.onnx").absolutePath
    fun getVocabPath(): String = File(modelDir, "vocab.txt").absolutePath

    fun deleteModels(): Boolean {
        return try {
            modelDir.listFiles()?.forEach { it.delete() }
            modelDir.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete models", e)
            false
        }
    }

    /**
     * Download all model files sequentially with combined progress reporting.
     * @param onProgress Called with progress from 0.0 to 1.0 across all files
     * @return true if all downloads succeeded
     */
    suspend fun downloadModels(onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            modelDir.mkdirs()
            cleanStaleTempFiles()

            var completedBytes = 0L

            for (modelFile in MODEL_FILES) {
                val targetFile = File(modelDir, modelFile.name)
                if (targetFile.exists() && targetFile.length() > modelFile.expectedSize * 0.9) {
                    completedBytes += modelFile.expectedSize
                    onProgress(completedBytes.toFloat() / TOTAL_SIZE_BYTES)
                    continue
                }

                val success = downloadFile(
                    url = "$BASE_URL/${modelFile.name}",
                    targetFile = targetFile,
                    onFileProgress = { fileBytes ->
                        onProgress((completedBytes + fileBytes).toFloat() / TOTAL_SIZE_BYTES)
                    }
                )

                if (!success) {
                    Log.e(TAG, "Failed to download ${modelFile.name}")
                    return@withContext false
                }

                completedBytes += targetFile.length()
                onProgress(completedBytes.toFloat() / TOTAL_SIZE_BYTES)
            }

            Log.d(TAG, "All manga-ocr models downloaded successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            false
        }
    }

    private fun downloadFile(
        url: String,
        targetFile: File,
        onFileProgress: (Long) -> Unit
    ): Boolean {
        val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.setRequestProperty("User-Agent", "Yomidroid/1.0")
            connection.instanceFollowRedirects = true

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP error $responseCode for $url")
                return false
            }

            var downloadedBytes = 0L
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        onFileProgress(downloadedBytes)
                    }
                }
            }

            return tempFile.renameTo(targetFile).also { success ->
                if (!success) tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading $url", e)
            tempFile.delete()
            return false
        }
    }

    private fun cleanStaleTempFiles() {
        modelDir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach {
            Log.d(TAG, "Cleaning stale temp file: ${it.name}")
            it.delete()
        }
    }
}

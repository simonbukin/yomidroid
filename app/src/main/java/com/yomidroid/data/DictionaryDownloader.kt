package com.yomidroid.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads dictionary ZIPs from URLs and imports them via DictionaryImporter.
 * Progress: 0-40% download, 40-100% import.
 */
class DictionaryDownloader(private val context: Context) {

    companion object {
        private const val TAG = "DictionaryDownloader"
    }

    private val tempDir: File
        get() = File(context.cacheDir, "dict_downloads").also { it.mkdirs() }

    suspend fun downloadAndImport(
        url: String,
        onProgress: (phase: String, progress: Float) -> Unit
    ): ImportResult = withContext(Dispatchers.IO) {
        var tempFile: File? = null
        try {
            // Phase 1: Download (0% - 40%)
            onProgress("Downloading...", 0f)
            tempFile = File(tempDir, "download_${System.currentTimeMillis()}.zip")

            val downloadOk = downloadFile(url, tempFile) { downloadProgress ->
                onProgress("Downloading...", downloadProgress * 0.4f)
            }

            if (!downloadOk) {
                return@withContext ImportResult(false, error = "Download failed")
            }

            Log.d(TAG, "Downloaded ${tempFile.length() / 1024}KB from $url")

            // Phase 2: Import (40% - 100%)
            val importer = DictionaryImporter(context)
            importer.importFromFile(tempFile) { phase, importProgress ->
                onProgress(phase, 0.4f + importProgress * 0.6f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download+import failed: ${e.message}", e)
            ImportResult(false, error = e.message ?: "Unknown error")
        } finally {
            tempFile?.let {
                try { if (it.exists()) it.delete() } catch (_: Exception) {}
            }
        }
    }

    private fun downloadFile(
        urlStr: String,
        dest: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Yomidroid/1.0")

        try {
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP $responseCode for $urlStr")
                return false
            }

            val totalBytes = connection.contentLengthLong
            var downloaded = 0L

            connection.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalBytes > 0) {
                            onProgress((downloaded.toFloat() / totalBytes).coerceAtMost(1f))
                        }
                    }
                }
            }
            return true
        } finally {
            connection.disconnect()
        }
    }
}

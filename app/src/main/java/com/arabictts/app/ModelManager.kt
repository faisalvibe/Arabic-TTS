package com.arabictts.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and manages Piper VITS ONNX models for Arabic and English TTS.
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val BASE_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/main"

        // Arabic: Kareem (Jordanian) - low quality for smaller size
        private const val AR_MODEL_PATH = "ar/ar_JO/kareem/low/ar_JO-kareem-low.onnx"
        private const val AR_CONFIG_PATH = "ar/ar_JO/kareem/low/ar_JO-kareem-low.onnx.json"

        // English: Amy (US) - low quality for smaller size
        private const val EN_MODEL_PATH = "en/en_US/amy/low/en_US-amy-low.onnx"
        private const val EN_CONFIG_PATH = "en/en_US/amy/low/en_US-amy-low.onnx.json"

        // Data tokens file needed by Piper
        private const val ESPEAK_DATA_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/espeak-ng-data.tar.bz2"
    }

    data class ModelFiles(
        val modelPath: String,
        val configPath: String
    )

    data class DownloadProgress(
        val component: String,
        val bytesDownloaded: Long,
        val totalBytes: Long
    )

    private val modelsDir = File(context.filesDir, "models")
    private val espeakDir = File(context.filesDir, "espeak-ng-data")

    fun getArabicModelFiles(): ModelFiles? {
        val model = File(modelsDir, "ar_JO-kareem-low.onnx")
        val config = File(modelsDir, "ar_JO-kareem-low.onnx.json")
        return if (model.exists() && config.exists()) {
            ModelFiles(model.absolutePath, config.absolutePath)
        } else null
    }

    fun getEnglishModelFiles(): ModelFiles? {
        val model = File(modelsDir, "en_US-amy-low.onnx")
        val config = File(modelsDir, "en_US-amy-low.onnx.json")
        return if (model.exists() && config.exists()) {
            ModelFiles(model.absolutePath, config.absolutePath)
        } else null
    }

    fun getEspeakDataDir(): String? {
        return if (espeakDir.exists() && espeakDir.isDirectory) {
            espeakDir.absolutePath
        } else null
    }

    fun areModelsReady(): Boolean {
        return getArabicModelFiles() != null &&
                getEnglishModelFiles() != null &&
                getEspeakDataDir() != null
    }

    suspend fun downloadAllModels(
        onProgress: (DownloadProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()

        // Download Arabic model + config
        downloadFile(
            "$BASE_URL/$AR_MODEL_PATH",
            File(modelsDir, "ar_JO-kareem-low.onnx"),
            "Arabic voice model"
        ) { bytes, total -> onProgress(DownloadProgress("Arabic voice model", bytes, total)) }

        downloadFile(
            "$BASE_URL/$AR_CONFIG_PATH",
            File(modelsDir, "ar_JO-kareem-low.onnx.json"),
            "Arabic config"
        ) { bytes, total -> onProgress(DownloadProgress("Arabic config", bytes, total)) }

        // Download English model + config
        downloadFile(
            "$BASE_URL/$EN_MODEL_PATH",
            File(modelsDir, "en_US-amy-low.onnx"),
            "English voice model"
        ) { bytes, total -> onProgress(DownloadProgress("English voice model", bytes, total)) }

        downloadFile(
            "$BASE_URL/$EN_CONFIG_PATH",
            File(modelsDir, "en_US-amy-low.onnx.json"),
            "English config"
        ) { bytes, total -> onProgress(DownloadProgress("English config", bytes, total)) }

        // Download and extract espeak-ng data
        if (!espeakDir.exists()) {
            val tarFile = File(context.cacheDir, "espeak-ng-data.tar.bz2")
            downloadFile(
                ESPEAK_DATA_URL,
                tarFile,
                "Phoneme data"
            ) { bytes, total -> onProgress(DownloadProgress("Phoneme data", bytes, total)) }

            extractTarBz2(tarFile, context.filesDir)
            tarFile.delete()
        }
    }

    private fun downloadFile(
        urlString: String,
        destFile: File,
        label: String,
        onProgress: (Long, Long) -> Unit
    ) {
        if (destFile.exists()) return

        val tempFile = File(destFile.parent, "${destFile.name}.tmp")
        var conn: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            var activeConn = url.openConnection() as HttpURLConnection
            activeConn.connectTimeout = 30_000
            activeConn.readTimeout = 30_000
            activeConn.instanceFollowRedirects = true

            // Handle redirects manually for cross-protocol
            var responseCode = activeConn.responseCode
            var currentUrl = urlString
            var redirectCount = 0
            while (responseCode in 301..303 || responseCode == 307 || responseCode == 308) {
                if (redirectCount++ > 5) throw Exception("Too many redirects for $label")
                currentUrl = activeConn.getHeaderField("Location") ?: break
                activeConn.disconnect()
                activeConn = URL(currentUrl).openConnection() as HttpURLConnection
                activeConn.connectTimeout = 30_000
                activeConn.readTimeout = 30_000
                activeConn.instanceFollowRedirects = true
                responseCode = activeConn.responseCode
            }
            conn = activeConn

            if (responseCode != 200) {
                throw Exception("HTTP $responseCode downloading $label from $currentUrl")
            }

            val totalBytes = activeConn.contentLengthLong
            var downloadedBytes = 0L

            activeConn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        onProgress(downloadedBytes, totalBytes)
                    }
                }
            }

            tempFile.renameTo(destFile)
        } catch (e: Exception) {
            tempFile.delete()
            throw Exception("Failed to download $label: ${e.message}", e)
        } finally {
            conn?.disconnect()
        }
    }

    private fun extractTarBz2(tarBz2File: File, destDir: File) {
        val process = ProcessBuilder(
            "tar", "xjf", tarBz2File.absolutePath, "-C", destDir.absolutePath
        ).start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            // Fallback: try with busybox or just bzip2 + tar
            val process2 = ProcessBuilder(
                "sh", "-c",
                "cd ${destDir.absolutePath} && bzip2 -dc ${tarBz2File.absolutePath} | tar xf -"
            ).start()
            if (process2.waitFor() != 0) {
                throw Exception("Failed to extract espeak-ng data (exit code: $exitCode)")
            }
        }
    }
}

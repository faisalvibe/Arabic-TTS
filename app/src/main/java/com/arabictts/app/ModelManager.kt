package com.arabictts.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and manages Piper VITS ONNX models for Arabic and English TTS.
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
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
        val tokensPath: String
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
        val tokens = File(modelsDir, "ar_JO-kareem-low-tokens.txt")
        return if (model.exists() && tokens.exists()) {
            ModelFiles(model.absolutePath, tokens.absolutePath)
        } else null
    }

    fun getEnglishModelFiles(): ModelFiles? {
        val model = File(modelsDir, "en_US-amy-low.onnx")
        val tokens = File(modelsDir, "en_US-amy-low-tokens.txt")
        return if (model.exists() && tokens.exists()) {
            ModelFiles(model.absolutePath, tokens.absolutePath)
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

        // Download Arabic model + config, then generate tokens.txt
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

        generateTokensFile(
            File(modelsDir, "ar_JO-kareem-low.onnx.json"),
            File(modelsDir, "ar_JO-kareem-low-tokens.txt")
        )

        // Download English model + config, then generate tokens.txt
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

        generateTokensFile(
            File(modelsDir, "en_US-amy-low.onnx.json"),
            File(modelsDir, "en_US-amy-low-tokens.txt")
        )

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

    /**
     * Parses a Piper .onnx.json config and generates the tokens.txt that sherpa-onnx needs.
     * The JSON has a "phoneme_id_map" like {"_": [0], "^": [1], ...}.
     * tokens.txt needs lines like "_\n^\n..." where line number = token ID.
     */
    private fun generateTokensFile(configJson: File, tokensFile: File) {
        if (tokensFile.exists()) return
        if (!configJson.exists()) throw Exception("Config file not found: ${configJson.name}")

        try {
            val json = JSONObject(configJson.readText())
            val phonemeIdMap = json.getJSONObject("phoneme_id_map")

            // Build id -> phoneme mapping
            val idToPhoneme = mutableMapOf<Int, String>()
            val keys = phonemeIdMap.keys()
            while (keys.hasNext()) {
                val phoneme = keys.next()
                val ids = phonemeIdMap.getJSONArray(phoneme)
                for (i in 0 until ids.length()) {
                    idToPhoneme[ids.getInt(i)] = phoneme
                }
            }

            // Write tokens.txt: each line is the phoneme for that token ID
            val maxId = idToPhoneme.keys.maxOrNull() ?: return
            tokensFile.bufferedWriter().use { writer ->
                for (id in 0..maxId) {
                    val phoneme = idToPhoneme[id] ?: ""
                    writer.write(phoneme)
                    writer.write(" ")
                    writer.write(id.toString())
                    writer.newLine()
                }
            }
            Log.i(TAG, "Generated tokens file: ${tokensFile.name} with ${maxId + 1} tokens")
        } catch (e: Exception) {
            tokensFile.delete()
            throw Exception("Failed to generate tokens from ${configJson.name}: ${e.message}", e)
        }
    }

    /**
     * Extracts a .tar.bz2 archive using Apache Commons Compress (pure Java, works on all Android).
     */
    private fun extractTarBz2(tarBz2File: File, destDir: File) {
        Log.i(TAG, "Extracting ${tarBz2File.name} to ${destDir.absolutePath}")
        val fis = FileInputStream(tarBz2File)
        val bis = BufferedInputStream(fis)
        val bzis = BZip2CompressorInputStream(bis)
        val tais = TarArchiveInputStream(bzis)

        try {
            var entry = tais.nextTarEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)

                // Prevent path traversal
                if (!outFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                    throw Exception("Bad tar entry: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (tais.read(buffer).also { len = it } != -1) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                entry = tais.nextTarEntry
            }
            Log.i(TAG, "Extraction complete")
        } finally {
            tais.close()
            bzis.close()
            bis.close()
            fis.close()
        }
    }
}

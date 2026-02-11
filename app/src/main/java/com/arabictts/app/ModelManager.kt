package com.arabictts.app

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
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

        // Custom English voice model filenames
        private const val CUSTOM_EN_MODEL = "custom_en.onnx"
        private const val CUSTOM_EN_CONFIG = "custom_en.onnx.json"
        private const val CUSTOM_EN_TOKENS = "custom_en-tokens.txt"

        // Legacy custom Arabic filenames (for cleanup)
        private const val LEGACY_CUSTOM_AR_MODEL = "custom_ar.onnx"
        private const val LEGACY_CUSTOM_AR_CONFIG = "custom_ar.onnx.json"
        private const val LEGACY_CUSTOM_AR_TOKENS = "custom_ar-tokens.txt"
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

    /**
     * Returns custom English model files if imported, otherwise null.
     */
    fun getCustomEnglishModelFiles(): ModelFiles? {
        val model = File(modelsDir, CUSTOM_EN_MODEL)
        val tokens = File(modelsDir, CUSTOM_EN_TOKENS)
        return if (model.exists() && tokens.exists()) {
            ModelFiles(model.absolutePath, tokens.absolutePath)
        } else null
    }

    fun hasCustomEnglishModel(): Boolean {
        return File(modelsDir, CUSTOM_EN_MODEL).exists() &&
                File(modelsDir, CUSTOM_EN_CONFIG).exists()
    }

    /**
     * Imports a custom English voice from user-provided .onnx and .onnx.json files.
     * Copies both files to the models directory, generates tokens, and injects metadata.
     */
    suspend fun importCustomEnglishModel(
        onnxUri: Uri,
        configUri: Uri
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            modelsDir.mkdirs()

            val modelFile = File(modelsDir, CUSTOM_EN_MODEL)
            val configFile = File(modelsDir, CUSTOM_EN_CONFIG)
            val tokensFile = File(modelsDir, CUSTOM_EN_TOKENS)

            // Clean up any previous custom model files
            removeCustomEnglishModelFiles()

            // Copy .onnx model
            context.contentResolver.openInputStream(onnxUri)?.use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Could not read ONNX model file")

            // Copy .onnx.json config
            context.contentResolver.openInputStream(configUri)?.use { input ->
                FileOutputStream(configFile).use { output ->
                    input.copyTo(output)
                }
            } ?: throw Exception("Could not read config JSON file")

            // Validate config has required fields
            val json = JSONObject(configFile.readText())
            if (!json.has("phoneme_id_map")) {
                removeCustomEnglishModelFiles()
                throw Exception("Config JSON missing 'phoneme_id_map' field")
            }
            if (!json.has("audio") || !json.getJSONObject("audio").has("sample_rate")) {
                removeCustomEnglishModelFiles()
                throw Exception("Config JSON missing 'audio.sample_rate' field")
            }

            // Generate tokens file from config
            generateTokensFile(configFile, tokensFile)

            // Inject ONNX metadata
            injectOnnxMetadata(modelFile, configFile)

            Log.i(TAG, "Custom English model imported successfully: ${modelFile.length()} bytes")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import custom English model", e)
            removeCustomEnglishModelFiles()
            Result.failure(e)
        }
    }

    /**
     * Downloads a custom English voice from URLs and imports it.
     */
    suspend fun importCustomEnglishModelFromUrls(
        onnxUrl: String,
        configUrl: String,
        onProgress: (String) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            modelsDir.mkdirs()

            val modelFile = File(modelsDir, CUSTOM_EN_MODEL)
            val configFile = File(modelsDir, CUSTOM_EN_CONFIG)
            val tokensFile = File(modelsDir, CUSTOM_EN_TOKENS)

            removeCustomEnglishModelFiles()

            onProgress("Downloading .onnx model...")
            downloadFile(onnxUrl, modelFile, "Custom voice model") { _, _ -> }

            onProgress("Downloading .onnx.json config...")
            downloadFile(configUrl, configFile, "Custom voice config") { _, _ -> }

            // Validate config
            val json = JSONObject(configFile.readText())
            if (!json.has("phoneme_id_map")) {
                removeCustomEnglishModelFiles()
                throw Exception("Config JSON missing 'phoneme_id_map' field")
            }
            if (!json.has("audio") || !json.getJSONObject("audio").has("sample_rate")) {
                removeCustomEnglishModelFiles()
                throw Exception("Config JSON missing 'audio.sample_rate' field")
            }

            onProgress("Processing voice files...")
            generateTokensFile(configFile, tokensFile)
            injectOnnxMetadata(modelFile, configFile)

            Log.i(TAG, "Custom English model downloaded from URL: ${modelFile.length()} bytes")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download custom English model from URL", e)
            removeCustomEnglishModelFiles()
            Result.failure(e)
        }
    }

    /**
     * Removes the custom English voice model files, reverting to the default Amy voice.
     */
    fun removeCustomEnglishModelFiles() {
        File(modelsDir, CUSTOM_EN_MODEL).delete()
        File(modelsDir, CUSTOM_EN_CONFIG).delete()
        File(modelsDir, CUSTOM_EN_TOKENS).delete()
        File(modelsDir, "${CUSTOM_EN_MODEL}.patched_v3").delete()
        File(modelsDir, "${CUSTOM_EN_MODEL}.inject_log").delete()
        // Also clean up legacy custom_ar files from previous versions
        removeLegacyCustomFiles()
    }

    /**
     * Removes legacy custom_ar.* files from before the fix that moved
     * custom voices from the Arabic slot to the English slot.
     */
    fun removeLegacyCustomFiles() {
        File(modelsDir, LEGACY_CUSTOM_AR_MODEL).delete()
        File(modelsDir, LEGACY_CUSTOM_AR_CONFIG).delete()
        File(modelsDir, LEGACY_CUSTOM_AR_TOKENS).delete()
        File(modelsDir, "${LEGACY_CUSTOM_AR_MODEL}.patched_v3").delete()
        File(modelsDir, "${LEGACY_CUSTOM_AR_MODEL}.inject_log").delete()
    }

    fun areModelsReady(): Boolean {
        // Clean up legacy custom_ar files from before the English slot fix
        removeLegacyCustomFiles()
        // Always regenerate tokens.txt to fix any broken format from older versions
        ensureTokenFiles()
        // Inject required ONNX metadata into Piper models (sherpa-onnx exits if missing)
        ensureOnnxMetadata()
        // Arabic is always default Kareem; English can be custom or default Amy
        val hasEnglish = getCustomEnglishModelFiles() != null || getEnglishModelFiles() != null
        return getArabicModelFiles() != null &&
                hasEnglish &&
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
        injectOnnxMetadata(
            File(modelsDir, "ar_JO-kareem-low.onnx"),
            File(modelsDir, "ar_JO-kareem-low.onnx.json")
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
        injectOnnxMetadata(
            File(modelsDir, "en_US-amy-low.onnx"),
            File(modelsDir, "en_US-amy-low.onnx.json")
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
     * Ensures tokens.txt files are correctly generated from the Piper JSON configs.
     * Call this on every startup to repair tokens files from older versions that had
     * a bug writing duplicate token entries (which causes sherpa-onnx to exit(-1)).
     */
    fun ensureTokenFiles() {
        val arConfig = File(modelsDir, "ar_JO-kareem-low.onnx.json")
        val arTokens = File(modelsDir, "ar_JO-kareem-low-tokens.txt")
        if (arConfig.exists()) {
            arTokens.delete()
            generateTokensFile(arConfig, arTokens)
        }

        val enConfig = File(modelsDir, "en_US-amy-low.onnx.json")
        val enTokens = File(modelsDir, "en_US-amy-low-tokens.txt")
        if (enConfig.exists()) {
            enTokens.delete()
            generateTokensFile(enConfig, enTokens)
        }

        // Custom English model
        val customEnConfig = File(modelsDir, CUSTOM_EN_CONFIG)
        val customEnTokens = File(modelsDir, CUSTOM_EN_TOKENS)
        if (customEnConfig.exists()) {
            customEnTokens.delete()
            generateTokensFile(customEnConfig, customEnTokens)
        }
    }

    /**
     * Ensures ONNX model files have the required metadata for sherpa-onnx.
     * Sherpa-onnx VITS loader calls exit(-1) if these are missing from ONNX metadata:
     *   sample_rate, n_speakers, language, comment
     * Additionally, 'voice' is needed for espeak_SetVoiceByName() (defaults to "" if missing).
     * Piper models from HuggingFace store this in the JSON config, not in the ONNX model.
     * This function appends protobuf metadata entries to the ONNX files.
     */
    fun ensureOnnxMetadata() {
        injectOnnxMetadata(
            File(modelsDir, "ar_JO-kareem-low.onnx"),
            File(modelsDir, "ar_JO-kareem-low.onnx.json")
        )
        injectOnnxMetadata(
            File(modelsDir, "en_US-amy-low.onnx"),
            File(modelsDir, "en_US-amy-low.onnx.json")
        )
        // Custom English model
        injectOnnxMetadata(
            File(modelsDir, CUSTOM_EN_MODEL),
            File(modelsDir, CUSTOM_EN_CONFIG)
        )
    }

    /**
     * Reads metadata from a Piper .onnx.json config and appends it as protobuf
     * metadata_props entries to the ONNX model file.
     * Uses a versioned .patched marker file to track injection state.
     *
     * If upgrading from an older patch version, the ONNX file is deleted to force
     * a clean re-download, since appending multiple patches corrupts the file
     * with duplicate metadata_props entries.
     */
    private fun injectOnnxMetadata(onnxFile: File, configJson: File) {
        if (!onnxFile.exists() || !configJson.exists()) return

        val statusFile = File(onnxFile.parent, "${onnxFile.name}.inject_log")
        val marker = File(onnxFile.parent, "${onnxFile.name}.patched_v3")

        // Already patched with current version - nothing to do
        if (marker.exists()) {
            statusFile.writeText("SKIP: already patched v3 at ${java.util.Date()}")
            return
        }

        // Check for old patch versions - if found, the ONNX file has stale metadata
        // appended to it. Delete it to force a clean re-download.
        val oldV1 = File(onnxFile.parent, "${onnxFile.name}.patched")
        val oldV2 = File(onnxFile.parent, "${onnxFile.name}.patched_v2")
        if (oldV1.exists() || oldV2.exists()) {
            Log.i(TAG, "Deleting ${onnxFile.name} to force clean re-download (upgrading from old patch)")
            onnxFile.delete()
            oldV1.delete()
            oldV2.delete()
            statusFile.writeText("DELETED: old patched ONNX removed for clean re-download at ${java.util.Date()}")
            return
        }

        // Fresh ONNX file (never patched) - inject metadata
        try {
            val json = JSONObject(configJson.readText())

            val sampleRate = json.getJSONObject("audio").getInt("sample_rate")
            val numSpeakers = json.optInt("num_speakers", 1)
            // Use espeak.voice (e.g. "ar", "en-us") not language.family (e.g. "en")
            val espeakVoice = json.optJSONObject("espeak")?.optString("voice", "en-us") ?: "en-us"

            val metadata = mapOf(
                "sample_rate" to sampleRate.toString(),
                "n_speakers" to numSpeakers.toString(),
                "language" to espeakVoice,
                "voice" to espeakVoice,
                "comment" to "piper",
                "add_blank" to "1"
            )

            val sizeBefore = onnxFile.length()
            val bytes = buildProtobufMetadata(metadata)
            FileOutputStream(onnxFile, true).use { fos ->
                fos.write(bytes)
            }
            val sizeAfter = onnxFile.length()

            marker.writeText("v3:$sizeBefore")
            val msg = "OK: injected ${bytes.size} bytes at ${java.util.Date()}\n" +
                    "original_size: $sizeBefore, patched_size: $sizeAfter\nmetadata: $metadata"
            statusFile.writeText(msg)
            Log.i(TAG, "Injected ONNX metadata into ${onnxFile.name}: $metadata")
        } catch (e: Exception) {
            statusFile.writeText("FAIL at ${java.util.Date()}: ${e.message}\n${e.stackTraceToString()}")
            Log.e(TAG, "Failed to inject ONNX metadata into ${onnxFile.name}: ${e.message}")
        }
    }

    /**
     * Builds protobuf bytes for ModelProto.metadata_props (field 14) entries.
     * Each entry is a StringStringEntryProto with key (field 1) and value (field 2).
     * These bytes can be appended to a serialized ONNX ModelProto file.
     */
    private fun buildProtobufMetadata(metadata: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        for ((key, value) in metadata) {
            val keyBytes = key.toByteArray(Charsets.UTF_8)
            val valueBytes = value.toByteArray(Charsets.UTF_8)

            // Build submessage (StringStringEntryProto)
            val submsg = ByteArrayOutputStream()
            submsg.write(0x0A) // field 1 (key), wire type 2
            writeVarint(submsg, keyBytes.size)
            submsg.write(keyBytes)
            submsg.write(0x12) // field 2 (value), wire type 2
            writeVarint(submsg, valueBytes.size)
            submsg.write(valueBytes)
            val submsgBytes = submsg.toByteArray()

            // Field 14 (metadata_props), wire type 2 = (14 << 3) | 2 = 0x72
            baos.write(0x72)
            writeVarint(baos, submsgBytes.size)
            baos.write(submsgBytes)
        }
        return baos.toByteArray()
    }

    private fun writeVarint(stream: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v > 0x7F) {
            stream.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        stream.write(v and 0x7F)
    }

    /**
     * Parses a Piper .onnx.json config and generates the tokens.txt that sherpa-onnx needs.
     * The JSON has a "phoneme_id_map" like {"_": [0], "^": [1], " ": [3], ...}.
     *
     * IMPORTANT: Each phoneme must appear EXACTLY ONCE using only its first ID.
     * Sherpa-onnx calls exit(-1) on duplicate token names.
     * The space character token is handled by sherpa-onnx's Trim+EOF heuristic.
     * Newline tokens must be skipped (would break line-based format).
     */
    private fun generateTokensFile(configJson: File, tokensFile: File) {
        if (!configJson.exists()) throw Exception("Config file not found: ${configJson.name}")

        try {
            val json = JSONObject(configJson.readText())
            val phonemeIdMap = json.getJSONObject("phoneme_id_map")

            // Build phoneme -> first ID mapping (one entry per phoneme, matching sherpa-onnx convention)
            val entries = mutableListOf<Pair<String, Int>>()
            val keys = phonemeIdMap.keys()
            while (keys.hasNext()) {
                val phoneme = keys.next()
                if (phoneme == "\n") continue  // Skip newline token (would break line-based format)
                val ids = phonemeIdMap.getJSONArray(phoneme)
                val firstId = ids.getInt(0)
                entries.add(phoneme to firstId)
            }

            // Sort by ID for consistency
            entries.sortBy { it.second }

            // Write tokens.txt: each line is "<token> <id>"
            tokensFile.bufferedWriter().use { writer ->
                for ((phoneme, id) in entries) {
                    writer.write(phoneme)
                    writer.write(" ")
                    writer.write(id.toString())
                    writer.newLine()
                }
            }
            Log.i(TAG, "Generated tokens file: ${tokensFile.name} with ${entries.size} entries")
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

package com.arabictts.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wraps Sherpa-ONNX TTS for bilingual Arabic/English synthesis.
 * Manages two Piper VITS models and handles language-based routing.
 */
class TTSEngine {

    companion object {
        private const val TAG = "TTSEngine"
    }

    private var arabicTts: OfflineTts? = null
    private var englishTts: OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    var breadcrumbFile: File? = null

    private fun breadcrumb(msg: String) {
        try {
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            breadcrumbFile?.appendText("[$ts] $msg\n")
            Log.i(TAG, "BREADCRUMB: $msg")
        } catch (_: Exception) {}
    }

    data class InitResult(val success: Boolean, val error: String? = null)

    fun initArabic(modelPath: String, tokensPath: String, espeakDataDir: String): InitResult {
        return try {
            Log.i(TAG, "Initializing Arabic TTS: model=$modelPath, tokens=$tokensPath, espeak=$espeakDataDir")
            // Release previous instance so native resources are freed before loading new model
            arabicTts?.release()
            arabicTts = null
            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelPath,
                tokens = tokensPath,
                dataDir = espeakDataDir,
                dictDir = ""
            )
            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,
                debug = true,
                provider = "cpu"
            )
            val config = OfflineTtsConfig(
                model = modelConfig,
                maxNumSentences = 1
            )
            arabicTts = OfflineTts(config = config)
            Log.i(TAG, "Arabic TTS initialized successfully")
            InitResult(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Arabic TTS", e)
            InitResult(false, e.message)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library error for Arabic TTS", e)
            InitResult(false, "Native library error: ${e.message}")
        }
    }

    fun initEnglish(modelPath: String, tokensPath: String, espeakDataDir: String): InitResult {
        return try {
            Log.i(TAG, "Initializing English TTS: model=$modelPath, tokens=$tokensPath, espeak=$espeakDataDir")
            englishTts?.release()
            englishTts = null
            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelPath,
                tokens = tokensPath,
                dataDir = espeakDataDir,
                dictDir = ""
            )
            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,
                debug = true,
                provider = "cpu"
            )
            val config = OfflineTtsConfig(
                model = modelConfig,
                maxNumSentences = 1
            )
            englishTts = OfflineTts(config = config)
            Log.i(TAG, "English TTS initialized successfully")
            InitResult(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init English TTS", e)
            InitResult(false, e.message)
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native library error for English TTS", e)
            InitResult(false, "Native library error: ${e.message}")
        }
    }

    /**
     * Synthesizes and plays text, automatically detecting and routing
     * Arabic vs English segments to the appropriate model.
     */
    suspend fun speak(
        text: String,
        speed: Float = 1.0f,
        onSegmentStart: ((LanguageDetector.TextSegment) -> Unit)? = null
    ) = withContext(Dispatchers.Default) {
        if (isPlaying) {
            stop()
        }

        val segments = LanguageDetector.splitByLanguage(text)
        if (segments.isEmpty()) return@withContext

        isPlaying = true

        for (segment in segments) {
            if (!isPlaying) break

            onSegmentStart?.invoke(segment)

            val tts = when (segment.language) {
                LanguageDetector.Language.ARABIC -> arabicTts
                LanguageDetector.Language.ENGLISH -> englishTts
            }

            if (tts == null) {
                Log.w(TAG, "No TTS engine for ${segment.language}, skipping: ${segment.text}")
                continue
            }

            try {
                breadcrumb("generate() calling for ${segment.language}: \"${segment.text.take(50)}\"")
                val audio = tts.generate(
                    text = segment.text,
                    sid = 0,
                    speed = speed
                )
                breadcrumb("generate() returned: ${audio.samples.size} samples, rate=${audio.sampleRate}")

                if (!isPlaying) break

                breadcrumb("playAudio() starting")
                playAudio(audio.samples, audio.sampleRate)
                breadcrumb("playAudio() completed")
            } catch (e: Throwable) {
                breadcrumb("EXCEPTION: ${e.javaClass.name}: ${e.message}")
                Log.e(TAG, "TTS generation failed for segment: ${segment.text}", e)
            }
        }

        isPlaying = false
    }

    /**
     * Synthesizes a single language text without auto-detection.
     */
    suspend fun speakWithLanguage(
        text: String,
        language: LanguageDetector.Language,
        speed: Float = 1.0f
    ) = withContext(Dispatchers.Default) {
        if (isPlaying) stop()

        val tts = when (language) {
            LanguageDetector.Language.ARABIC -> arabicTts
            LanguageDetector.Language.ENGLISH -> englishTts
        } ?: return@withContext

        isPlaying = true
        try {
            val audio = tts.generate(text = text, sid = 0, speed = speed)
            if (isPlaying) {
                playAudio(audio.samples, audio.sampleRate)
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS generation failed", e)
        }
        isPlaying = false
    }

    private fun playAudio(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return

        // Convert float samples to 16-bit PCM
        val pcmData = ShortArray(samples.size)
        for (i in samples.indices) {
            val sample = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            pcmData[i] = sample.toShort()
        }

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack?.release()
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize, pcmData.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack?.write(pcmData, 0, pcmData.size)
        audioTrack?.play()

        // Wait for playback to complete
        val durationMs = (samples.size * 1000L) / sampleRate
        Thread.sleep(durationMs + 100)

        audioTrack?.stop()
    }

    fun stop() {
        isPlaying = false
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (_: Exception) {}
    }

    fun release() {
        stop()
        arabicTts?.release()
        englishTts?.release()
        arabicTts = null
        englishTts = null
    }

    fun isArabicReady(): Boolean = arabicTts != null
    fun isEnglishReady(): Boolean = englishTts != null

    /**
     * Test generation with English TTS to verify native inference works.
     * Returns (sampleCount, sampleRate) or null on failure.
     */
    fun testGenerate(text: String): Pair<Int, Int>? {
        val tts = englishTts ?: arabicTts ?: return null
        breadcrumb("testGenerate() calling with: \"$text\"")
        val audio = tts.generate(text = text, sid = 0, speed = 1.0f)
        breadcrumb("testGenerate() returned: ${audio.samples.size} samples, rate=${audio.sampleRate}")
        return Pair(audio.samples.size, audio.sampleRate)
    }
}

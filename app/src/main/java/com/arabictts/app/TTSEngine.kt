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

    data class InitResult(val success: Boolean, val error: String? = null)

    fun initArabic(modelPath: String, configPath: String, espeakDataDir: String): InitResult {
        return try {
            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelPath,
                tokens = "",
                dataDir = espeakDataDir,
                dictDir = ""
            )
            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,
                debug = false,
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
        }
    }

    fun initEnglish(modelPath: String, configPath: String, espeakDataDir: String): InitResult {
        return try {
            val vitsConfig = OfflineTtsVitsModelConfig(
                model = modelPath,
                tokens = "",
                dataDir = espeakDataDir,
                dictDir = ""
            )
            val modelConfig = OfflineTtsModelConfig(
                vits = vitsConfig,
                numThreads = 2,
                debug = false,
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
                val audio = tts.generate(
                    text = segment.text,
                    sid = 0,
                    speed = speed
                )

                if (!isPlaying) break

                playAudio(audio.samples, audio.sampleRate)
            } catch (e: Exception) {
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
}

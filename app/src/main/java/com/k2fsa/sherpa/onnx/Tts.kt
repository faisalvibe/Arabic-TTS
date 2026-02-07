// Copyright (c) 2023 Xiaomi Corporation
// Adapted for Arabic-TTS project, matching sherpa-onnx v1.10.31 native libs
package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class OfflineTtsVitsModelConfig(
    var model: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var noiseScale: Float = 0.667f,
    var noiseScaleW: Float = 0.8f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsMatchaModelConfig(
    var acousticModel: String = "",
    var vocoder: String = "",
    var lexicon: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var dictDir: String = "",
    var noiseScale: Float = 1.0f,
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsKokoroModelConfig(
    var model: String = "",
    var voices: String = "",
    var tokens: String = "",
    var dataDir: String = "",
    var lexicon: String = "",
    var lang: String = "",
    var dictDir: String = "",
    var lengthScale: Float = 1.0f,
)

data class OfflineTtsModelConfig(
    var vits: OfflineTtsVitsModelConfig = OfflineTtsVitsModelConfig(),
    var matcha: OfflineTtsMatchaModelConfig = OfflineTtsMatchaModelConfig(),
    var kokoro: OfflineTtsKokoroModelConfig = OfflineTtsKokoroModelConfig(),
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)

data class OfflineTtsConfig(
    var model: OfflineTtsModelConfig = OfflineTtsModelConfig(),
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var maxNumSentences: Int = 1,
)

class GeneratedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    fun save(filename: String) =
        saveImpl(filename = filename, samples = samples, sampleRate = sampleRate)

    private external fun saveImpl(
        filename: String,
        samples: FloatArray,
        sampleRate: Int
    ): Boolean
}

class OfflineTts(
    assetManager: AssetManager? = null,
    var config: OfflineTtsConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    fun sampleRate() = getSampleRate(ptr)

    fun numSpeakers() = getNumSpeakers(ptr)

    fun generate(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio {
        return generateImpl(ptr, text = text, sid = sid, speed = speed)
    }

    fun generateWithCallback(
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Int
    ): GeneratedAudio {
        return generateWithCallbackImpl(
            ptr,
            text = text,
            sid = sid,
            speed = speed,
            callback = callback
        )
    }

    fun allocate(assetManager: AssetManager? = null) {
        if (ptr == 0L) {
            ptr = if (assetManager != null) {
                newFromAsset(assetManager, config)
            } else {
                newFromFile(config)
            }
        }
    }

    fun free() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OfflineTtsConfig,
    ): Long

    private external fun newFromFile(
        config: OfflineTtsConfig,
    ): Long

    private external fun delete(ptr: Long)
    private external fun getSampleRate(ptr: Long): Int
    private external fun getNumSpeakers(ptr: Long): Int

    private external fun generateImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f
    ): GeneratedAudio

    private external fun generateWithCallbackImpl(
        ptr: Long,
        text: String,
        sid: Int = 0,
        speed: Float = 1.0f,
        callback: (samples: FloatArray) -> Int
    ): GeneratedAudio

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}

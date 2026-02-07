package com.arabictts.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arabictts.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var modelManager: ModelManager
    private val ttsEngine = TTSEngine()
    private var isSpeaking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelManager = ModelManager(this)

        setupUI()
        checkModels()
    }

    private fun setupUI() {
        binding.btnSpeak.setOnClickListener {
            val text = binding.etInput.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Please enter text / الرجاء إدخال النص", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isSpeaking) {
                stopSpeaking()
            } else {
                speak(text)
            }
        }

        binding.btnStop.setOnClickListener {
            stopSpeaking()
        }

        binding.btnDownload.setOnClickListener {
            downloadModels()
        }

        // Speed slider
        binding.sliderSpeed.addOnChangeListener { _, value, _ ->
            binding.tvSpeedValue.text = String.format("%.1fx", value)
        }

        // Sample text buttons
        binding.chipArabic.setOnClickListener {
            binding.etInput.setText("مرحباً بكم في تطبيق تحويل النص إلى كلام باللغة العربية")
        }

        binding.chipEnglish.setOnClickListener {
            binding.etInput.setText("Welcome to the Arabic and English text to speech application")
        }

        binding.chipMixed.setOnClickListener {
            binding.etInput.setText("مرحباً Hello كيف حالك How are you")
        }
    }

    private fun checkModels() {
        if (modelManager.areModelsReady()) {
            showReady()
            initTTSEngines()
        } else {
            showDownloadNeeded()
        }
    }

    private fun downloadModels() {
        binding.downloadSection.visibility = View.VISIBLE
        binding.btnDownload.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvStatus.text = "Starting download..."

        lifecycleScope.launch {
            try {
                modelManager.downloadAllModels { progress ->
                    launch(Dispatchers.Main) {
                        val percent = if (progress.totalBytes > 0) {
                            (progress.bytesDownloaded * 100 / progress.totalBytes).toInt()
                        } else 0
                        binding.tvStatus.text = "${progress.component}: $percent%"
                        binding.progressBar.progress = percent
                    }
                }

                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Models downloaded! Initializing..."
                    initTTSEngines()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Download failed: ${e.message}"
                    binding.btnDownload.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        "Download failed. Check your internet connection.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun initTTSEngines() {
        lifecycleScope.launch(Dispatchers.IO) {
            val arModel = modelManager.getArabicModelFiles()
            val enModel = modelManager.getEnglishModelFiles()
            val espeakData = modelManager.getEspeakDataDir()

            if (arModel == null || enModel == null || espeakData == null) {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Models not found. Please download first."
                    showDownloadNeeded()
                }
                return@launch
            }

            val arResult = ttsEngine.initArabic(arModel.modelPath, arModel.configPath, espeakData)
            val enResult = ttsEngine.initEnglish(enModel.modelPath, enModel.configPath, espeakData)

            withContext(Dispatchers.Main) {
                if (arResult.success && enResult.success) {
                    showReady()
                    binding.tvStatus.text = "Ready! / !جاهز"
                } else {
                    val errors = listOfNotNull(
                        arResult.error?.let { "Arabic: $it" },
                        enResult.error?.let { "English: $it" }
                    ).joinToString("\n")
                    binding.tvStatus.text = "Init failed:\n$errors"
                }
            }
        }
    }

    private fun speak(text: String) {
        val speed = binding.sliderSpeed.value

        isSpeaking = true
        binding.btnSpeak.text = "⏳"
        binding.btnSpeak.isEnabled = false
        binding.btnStop.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                ttsEngine.speak(
                    text = text,
                    speed = speed,
                    onSegmentStart = { segment ->
                        launch(Dispatchers.Main) {
                            val langLabel = when (segment.language) {
                                LanguageDetector.Language.ARABIC -> "🟢 Arabic"
                                LanguageDetector.Language.ENGLISH -> "🔵 English"
                            }
                            binding.tvStatus.text = "$langLabel: ${segment.text.take(50)}"
                        }
                    }
                )
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "TTS error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isSpeaking = false
                    binding.btnSpeak.text = getString(R.string.speak)
                    binding.btnSpeak.isEnabled = true
                    binding.btnStop.visibility = View.GONE
                    binding.tvStatus.text = "Ready! / !جاهز"
                }
            }
        }
    }

    private fun stopSpeaking() {
        ttsEngine.stop()
        isSpeaking = false
        binding.btnSpeak.text = getString(R.string.speak)
        binding.btnSpeak.isEnabled = true
        binding.btnStop.visibility = View.GONE
        binding.tvStatus.text = "Stopped"
    }

    private fun showReady() {
        binding.downloadSection.visibility = View.GONE
        binding.ttsSection.visibility = View.VISIBLE
        binding.progressBar.visibility = View.GONE
    }

    private fun showDownloadNeeded() {
        binding.downloadSection.visibility = View.VISIBLE
        binding.ttsSection.visibility = View.GONE
        binding.btnDownload.isEnabled = true
    }

    override fun onDestroy() {
        ttsEngine.release()
        super.onDestroy()
    }
}

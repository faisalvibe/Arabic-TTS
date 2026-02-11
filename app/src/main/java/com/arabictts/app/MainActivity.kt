package com.arabictts.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
    private var pendingOnnxUri: Uri? = null

    // Step 1: Pick the .onnx model file
    private val pickOnnxFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingOnnxUri = uri
            Toast.makeText(this, getString(R.string.pick_config_file), Toast.LENGTH_LONG).show()
            pickConfigFile.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
        }
    }

    // Step 2: Pick the .onnx.json config file
    private val pickConfigFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val onnxUri = pendingOnnxUri
        if (uri != null && onnxUri != null) {
            importCustomVoice(onnxUri, uri)
        }
        pendingOnnxUri = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        modelManager = ModelManager(this)

        setupUI()

        val skipDebug = intent.getBooleanExtra("skip_debug", false)
        if (skipDebug && modelManager.areModelsReady()) {
            // Coming back from DebugActivity - go straight to init
            showReady()
            binding.tvStatus.text = "Initializing TTS engines..."
            initTTSEngines()
        } else {
            checkModels()
        }
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

        // Custom voice buttons
        binding.btnImportVoice.setOnClickListener {
            Toast.makeText(this, getString(R.string.pick_onnx_file), Toast.LENGTH_LONG).show()
            pickOnnxFile.launch(arrayOf("application/octet-stream", "*/*"))
        }

        binding.btnRemoveVoice.setOnClickListener {
            modelManager.removeCustomEnglishModelFiles()
            updateCustomVoiceStatus()
            reinitEnglishTTS()
        }
    }

    private fun checkModels() {
        if (modelManager.areModelsReady()) {
            // Models exist - show debug screen first so user can copy info before init
            launchDebugActivity()
        } else {
            showDownloadNeeded()
        }
    }

    private fun launchDebugActivity() {
        val intent = Intent(this, DebugActivity::class.java)
        startActivity(intent)
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
                    binding.tvStatus.text = "Download complete! Opening debug info..."
                    // Show debug activity so user can see files before init
                    launchDebugActivity()
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
        // Set up breadcrumb file for crash diagnostics
        val breadcrumbFile = java.io.File(filesDir, "tts_breadcrumb.txt")
        breadcrumbFile.writeText("=== TTS Breadcrumbs ===\n")
        ttsEngine.breadcrumbFile = breadcrumbFile

        lifecycleScope.launch(Dispatchers.IO) {
            // Arabic always uses default Kareem voice
            val arModel = modelManager.getArabicModelFiles()
            // English uses custom voice if available, otherwise default Amy
            val isCustomEnglish = modelManager.getCustomEnglishModelFiles() != null
            val enModel = modelManager.getCustomEnglishModelFiles()
                ?: modelManager.getEnglishModelFiles()
            val espeakData = modelManager.getEspeakDataDir()

            if (arModel == null || enModel == null || espeakData == null) {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Models not found. Please download first."
                    showDownloadNeeded()
                }
                return@launch
            }

            val enModelName = java.io.File(enModel.modelPath).name
            breadcrumbFile.appendText("Arabic model: ar_JO-kareem-low.onnx (default)\n")
            breadcrumbFile.appendText("English model: $enModelName (custom=$isCustomEnglish)\n")
            breadcrumbFile.appendText("English path: ${enModel.modelPath}\n")
            breadcrumbFile.appendText("English tokens: ${enModel.tokensPath}\n")

            val arResult = ttsEngine.initArabic(arModel.modelPath, arModel.tokensPath, espeakData)
            val enResult = ttsEngine.initEnglish(enModel.modelPath, enModel.tokensPath, espeakData)

            breadcrumbFile.appendText("Arabic init: ${if (arResult.success) "OK" else "FAIL: ${arResult.error}"}\n")
            breadcrumbFile.appendText("English init: ${if (enResult.success) "OK" else "FAIL: ${enResult.error}"}\n")

            if (arResult.success && enResult.success) {
                // Test generate with simple text to verify native inference works
                try {
                    breadcrumbFile.appendText("Testing generate with 'test'...\n")
                    val testAudio = ttsEngine.testGenerate("test")
                    breadcrumbFile.appendText("Test generate OK: ${testAudio?.first ?: 0} samples, rate=${testAudio?.second ?: 0}\n")
                } catch (e: Throwable) {
                    breadcrumbFile.appendText("Test generate FAILED: ${e.javaClass.name}: ${e.message}\n")
                }
            }

            withContext(Dispatchers.Main) {
                if (arResult.success && enResult.success) {
                    showReady()
                    updateCustomVoiceStatus()
                    val enLabel = if (isCustomEnglish) "Custom" else "Amy"
                    binding.tvStatus.text = "Ready - AR: Kareem | EN: $enLabel"
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

    private fun importCustomVoice(onnxUri: Uri, configUri: Uri) {
        binding.tvStatus.text = "Importing custom voice..."
        binding.btnImportVoice.isEnabled = false

        lifecycleScope.launch {
            val result = modelManager.importCustomEnglishModel(onnxUri, configUri)
            withContext(Dispatchers.Main) {
                binding.btnImportVoice.isEnabled = true
                if (result.isSuccess) {
                    binding.tvStatus.text = "Custom voice imported! Reinitializing..."
                    updateCustomVoiceStatus()
                    reinitEnglishTTS()
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    binding.tvStatus.text = "Import failed: $error"
                    Toast.makeText(
                        this@MainActivity,
                        "Import failed: $error",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun updateCustomVoiceStatus() {
        if (modelManager.hasCustomEnglishModel()) {
            binding.tvCustomVoiceStatus.text = getString(R.string.custom_voice_active)
            binding.btnRemoveVoice.visibility = View.VISIBLE
        } else {
            binding.tvCustomVoiceStatus.text = getString(R.string.custom_voice_none)
            binding.btnRemoveVoice.visibility = View.GONE
        }
    }

    private fun reinitEnglishTTS() {
        lifecycleScope.launch(Dispatchers.IO) {
            val espeakData = modelManager.getEspeakDataDir()
            if (espeakData == null) {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Reinit failed: espeak data not found"
                }
                return@launch
            }

            // Use custom model if available, otherwise default Amy
            val isCustom = modelManager.getCustomEnglishModelFiles() != null
            val enModel = modelManager.getCustomEnglishModelFiles()
                ?: modelManager.getEnglishModelFiles()
            if (enModel == null) {
                withContext(Dispatchers.Main) {
                    binding.tvStatus.text = "Reinit failed: no English model found"
                }
                return@launch
            }

            val modelName = java.io.File(enModel.modelPath).name
            withContext(Dispatchers.Main) {
                binding.tvStatus.text = "Loading: $modelName..."
            }

            val enResult = ttsEngine.initEnglish(enModel.modelPath, enModel.tokensPath, espeakData)

            withContext(Dispatchers.Main) {
                if (enResult.success) {
                    val enLabel = if (isCustom) "Custom" else "Amy"
                    binding.tvStatus.text = "Ready - AR: Kareem | EN: $enLabel"
                } else {
                    binding.tvStatus.text = "English init failed: ${enResult.error}\nModel: $modelName"
                }
            }
        }
    }

    private fun speak(text: String) {
        val speed = binding.sliderSpeed.value
        val isCustomEnglish = modelManager.hasCustomEnglishModel()

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
                            val voiceLabel = when (segment.language) {
                                LanguageDetector.Language.ARABIC -> "🟢 Arabic (Kareem)"
                                LanguageDetector.Language.ENGLISH -> {
                                    val voiceName = if (isCustomEnglish) "Custom" else "Amy"
                                    "🔵 English ($voiceName)"
                                }
                            }
                            binding.tvStatus.text = "$voiceLabel: ${segment.text.take(50)}"
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
                    val enLabel = if (isCustomEnglish) "Custom" else "Amy"
                    binding.tvStatus.text = "Ready - AR: Kareem | EN: $enLabel"
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

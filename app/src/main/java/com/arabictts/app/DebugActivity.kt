package com.arabictts.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Typeface
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * Shows complete debug info about downloaded model files.
 * Runs in a separate process so it survives native crashes.
 * The user can copy all info before attempting TTS init.
 */
class DebugActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = gatherDebugInfo()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Debug Info - Copy & Share"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }

        val subtitle = TextView(this).apply {
            text = "Copy this info and share it with the developer.\nThen tap 'Start TTS' to attempt initialization."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val debugText = TextView(this).apply {
            text = report
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFFF0F0F0.toInt())
        }
        scrollView.addView(debugText)

        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }

        val copyButton = Button(this).apply {
            text = "Copy Info"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Debug Info", report))
                Toast.makeText(this@DebugActivity, "Copied!", Toast.LENGTH_SHORT).show()
            }
        }

        val startButton = Button(this).apply {
            text = "Start TTS"
            setOnClickListener {
                val intent = Intent(this@DebugActivity, MainActivity::class.java)
                intent.putExtra("skip_debug", true)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                finish()
            }
        }

        buttonLayout.addView(copyButton)
        buttonLayout.addView(startButton)

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(scrollView)
        layout.addView(buttonLayout)

        setContentView(layout)
    }

    private fun gatherDebugInfo(): String = buildString {
        appendLine("=== ARABIC TTS DEBUG INFO ===")
        appendLine("Time: ${java.util.Date()}")
        appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
        appendLine()

        val filesDir = File(this@DebugActivity.filesDir.absolutePath.replace("/:debug", ""))
        // The files are in the main process's filesDir, not the :debug process
        // On most devices this is the same path without the process suffix
        val possibleDirs = listOf(
            this@DebugActivity.filesDir,
            filesDir,
            File("/data/data/com.arabictts.app/files"),
            File("/data/user/0/com.arabictts.app/files")
        )

        var modelsDir: File? = null
        var espeakDir: File? = null

        for (dir in possibleDirs) {
            val m = File(dir, "models")
            val e = File(dir, "espeak-ng-data")
            if (m.exists() || e.exists()) {
                modelsDir = m
                espeakDir = e
                appendLine("Found files in: ${dir.absolutePath}")
                break
            }
        }

        appendLine()
        appendLine("--- Models Directory ---")
        if (modelsDir?.exists() == true) {
            appendLine("Path: ${modelsDir.absolutePath}")
            modelsDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                appendLine("  ${f.name}  (${f.length()} bytes)")
            }
        } else {
            appendLine("NOT FOUND (tried: ${possibleDirs.map { File(it, "models").absolutePath }})")
        }

        appendLine()
        appendLine("--- espeak-ng-data Directory ---")
        if (espeakDir?.exists() == true) {
            appendLine("Path: ${espeakDir.absolutePath}")
            val files = espeakDir.listFiles()
            appendLine("Top-level entries: ${files?.size ?: 0}")
            files?.sortedBy { it.name }?.forEach { f ->
                if (f.isDirectory) {
                    val children = f.listFiles()?.size ?: 0
                    appendLine("  ${f.name}/  ($children items)")
                } else {
                    appendLine("  ${f.name}  (${f.length()} bytes)")
                }
            }
        } else {
            appendLine("NOT FOUND")
        }

        // Show tokens.txt content preview (with hex dump for debugging format issues)
        appendLine()
        appendLine("--- tokens.txt Preview ---")
        for (name in listOf("ar_JO-kareem-low-tokens.txt", "en_US-amy-low-tokens.txt")) {
            val f = modelsDir?.let { File(it, name) }
            appendLine("[$name]")
            if (f?.exists() == true) {
                appendLine("  Size: ${f.length()} bytes")
                val lines = f.readLines()
                appendLine("  Total lines: ${lines.size}")
                appendLine("  First 8 lines (with hex):")
                lines.take(8).forEachIndexed { idx, line ->
                    val hex = line.toByteArray().joinToString(" ") { "%02X".format(it) }
                    appendLine("    L$idx: [$line]  hex: $hex")
                }
                appendLine("  Last 3 lines:")
                lines.takeLast(3).forEachIndexed { idx, line ->
                    appendLine("    L${lines.size - 3 + idx}: [$line]")
                }
            } else {
                appendLine("  NOT FOUND")
            }
        }

        // Show JSON config preview
        appendLine()
        appendLine("--- Model Config Preview ---")
        for (name in listOf("ar_JO-kareem-low.onnx.json", "en_US-amy-low.onnx.json")) {
            val f = modelsDir?.let { File(it, name) }
            appendLine("[$name]")
            if (f?.exists() == true) {
                appendLine("  Size: ${f.length()} bytes")
                val content = f.readText()
                // Show key fields
                try {
                    val json = org.json.JSONObject(content)
                    appendLine("  audio.sample_rate: ${json.optJSONObject("audio")?.optInt("sample_rate")}")
                    appendLine("  espeak.voice: ${json.optJSONObject("espeak")?.optString("voice")}")
                    appendLine("  num_speakers: ${json.optInt("num_speakers")}")
                    val phonemeMap = json.optJSONObject("phoneme_id_map")
                    appendLine("  phoneme_id_map keys: ${phonemeMap?.length() ?: "null"}")
                } catch (e: Exception) {
                    appendLine("  Parse error: ${e.message}")
                    appendLine("  First 200 chars: ${content.take(200)}")
                }
            } else {
                appendLine("  NOT FOUND")
            }
        }

        // Show ONNX metadata injection status
        appendLine()
        appendLine("--- ONNX Metadata Patch Status ---")
        for (name in listOf("ar_JO-kareem-low.onnx", "en_US-amy-low.onnx")) {
            appendLine("[$name]")
            val onnxFile = modelsDir?.let { File(it, name) }
            val patchedFile = modelsDir?.let { File(it, "$name.patched") }
            val injectLog = modelsDir?.let { File(it, "$name.inject_log") }
            appendLine("  .patched exists: ${patchedFile?.exists()}")
            appendLine("  .inject_log exists: ${injectLog?.exists()}")
            if (injectLog?.exists() == true) {
                appendLine("  inject_log: ${injectLog.readText().take(500)}")
            }
            // Show last 100 bytes of ONNX file to verify metadata was appended
            if (onnxFile?.exists() == true && onnxFile.length() > 100) {
                try {
                    val raf = java.io.RandomAccessFile(onnxFile, "r")
                    raf.seek(onnxFile.length() - 100)
                    val tail = ByteArray(100)
                    raf.readFully(tail)
                    raf.close()
                    val hex = tail.joinToString(" ") { "%02X".format(it) }
                    appendLine("  tail 100 bytes: $hex")
                } catch (e: Exception) {
                    appendLine("  tail read error: ${e.message}")
                }
            }
        }

        // Check native libs
        appendLine()
        appendLine("--- Native Libraries ---")
        try {
            System.loadLibrary("sherpa-onnx-jni")
            appendLine("sherpa-onnx-jni: LOADED OK")
        } catch (e: UnsatisfiedLinkError) {
            appendLine("sherpa-onnx-jni: FAILED - ${e.message}")
        } catch (e: Exception) {
            appendLine("sherpa-onnx-jni: ERROR - ${e.message}")
        }

        // Show TTS breadcrumbs (last generate() call trace)
        appendLine()
        appendLine("--- TTS Breadcrumbs ---")
        val breadcrumbPaths = listOf(
            File(this@DebugActivity.filesDir.absolutePath.replace("/:debug", ""), "tts_breadcrumb.txt"),
            File("/data/user/0/com.arabictts.app/files/tts_breadcrumb.txt")
        )
        var foundBreadcrumb = false
        for (bf in breadcrumbPaths) {
            if (bf.exists()) {
                appendLine(bf.readText().take(2000))
                foundBreadcrumb = true
                break
            }
        }
        if (!foundBreadcrumb) {
            appendLine("No breadcrumbs found (generate() not yet called)")
        }

        // Show previous native crash info if available
        appendLine()
        appendLine("--- Previous Crash Info ---")
        val crashFile = File(
            this@DebugActivity.filesDir.absolutePath.replace("/:debug", ""),
            "native_crash_log.txt"
        )
        val crashPaths = listOf(
            crashFile,
            File("/data/data/com.arabictts.app/files/native_crash_log.txt"),
            File("/data/user/0/com.arabictts.app/files/native_crash_log.txt")
        )
        var foundCrash = false
        for (cf in crashPaths) {
            if (cf.exists()) {
                appendLine(cf.readText().take(4000))
                foundCrash = true
                break
            }
        }
        if (!foundCrash) {
            appendLine("No previous crash data found (file)")
        }

        // Capture logcat from crashed main process - sherpa-onnx logs errors before exit(-1)
        appendLine()
        appendLine("--- Recent Logcat (errors/warnings) ---")
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "150", "*:W"))
            val logOutput = process.inputStream.bufferedReader().readText()
            process.waitFor()
            // Filter for relevant entries
            val relevantLines = logOutput.lines().filter { line ->
                line.contains("sherpa", ignoreCase = true) ||
                line.contains("onnx", ignoreCase = true) ||
                line.contains("tts", ignoreCase = true) ||
                line.contains("FATAL", ignoreCase = true) ||
                line.contains("signal", ignoreCase = true) ||
                line.contains("exit", ignoreCase = true) ||
                line.contains("crash", ignoreCase = true) ||
                line.contains("Arabic", ignoreCase = true) ||
                line.contains("piper", ignoreCase = true) ||
                line.contains("espeak", ignoreCase = true) ||
                line.contains("native", ignoreCase = true) ||
                line.contains("Duplicated", ignoreCase = true)
            }
            if (relevantLines.isNotEmpty()) {
                relevantLines.takeLast(50).forEach { appendLine(it) }
            } else {
                appendLine("No relevant log entries found")
                // Show last 30 warning/error lines as fallback
                val lastLines = logOutput.lines().takeLast(30)
                lastLines.forEach { appendLine(it) }
            }
        } catch (e: Exception) {
            appendLine("Could not read logcat: ${e.message}")
        }

        appendLine()
        appendLine("=== END DEBUG INFO ===")
    }
}

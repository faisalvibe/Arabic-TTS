package com.arabictts.app

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class TTSApplication : Application() {

    companion object {
        private const val TAG = "TTSApplication"
    }

    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Build crash report
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                val stackTrace = sw.toString()

                val report = buildString {
                    appendLine("=== ARABIC TTS CRASH REPORT ===")
                    appendLine("Time: ${java.util.Date()}")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Exception: ${throwable.javaClass.name}")
                    appendLine("Message: ${throwable.message}")
                    appendLine()
                    appendLine("--- Stack Trace ---")
                    appendLine(stackTrace)
                    appendLine()
                    appendLine("--- Device Info ---")
                    appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
                    appendLine()
                    appendLine("--- App Files ---")
                    try {
                        val modelsDir = File(filesDir, "models")
                        val espeakDir = File(filesDir, "espeak-ng-data")
                        appendLine("models dir exists: ${modelsDir.exists()}")
                        if (modelsDir.exists()) {
                            modelsDir.listFiles()?.forEach { f ->
                                appendLine("  ${f.name} (${f.length()} bytes)")
                            }
                        }
                        appendLine("espeak-ng-data dir exists: ${espeakDir.exists()}")
                        if (espeakDir.exists()) {
                            appendLine("  files: ${espeakDir.listFiles()?.map { it.name }}")
                        }
                    } catch (e: Exception) {
                        appendLine("Error listing files: ${e.message}")
                    }
                    appendLine("=== END CRASH REPORT ===")
                }

                // Save to file
                val crashFile = File(getExternalFilesDir(null) ?: filesDir, "crash_log.txt")
                crashFile.writeText(report)
                Log.e(TAG, "Crash report saved to: ${crashFile.absolutePath}")
                Log.e(TAG, report)

                // Launch crash viewer activity
                val intent = Intent(this, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("crash_report", report)
                }
                startActivity(intent)

                // Give time for the activity to start
                Thread.sleep(500)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle crash", e)
            }

            // Kill the process
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }
}

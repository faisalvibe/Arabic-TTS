package com.arabictts.app

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

class TTSApplication : Application() {

    companion object {
        private const val TAG = "TTSApplication"
        private const val NATIVE_CRASH_FILE = "native_crash_log.txt"
    }

    override fun onCreate() {
        super.onCreate()
        captureNativeCrashInfo()
        setupCrashHandler()
    }

    /**
     * Uses ApplicationExitInfo (API 30+) to capture native crash details
     * from the previous run. Saves the info to a file that DebugActivity reads.
     */
    private fun captureNativeCrashInfo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exitReasons = am.getHistoricalProcessExitReasons(packageName, 0, 5)

            if (exitReasons.isEmpty()) {
                File(filesDir, NATIVE_CRASH_FILE).delete()
                return
            }

            val report = buildString {
                appendLine("=== PREVIOUS EXIT INFO (ApplicationExitInfo) ===")
                appendLine("Total exit records: ${exitReasons.size}")
                for (info in exitReasons) {
                    val reasonStr = when (info.reason) {
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                        ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
                        ApplicationExitInfo.REASON_ANR -> "ANR"
                        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                        ApplicationExitInfo.REASON_OTHER -> "OTHER"
                        else -> "UNKNOWN_${info.reason}"
                    }
                    appendLine()
                    appendLine("--- Exit Reason: $reasonStr (code ${info.reason}) ---")
                    appendLine("Time: ${java.util.Date(info.timestamp)}")
                    appendLine("PID: ${info.pid}")
                    appendLine("Process: ${info.processName}")
                    appendLine("Importance: ${info.importance}")
                    appendLine("Status/Signal: ${info.status}")
                    appendLine("Description: ${info.description}")
                    appendLine("PSS: ${info.pss} KB, RSS: ${info.rss} KB")

                    // Try to read the trace/tombstone
                    try {
                        val traceStream = info.traceInputStream
                        if (traceStream != null) {
                            val trace = traceStream.bufferedReader().readText()
                            appendLine()
                            appendLine("--- Trace/Tombstone ---")
                            appendLine(trace.take(8000)) // Limit size
                            traceStream.close()
                        }
                    } catch (e: Exception) {
                        appendLine("Could not read trace: ${e.message}")
                    }
                }
                appendLine()
                appendLine("=== END PREVIOUS EXIT INFO ===")
            }

            File(filesDir, NATIVE_CRASH_FILE).writeText(report)
            Log.i(TAG, "Captured previous exit info: ${exitReasons.size} entries")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture native crash info", e)
        }
    }

    private fun setupCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
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
                    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
                    appendLine("=== END CRASH REPORT ===")
                }

                val crashFile = File(getExternalFilesDir(null) ?: filesDir, "crash_log.txt")
                crashFile.writeText(report)
                Log.e(TAG, report)

                val intent = Intent(this, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("crash_report", report)
                }
                startActivity(intent)
                Thread.sleep(500)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle crash", e)
            }

            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }
}

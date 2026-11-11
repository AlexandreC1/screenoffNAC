package dev.screenoff.recorder

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small on-device log the owner can share from Settings. Everything also goes
 * to logcat under the tag ScreenOff. Kept under about 256 KB.
 */
object Diag {
    private const val TAG = "ScreenOff"
    private const val MAX_BYTES = 256L * 1024
    private var file: File? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        file = file(context).also { it.parentFile?.mkdirs() }
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        log("app start, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT}), app $version")
    }

    fun file(context: Context): File = File(context.filesDir, "logs/diag.txt")

    @Synchronized
    fun log(message: String, error: Throwable? = null) {
        if (error == null) Log.i(TAG, message) else Log.w(TAG, message, error)
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast((MAX_BYTES / 2).toInt()))
            val suffix = error?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""
            f.appendText("${stamp.format(Date())} $message$suffix\n")
        } catch (e: Exception) {
            // Logging must never break recording.
        }
    }
}

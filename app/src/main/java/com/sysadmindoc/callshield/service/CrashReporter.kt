package com.sysadmindoc.callshield.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.sysadmindoc.callshield.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Privacy-respecting local crash reporter.
 *
 * We deliberately do NOT ship Crashlytics / Sentry / Bugsnag — those send
 * crash telemetry off-device and conflict with CallShield's no-network-
 * phone-home stance (see README: "No API keys, no tracking"). Instead, this
 * captures uncaught exceptions to a local file the user can inspect and
 * optionally share via the "Report bug" flow in [MoreScreen].
 *
 * Behavior:
 *  - [install] hooks `Thread.setDefaultUncaughtExceptionHandler` once on
 *    [CallShieldApp.onCreate]. Subsequent installs are no-ops.
 *  - On uncaught exception we serialize `timestamp, build info, thread,
 *    stack trace` to `filesDir/crashes/crash_<epoch>.txt`, then chain to
 *    the previous handler so ART still crashes the process. We never
 *    swallow — swallowing breaks ANR recovery and hides bugs.
 *  - Files are rotated: we keep the 5 most recent and delete older ones
 *    to cap disk use (typical crash is < 8 KB).
 *  - [latestCrash] returns the most recent file or null.
 *  - [shareLatestCrash] builds a FileProvider share intent.
 *
 * Threading: the uncaught handler fires on whatever thread crashed. File
 * IO is synchronous (we're about to die anyway), and write failures are
 * swallowed — the priority is not breaking the original crash signal.
 */
object CrashReporter {

    private const val CRASH_DIR = "crashes"
    private const val KEEP_LATEST = 5
    private const val MAX_STACK_DEPTH = 200 // Guard against pathological chains

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                persistCrash(appContext, thread, throwable)
            } catch (_: Throwable) {
                // Never let our handler mask the original crash
            }
            // Chain to the default handler so ART still terminates the process.
            // If there was no previous handler (shouldn't happen on Android but
            // belt-and-suspenders) exit the process manually.
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }
    }

    /** @return the most recent crash log file, or null if none exist. */
    fun latestCrash(context: Context): File? {
        val dir = crashDir(context)
        if (!dir.exists()) return null
        return dir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }
    }

    /** Build a share intent for the latest crash. Returns null if there's nothing to share. */
    fun shareLatestCrashIntent(context: Context): Intent? {
        val file = latestCrash(context) ?: return null
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "CallShield crash log ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Delete all stored crash logs. Called from a user-facing "Clear crash logs" action. */
    fun clearAll(context: Context) {
        crashDir(context).listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun crashDir(context: Context): File = File(context.filesDir, CRASH_DIR)

    private fun persistCrash(context: Context, thread: Thread, throwable: Throwable) {
        val dir = crashDir(context).apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val fileName = "crash_${stamp}.txt"
        val file = File(dir, fileName)

        val stackWriter = StringWriter()
        PrintWriter(stackWriter).use { pw ->
            // Our own header — keep it small and parseable.
            pw.println("CallShield crash report")
            pw.println("=======================")
            pw.println("timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(stamp))}")
            pw.println("epoch_ms : $stamp")
            pw.println("versionName: ${BuildConfig.VERSION_NAME}")
            pw.println("versionCode: ${BuildConfig.VERSION_CODE}")
            pw.println("sdkInt   : ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
            pw.println("device   : ${Build.MANUFACTURER} ${Build.MODEL}")
            @Suppress("DEPRECATION") // Thread.id deprecated in Java 19+, but threadId is API 34+ only.
            val tid = thread.id
            pw.println("thread   : ${thread.name} [id=${tid}, priority=${thread.priority}]")
            pw.println()

            // Bounded stack dump to avoid pathological chains eating disk.
            var depth = 0
            var cause: Throwable? = throwable
            while (cause != null && depth < MAX_STACK_DEPTH) {
                if (depth == 0) {
                    pw.println("Exception: ${cause.javaClass.name}: ${cause.message}")
                } else {
                    pw.println("Caused by: ${cause.javaClass.name}: ${cause.message}")
                }
                cause.stackTrace.forEach { pw.println("    at $it") }
                cause = cause.cause
                depth++
            }
        }
        file.writeText(stackWriter.toString())
        rotate(dir)
    }

    private fun rotate(dir: File) {
        val files = dir.listFiles { f -> f.name.startsWith("crash_") && f.name.endsWith(".txt") }
            ?: return
        if (files.size <= KEEP_LATEST) return
        // Delete oldest first so the newest `KEEP_LATEST` survive.
        files.sortedBy { it.lastModified() }
            .take(files.size - KEEP_LATEST)
            .forEach { runCatching { it.delete() } }
    }
}

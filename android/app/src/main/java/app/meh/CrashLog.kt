package app.meh

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * If the app ever crashes, save what happened; on the next launch, show it with a Copy
 * button so it can be pasted into a bug report.
 */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val version = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName } catch (e: Exception) { "?" }
                file(app).writeText(
                    "meh $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "thread: ${thread.name}\n\n" + trace.take(20_000)
                )
            } catch (e: Throwable) { /* never fail while failing */ }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Show the report from the last crash, if there was one. */
    fun showIfAny(activity: Activity) {
        val f = file(activity)
        if (!f.exists()) return
        val report = try { f.readText() } catch (e: Exception) { "" }
        f.delete()
        if (report.isBlank()) return
        show(activity, "meh crashed last time", "Tap Copy and paste this to Claude so it can be fixed:\n\n$report", report)
    }

    fun show(activity: Activity, title: String, message: String, copyText: String? = null) {
        val body = TextView(activity).apply {
            text = message
            setTextIsSelectable(true)
            textSize = 12f
            setPadding(48, 24, 48, 0)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(android.widget.ScrollView(activity).apply { addView(body) })
            .setNegativeButton("Close", null)
        if (copyText != null) dialog.setPositiveButton("Copy") { _, _ ->
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("meh crash report", copyText))
            Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
        }
        dialog.show()
    }
}

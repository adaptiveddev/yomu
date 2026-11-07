package app.yomu.core.common

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local error journal: uncaught exceptions and logged errors are written to files inside the app
 * sandbox. Nothing is sent anywhere unless the user explicitly shares a report.
 */
object ErrorLog {
    private lateinit var dir: File

    @Volatile
    private var appContext: Context? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    /** The screen on display and the last steps before it (1.9.0): a stack trace alone rarely tells where it was. */
    @Volatile
    private var screen: String? = null
    private val trail = ArrayDeque<String>()

    fun install(context: Context, versionName: String) {
        appContext = context.applicationContext
        dir = File(context.filesDir, "logs").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write("crash", buildReport(versionName, thread.name, error)) }
            previous?.uncaughtException(thread, error)
        }
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority >= Log.ERROR) {
                    runCatching { write("error", buildReport(versionName, tag ?: "app", t, message)) }
                }
            }
        })
        trim()
    }

    private fun buildReport(version: String, where: String, t: Throwable?, message: String? = null) = buildString {
        appendLine("Yomu $version / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) / ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Where: $where")
        screen?.let { appendLine("Screen: $it") }
        val steps = synchronized(trail) { trail.toList() }
        if (steps.isNotEmpty()) appendLine("Recent: ${steps.joinToString(" → ")}")
        message?.let { appendLine(it) }
        t?.let { appendLine(Log.getStackTraceString(it)) }
    }

    /**
     * Remembers the screen on display for the reports: names and ids only (a route such as `TitleRoute` with its
     * numeric arguments), never search texts or titles.
     */
    fun screen(name: String) {
        if (name == screen) return
        screen = name
        synchronized(trail) {
            trail.addLast(name)
            while (trail.size > MAX_TRAIL) trail.removeFirst()
        }
    }

    /** The newest crash report of [version] (the running one), or null. */
    fun lastCrash(version: String): CrashReport? {
        val file = entries().firstOrNull { it.name.startsWith("crash-") } ?: return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        // Reports of an older version were written before the update: whatever crashed then may be fixed.
        if (!text.startsWith("Yomu $version ")) return null
        return CrashReport(file.lastModified(), text)
    }

    private var written = 0

    @Synchronized
    private fun write(kind: String, text: String) {
        if (!::dir.isInitialized) return
        File(dir, "$kind-${fmt.format(Date())}-${System.nanoTime() % 1000}.txt").writeText(text.take(MAX_CHARS))
        // An error repeating in a loop must not fill the storage: only the newest reports are kept.
        if (++written % TRIM_EVERY == 0) trim()
    }

    fun entries(): List<File> = if (::dir.isInitialized) dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty() else emptyList()

    fun clear() = entries().forEach { it.delete() }

    /** Logs an error that only the safety net caught ([ScreenErrors]) and tells the user that the action failed. */
    fun handled(t: Throwable) {
        Timber.e(t, "Unhandled error in screen work")
        val context = appContext ?: return
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(context, R.string.error_action_failed, Toast.LENGTH_LONG).show() }
        }
    }

    private fun trim() = entries().drop(MAX_FILES).forEach { it.delete() }

    private const val MAX_TRAIL = 6
    private const val MAX_FILES = 50
    private const val TRIM_EVERY = 10
    private const val MAX_CHARS = 64_000
}

/** A crash report from the error log: when it was written and its text. */
data class CrashReport(val time: Long, val text: String)

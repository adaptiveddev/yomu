package app.yomu.core.common

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Sharing helpers. Files are exposed through the app FileProvider (`<packageName>.files`). */
object Share {
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

    fun fileIntent(context: Context, file: File, mime: String, title: String? = null): Intent {
        val uri = uriFor(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun textIntent(text: String, title: String? = null): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun shareText(context: Context, text: String, title: String? = null): Boolean = start(context, textIntent(text, title))

    fun shareFile(context: Context, file: File, mime: String, title: String? = null): Boolean =
        runCatching { fileIntent(context, file, mime, title) }.getOrNull()?.let { start(context, it) } ?: false.also { noApp(context) }

    /** Directory for temporary shared files, cleaned on every call to keep the cache small. */
    fun shareDir(context: Context): File = File(context.cacheDir, "share").apply {
        mkdirs()
        listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 60 * 60 * 1000L }?.forEach { it.delete() }
    }

    /** Opens [url] in another app (browser, mail…); a link nothing can open (no browser, unknown scheme) shows a hint. */
    fun openUrl(context: Context, url: String): Boolean {
        val uri = runCatching { Uri.parse(url.trim()) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrBlank()) return false.also { noApp(context) }
        return start(context, Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Starts [intent]; returns false and shows a short hint instead of crashing when no app can handle it. */
    fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        noApp(context)
        false
    } catch (e: SecurityException) {
        noApp(context)
        false
    }

    private fun noApp(context: Context) {
        runCatching { Toast.makeText(context, R.string.share_no_app, Toast.LENGTH_SHORT).show() }
    }
}

package app.yomu.core.common

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Files {
    fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    fun size(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path!!).length()
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L
    }

    fun open(context: Context, uri: Uri): InputStream = when (uri.scheme) {
        "file", null -> File(uri.path!!).inputStream()
        else -> context.contentResolver.openInputStream(uri) ?: error("Cannot open $uri")
    }

    fun copy(context: Context, uri: Uri, dest: File): File {
        dest.parentFile?.mkdirs()
        open(context, uri).use { input -> dest.outputStream().use { input.copyTo(it, BUFFER) } }
        return dest
    }

    fun sanitize(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().take(MAX_NAME).ifBlank { "file" }

    fun sha1(input: InputStream, limit: Long = Long.MAX_VALUE): String {
        val md = MessageDigest.getInstance("SHA-1")
        val buf = ByteArray(BUFFER)
        var total = 0L
        input.use {
            while (total < limit) {
                val n = it.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
                total += n
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** MIME type for a file extension (falls back to application/octet-stream). */
    fun mimeOf(extension: String): String = when (extension.lowercase()) {
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        "cbz", "zip" -> "application/vnd.comicbook+zip"
        "cbr", "rar" -> "application/vnd.comicbook-rar"
        "cb7", "7z" -> "application/x-7z-compressed"
        "fb2" -> "application/x-fictionbook+xml"
        "txt" -> "text/plain"
        "html", "htm", "xhtml" -> "text/html"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "mobi", "azw", "azw3", "prc" -> "application/x-mobipocket-ebook"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "md" -> "text/markdown"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    fun dirSize(dir: File): Long = if (!dir.exists()) 0 else dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes / 1024.0
        var i = 0
        while (v >= 1024 && i < units.lastIndex) {
            v /= 1024
            i++
        }
        return "%.1f %s".format(v, units[i])
    }

    const val BUFFER = 64 * 1024
    private const val MAX_NAME = 120
}

package app.yomu.core.common

/** Four canonical states every screen renders: loading, data, empty and error. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Data<T>(val value: T) : UiState<T>
    data object Empty : UiState<Nothing>
    data class Error(val error: AppError) : UiState<Nothing>
}

fun <T> List<T>.toUiState(): UiState<List<T>> = if (isEmpty()) UiState.Empty else UiState.Data(this)

/** Errors that the UI knows how to describe with a localized message. */
sealed class AppError(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    class NoNetwork(cause: Throwable? = null) : AppError("No network", cause)
    class Http(val code: Int, message: String? = null) : AppError(message ?: "HTTP $code")
    class NeedsWebView(val url: String) : AppError("Verification required: $url")
    class CorruptedFile(message: String? = null, cause: Throwable? = null) : AppError(message, cause)
    class UnsupportedFormat(val format: String) : AppError("Unsupported format: $format")
    class NoPermission(message: String? = null) : AppError(message)

    /** The document no longer exists at its stored location (deleted, moved, or storage unmounted). */
    class FileMissing(val path: String? = null) : AppError("File not found: $path")
    class NoSpace : AppError("No space left")
    class NotConfigured(val what: String) : AppError("Not configured: $what")

    /** The site answered, but with data the source could not parse (the site changed or the source is outdated). */
    class BadData(cause: Throwable) : AppError(cause.message, cause)

    /** The site did not answer in time. */
    class Timeout(cause: Throwable) : AppError(cause.message, cause)

    /** The code of a source does not fit this app version (an outdated or broken extension: missing classes or methods). */
    class Incompatible(cause: Throwable) : AppError(cause.message, cause)
    /** A link could not become an audiobook; [reason] says why (each has its own explanation). */
    class AudioLink(val reason: Reason, cause: Throwable? = null) : AppError(reason.name, cause) {
        enum class Reason { NO_LINK, NOTEBOOKLM, YOUTUBE_BLOCKED, UNSUPPORTED }
    }

    class Unknown(cause: Throwable) : AppError(cause.message, cause)

    /** Technical description for the "Details" section of error screens (class and message of the root cause). */
    val technicalDetails: String?
        get() {
            val root = generateSequence(cause ?: this) { it.cause }.take(8).last()
            val text = listOfNotNull(root.javaClass.simpleName.ifBlank { null }, root.message?.takeIf { it.isNotBlank() }).joinToString(": ")
            return text.ifBlank { null }
        }

    companion object {
        /** Hook for exception types that core:common cannot see (e.g. source-api exceptions); set by the app. */
        @Volatile
        var mapper: ((Throwable) -> AppError?)? = null

        fun from(t: Throwable): AppError = when (t) {
            is AppError -> t
            else -> mapper?.invoke(t) ?: fallback(t)
        }

        /** "HTTP 404 for …" (Yomu), "HTTP error 403" (Mihon/Tachiyomi extensions' HttpException). */
        private val HTTP_MESSAGE = Regex("""^HTTP(?: error)? (\d{3})\b""", RegexOption.IGNORE_CASE)

        private fun fallback(t: Throwable): AppError {
            // Wrapped by network interceptors or source adapters (IOException(NoSuchMethodError), …).
            generateSequence(t) { it.cause }.take(8).firstOrNull { it is LinkageError }?.let { return Incompatible(it) }
            if (t.isA("kotlinx.serialization.SerializationException") || t.isA("org.json.JSONException") ||
                t.isA("com.google.gson.JsonParseException")
            ) {
                return BadData(t)
            }
            val code = t.message?.let { HTTP_MESSAGE.find(it.trim()) }?.groupValues?.get(1)?.toIntOrNull()
            if (code != null && code >= 400) return Http(code, t.message)
            if (generateSequence(t) { it.cause }.take(8).any(::isNoSpace)) return NoSpace()
            return fallbackByType(t)
        }

        private fun fallbackByType(t: Throwable): AppError = when (t) {
            is java.net.SocketTimeoutException -> Timeout(t)
            is java.net.UnknownHostException, is java.net.ConnectException -> NoNetwork(t)
            is java.util.zip.ZipException -> CorruptedFile(t.message, t)
            is SecurityException -> NoPermission(t.message)
            is java.io.FileNotFoundException -> FileMissing(t.message)
            is java.nio.file.NoSuchFileException -> FileMissing(t.file)
            is java.io.IOException -> Unknown(t)
            else -> Unknown(t)
        }
    }
}

inline fun <T> runCatchingApp(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(AppError.from(e))
}

/** A write failed because the storage is full: file systems report ENOSPC, SQLite a "disk is full" error. */
private fun isNoSpace(t: Throwable): Boolean {
    val m = t.message.orEmpty()
    return "ENOSPC" in m || "No space left on device" in m || t.isA("android.database.sqlite.SQLiteFullException")
}

/** Whether this throwable is an instance of the class [name], checked by name (the library may not be on the classpath). */
private fun Throwable.isA(name: String): Boolean =
    generateSequence<Class<*>>(javaClass) { it.superclass }.any { it.name == name }

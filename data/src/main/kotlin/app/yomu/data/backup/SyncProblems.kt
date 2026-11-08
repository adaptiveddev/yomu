package app.yomu.data.backup

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * A fail-closed safety stop raised by the sync engine. Unlike a plain [IOException], a problem
 * carries a stable machine kind and the affected item so the conflicts screen can list it and,
 * later, offer resolutions. Raising a problem never deletes or overwrites data.
 */
internal interface SyncProblem {
    /** Stable machine kind, e.g. "folder_legacy"; the settings UI maps it to a localized label. */
    val kindTag: String

    /** The affected book or folder title, if known; empty for anonymous records. */
    val subject: String

    /** Machine-addressable target of a resolution action (a bookId, ...), null if none. */
    val target: String? get() = null
}

/** One recorded sync problem, persisted for the conflicts screen. */
@Serializable
data class SyncProblemInfo(
    /** Stable identity of the problem within one failure, used by dismiss/resolve actions. */
    val id: String,
    val kind: String,
    val subject: String,
    val detail: String,
    val at: Long,
    /** Machine-addressable target of a resolution action (a bookId, a folder identity, ...), null if none. */
    val target: String? = null,
)

/**
 * Remembers the problems of failed sync attempts in `filesDir/sync/problems.json` so they survive
 * process death and appear on the conflicts screen until the next successful sync. The record is
 * disposable (a successful sync clears it), so a plain verified publish is used instead of
 * [android.util.AtomicFile]: java.nio's replace-existing move also works on hosts where
 * `File.renameTo` refuses to replace an existing file.
 */
internal class SyncProblemStore(context: Context) {
    private val dir = File(context.filesDir, "sync").apply { mkdirs() }
    private val file = File(dir, "problems.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun load(): List<SyncProblemInfo> = try {
        json.decodeFromString(Store.serializer(), SyncJson.text(readBytes())).problems
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun save(problems: List<SyncProblemInfo>) {
        val trimmed = problems.takeLast(MAX_PROBLEMS).map {
            it.copy(subject = it.subject.take(MAX_TEXT), detail = it.detail.take(MAX_TEXT))
        }
        val bytes = json.encodeToString(Store.serializer(), Store(trimmed)).toByteArray()
        val temp = File.createTempFile("problems-", ".new", dir)
        try {
            temp.writeBytes(bytes)
            if (temp.readBytes().size != bytes.size) throw IOException("Sync problem record was not written")
            java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            temp.delete()
            throw IOException("Cannot record the sync problem", e)
        }
    }

    @Synchronized
    fun clear() {
        try { file.delete() } catch (_: Exception) { }
    }

    private fun readBytes(): ByteArray {
        val bytes = file.readBytes()
        if (bytes.size > MAX_FILE_BYTES) throw IOException("Sync problem record is too large")
        return bytes
    }

    @Serializable
    private data class Store(val problems: List<SyncProblemInfo> = emptyList())

    companion object {
        private const val MAX_PROBLEMS = 50
        private const val MAX_TEXT = 500
        private const val MAX_FILE_BYTES = 1 shl 20
    }
}

internal fun SyncProblem.record(at: Long): SyncProblemInfo = SyncProblemInfo(
    id = "${kindTag}:${target ?: subject}",
    kind = kindTag,
    subject = subject,
    detail = (this as? IOException)?.message.orEmpty(),
    at = at,
    target = target,
)

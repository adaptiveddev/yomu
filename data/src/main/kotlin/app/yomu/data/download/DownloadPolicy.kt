package app.yomu.data.download

import app.yomu.core.common.AppError
import app.yomu.data.source.SourceCalls
import app.yomu.domain.model.DownloadItem
import app.yomu.domain.model.DownloadStatus

/** How a failed transfer should be treated. */
enum class FailureKind {
    /** The network or the server hiccuped (timeout, reset by a VPN switch, 5xx, 429): try again later. */
    TRANSIENT,

    /** Retrying cannot help (404, 403, broken file, unsupported content). */
    PERMANENT,

    /** The device storage is full: the whole queue should wait, not just this task. */
    NO_SPACE,
}

object RetryPolicy {
    const val MAX_DELAY_MS = 5 * 60_000L
    private const val MAX_JITTER = 0.25

    fun classify(e: Throwable): FailureKind = when {
        AppError.from(e) is AppError.NoSpace -> FailureKind.NO_SPACE
        SourceCalls.isTransient(e) -> FailureKind.TRANSIENT
        else -> FailureKind.PERMANENT
    }

    fun shouldRetry(kind: FailureKind, attempts: Int, maxRetries: Int): Boolean =
        kind == FailureKind.TRANSIENT && attempts < maxRetries

    /** [baseMs], 2×, 4×… capped at [MAX_DELAY_MS], plus up to 25% of it scaled by [jitter] (0..1). */
    fun delayMs(baseMs: Long, attempt: Int, jitter: Double = 0.0): Long {
        val d = (baseMs shl attempt.coerceIn(0, 10)).coerceIn(0, MAX_DELAY_MS)
        return d + (d * MAX_JITTER * jitter.coerceIn(0.0, 1.0)).toLong()
    }
}

/** Decisions about continuing a partly downloaded file with an HTTP Range request. */
object RangeResume {
    /** `If-Range` value that makes the server answer 200 (whole file) when the file changed since the first bytes. */
    fun ifRange(etag: String?, lastModified: String?): String? =
        etag?.takeIf { it.isNotBlank() && !it.startsWith("W/") } ?: lastModified?.takeIf { it.isNotBlank() }

    /**
     * True when the response body continues the partial file of [start] bytes. A 200 answer (Range ignored or the
     * file changed) or a 206 that starts elsewhere means the file must be written from scratch.
     */
    fun canAppend(start: Long, code: Int, contentRange: String?): Boolean {
        if (start <= 0 || code != 206) return false
        val from = contentRange?.trim()?.removePrefix("bytes")?.trim()?.substringBefore('-')?.trim() ?: return true
        return from.toLongOrNull() == start
    }
}

/** Where a task is, for the combined (chapters, books, audiobooks) progress. [fraction] is null while unknown. */
data class TaskProgress(val state: State, val fraction: Float?, val createdAt: Long, val updatedAt: Long) {
    enum class State { RUNNING, QUEUED, PAUSED, FAILED, DONE }

    companion object {
        fun of(item: DownloadItem): TaskProgress? {
            val state = when (item.status) {
                DownloadStatus.RUNNING -> State.RUNNING
                DownloadStatus.QUEUED -> State.QUEUED
                DownloadStatus.PAUSED -> State.PAUSED
                DownloadStatus.FAILED -> State.FAILED
                DownloadStatus.DONE -> State.DONE
                DownloadStatus.CANCELLED -> return null
            }
            val known = item.pagesTotal > 0 || item.bytesTotal > 0
            return TaskProgress(state, if (known) item.progress.coerceIn(0f, 1f) else null, item.createdAt, item.updatedAt)
        }
    }
}

/** What the library's downloads button shows. */
data class DownloadSummary(val running: Int = 0, val queued: Int = 0, val paused: Int = 0, val failed: Int = 0, val fraction: Float? = null) {
    /** Items still to download (waiting, running or paused). */
    val remaining: Int get() = running + queued + paused

    /** Something needs attention or is in progress: the button is shown prominently. */
    val visible: Boolean get() = remaining + failed > 0

    /** True while data is (or will be) flowing. */
    val active: Boolean get() = running + queued > 0

    companion object {
        /**
         * Progress of the current batch: unfinished tasks plus the ones finished since the oldest of them was added,
         * so the ring does not jump back when a task completes. Null fraction: nothing measurable yet.
         */
        fun of(tasks: List<TaskProgress>): DownloadSummary {
            val open = tasks.filter { it.state == TaskProgress.State.RUNNING || it.state == TaskProgress.State.QUEUED || it.state == TaskProgress.State.PAUSED }
            val failed = tasks.count { it.state == TaskProgress.State.FAILED }
            val counts = DownloadSummary(
                running = open.count { it.state == TaskProgress.State.RUNNING },
                queued = open.count { it.state == TaskProgress.State.QUEUED },
                paused = open.count { it.state == TaskProgress.State.PAUSED },
                failed = failed,
            )
            if (open.isEmpty()) return counts
            val since = open.minOf { it.createdAt }
            val done = tasks.count { it.state == TaskProgress.State.DONE && it.updatedAt >= since }
            val measured = open.any { it.fraction != null && it.fraction > 0f } || done > 0
            if (!measured) return counts
            val sum = open.sumOf { (it.fraction ?: 0f).toDouble() } + done
            return counts.copy(fraction = (sum / (open.size + done)).toFloat().coerceIn(0f, 1f))
        }
    }
}

/** Why queued downloads are not moving even though nothing is wrong with them. */
enum class NetworkWait {
    NONE, OFFLINE, WIFI_ONLY;

    companion object {
        fun of(online: Boolean, wifiOnly: Boolean, metered: Boolean): NetworkWait = when {
            !online -> OFFLINE
            wifiOnly && metered -> WIFI_ONLY
            else -> NONE
        }
    }
}

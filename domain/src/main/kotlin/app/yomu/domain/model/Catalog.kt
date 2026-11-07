package app.yomu.domain.model

enum class ContentType { MANGA, NOVEL, BOOK }

data class CatalogEntry(
    val id: Long = 0,
    val sourceId: Long,
    val url: String,
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: Int = 0,
    val coverUrl: String? = null,
    val type: ContentType = ContentType.MANGA,
    val favorite: Boolean = false,
    val categoryId: Long? = null,
    val addedAt: Long = 0,
    val lastUpdateAt: Long = 0,
    val unread: Int = 0,
    /** Downloaded chapters (library listing only). */
    val downloaded: Int = 0,
    /** Latest reading time of any chapter, 0 when never read (library listing only). */
    val lastReadAt: Long = 0,
    /** Number of known chapters (library listing only). */
    val chapterCount: Int = 0,
)

data class ChapterInfo(
    val id: Long = 0,
    val entryId: Long,
    val url: String,
    val name: String,
    val number: Float = -1f,
    val scanlator: String? = null,
    val uploadDate: Long = 0,
    val read: Boolean = false,
    val bookmarked: Boolean = false,
    val lastPageRead: Int = 0,
    val downloaded: Boolean = false,
    val localPath: String? = null,
    val isNew: Boolean = false,
    val sourceOrder: Int = 0,
)

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED }
enum class DownloadKind { BOOK, CHAPTER, EXTENSION }

data class DownloadItem(
    val id: Long = 0,
    val kind: DownloadKind,
    val title: String,
    val url: String,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0,
    val bytesTotal: Long = -1,
    val pagesDone: Int = 0,
    val pagesTotal: Int = 0,
    val error: String? = null,
    val chapterId: Long? = null,
    val entryId: Long? = null,
    val sourceId: Long? = null,
    val destPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Automatic retries made so far after transient errors. */
    val attempts: Int = 0,
    val updatedAt: Long = createdAt,
) {
    val progress: Float
        get() = when {
            pagesTotal > 0 -> pagesDone.toFloat() / pagesTotal
            bytesTotal > 0 -> bytesDownloaded.toFloat() / bytesTotal
            else -> 0f
        }
}

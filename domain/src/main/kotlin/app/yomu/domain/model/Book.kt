package app.yomu.domain.model

import kotlinx.serialization.Serializable

enum class BookFormat(
    val isText: Boolean = false,
    val isComic: Boolean = false,
    val extensions: List<String> = emptyList(),
    val isAudio: Boolean = false,
) {
    EPUB(isText = true, extensions = listOf("epub")),
    PDF(extensions = listOf("pdf")),
    CBZ(isComic = true, extensions = listOf("cbz", "zip")),
    CBR(isComic = true, extensions = listOf("cbr", "rar")),
    CB7(isComic = true, extensions = listOf("cb7", "7z")),
    FOLDER(isComic = true),
    FB2(isText = true, extensions = listOf("fb2")),
    TXT(isText = true, extensions = listOf("txt")),
    HTML(isText = true, extensions = listOf("html", "htm", "xhtml")),
    DOCX(isText = true, extensions = listOf("docx")),
    MOBI(isText = true, extensions = listOf("mobi", "azw3", "azw", "prc")),

    /**
     * An audiobook: one audio file, a folder of audio files, or an [AudiobookManifest] (`.yaudio`) listing local or
     * online tracks (catalog streams, YouTube, podcasts).
     */
    AUDIO(isAudio = true, extensions = AudioFiles.EXTENSIONS + AudiobookManifest.EXTENSION),
    UNKNOWN,
}

enum class ReadingStatus { NEW, READING, FINISHED }

/** Which engine renders the book. EPUB/PDF can be flagged as manga (image based) by the user or by detection. */
enum class EngineType { TEXT, PDF, MANGA, AUDIO }

data class Book(
    val id: Long = 0,
    val title: String,
    val authors: List<String> = emptyList(),
    val series: String? = null,
    val seriesIndex: Float? = null,
    val language: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    val format: BookFormat,
    val uri: String,
    val isLinked: Boolean = false,
    val fileSize: Long = 0,
    val coverPath: String? = null,
    val status: ReadingStatus = ReadingStatus.NEW,
    val isManga: Boolean = format.isComic,
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long? = null,
    val progress: Float = 0f,
    val tags: List<String> = emptyList(),
    val readerSettingsJson: String? = null,
) {
    val engine: EngineType
        get() = when {
            format.isAudio -> EngineType.AUDIO
            format.isComic || isManga -> EngineType.MANGA
            format == BookFormat.PDF -> EngineType.PDF
            else -> EngineType.TEXT
        }
    val authorsText: String get() = authors.joinToString(", ")
}

data class BookCollection(val id: Long, val name: String, val count: Int = 0)

enum class LibrarySort { TITLE, AUTHOR, ADDED, LAST_READ, PROGRESS }

data class LibraryFilter(
    val formats: Set<BookFormat> = emptySet(),
    val statuses: Set<ReadingStatus> = emptySet(),
    val tag: String? = null,
    val language: String? = null,
    val collectionId: Long? = null,
)

data class LibraryQuery(
    val search: String = "",
    val sort: LibrarySort = LibrarySort.LAST_READ,
    val ascending: Boolean = false,
    val filter: LibraryFilter = LibraryFilter(),
)

/** Universal position inside any document. */
@Serializable
data class Locator(
    /** Text: chapter (spine) index. PDF and comics: page index. */
    val chapter: Int = 0,
    /** Progression inside the chapter, 0..1. */
    val progression: Float = 0f,
    /** Progression inside the whole publication, 0..1. */
    val totalProgression: Float = 0f,
    /** Character offset inside the chapter text (start of a highlight or first visible character). */
    val offset: Int? = null,
    val endOffset: Int? = null,
    val text: String? = null,
    val chapterTitle: String? = null,
    /** Online chapter id (catalog) when reading from a source. */
    val onlineChapterId: Long? = null,
)

data class TocItem(val title: String, val chapter: Int, val anchor: String? = null, val level: Int = 0)

data class SearchHit(
    val chapter: Int,
    val occurrence: Int,
    val snippet: String,
    val matchStart: Int,
    val matchLength: Int,
    val chapterTitle: String? = null,
)

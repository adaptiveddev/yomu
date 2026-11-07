package app.yomu.data.repository

import app.yomu.core.database.BookEntity
import app.yomu.core.database.BookmarkEntity
import app.yomu.core.database.CatalogItemEntity
import app.yomu.core.database.ChapterEntity
import app.yomu.core.database.DownloadTaskEntity
import app.yomu.core.database.HighlightEntity
import app.yomu.core.database.NoteEntity
import app.yomu.core.database.WordEntity
import app.yomu.domain.model.Book
import app.yomu.domain.model.BookFormat
import app.yomu.domain.model.Bookmark
import app.yomu.domain.model.CatalogEntry
import app.yomu.domain.model.ChapterInfo
import app.yomu.domain.model.ContentType
import app.yomu.domain.model.DownloadItem
import app.yomu.domain.model.DownloadKind
import app.yomu.domain.model.DownloadStatus
import app.yomu.domain.model.Highlight
import app.yomu.domain.model.HighlightColor
import app.yomu.domain.model.HighlightStyle
import app.yomu.domain.model.Locator
import app.yomu.domain.model.Note
import app.yomu.domain.model.ReadingStatus
import app.yomu.domain.model.SrsState
import app.yomu.domain.model.Word
import kotlinx.serialization.json.Json

val DataJson = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

fun Locator.toJson(): String = DataJson.encodeToString(Locator.serializer(), this)
fun String.toLocator(): Locator = runCatching { DataJson.decodeFromString(Locator.serializer(), this) }.getOrDefault(Locator())

internal fun splitList(s: String): List<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
internal fun joinList(l: List<String>): String = l.map { it.replace(",", " ").trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")

fun BookEntity.toDomain(tags: List<String> = emptyList()) = Book(
    id = id,
    title = title,
    authors = authorsText.split(" & ").map { it.trim() }.filter { it.isNotEmpty() },
    series = seriesName,
    seriesIndex = seriesIndex,
    language = language,
    description = description,
    publisher = publisher,
    format = runCatching { BookFormat.valueOf(format) }.getOrDefault(BookFormat.UNKNOWN),
    uri = uri,
    isLinked = isLinked,
    fileSize = fileSize,
    coverPath = coverPath,
    status = runCatching { ReadingStatus.valueOf(status) }.getOrDefault(ReadingStatus.NEW),
    isManga = isManga,
    addedAt = addedAt,
    lastReadAt = lastReadAt,
    progress = progress,
    tags = tags,
    readerSettingsJson = readerSettingsJson,
)

fun authorsToText(authors: List<String>): String = authors.joinToString(" & ")

fun sortTitle(title: String): String {
    val t = title.trim()
    val lower = t.lowercase()
    listOf("the ", "a ", "an ").forEach { if (lower.startsWith(it)) return t.substring(it.length).trim() }
    return t
}

fun HighlightEntity.toDomain() = Highlight(
    id = id,
    bookId = bookId,
    locator = locatorJson.toLocator(),
    text = text,
    color = runCatching { HighlightColor.valueOf(color) }.getOrDefault(HighlightColor.YELLOW),
    style = runCatching { HighlightStyle.valueOf(style) }.getOrDefault(HighlightStyle.HIGHLIGHT),
    isQuote = isQuote,
    isFavorite = isFavorite,
    tags = splitList(tags),
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Highlight.toEntity() = HighlightEntity(
    id = id,
    bookId = bookId,
    locatorJson = locator.toJson(),
    chapter = locator.chapter,
    text = text,
    color = color.name,
    style = style.name,
    isQuote = isQuote,
    isFavorite = isFavorite,
    tags = joinList(tags),
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun NoteEntity.toDomain() = Note(id, bookId, highlightId, locatorJson?.toLocator(), text, splitList(tags), createdAt, updatedAt)
fun Note.toEntity() = NoteEntity(id, bookId, highlightId, locator?.toJson(), text, joinList(tags), createdAt, updatedAt)

fun BookmarkEntity.toDomain() = Bookmark(id, bookId, locatorJson.toLocator(), title, createdAt)
fun Bookmark.toEntity() = BookmarkEntity(id, bookId, locator.toJson(), title, locator.totalProgression, createdAt)

fun WordEntity.toDomain() = Word(
    id = id,
    term = term,
    lang = lang.ifEmpty { null },
    translation = translation,
    definition = definition,
    context = context,
    bookId = bookId,
    bookTitle = bookTitle,
    locator = locatorJson?.toLocator(),
    tags = splitList(tags),
    createdAt = createdAt,
    level = level,
    srs = SrsState(ease, intervalDays, repetitions, dueAt, lapses, lastReviewedAt),
)

fun Word.toEntity(normalized: String) = WordEntity(
    id = id,
    term = term,
    normalized = normalized,
    lang = lang.orEmpty(),
    translation = translation,
    definition = definition,
    context = context,
    bookId = bookId,
    bookTitle = bookTitle,
    locatorJson = locator?.toJson(),
    tags = joinList(tags),
    createdAt = createdAt,
    level = level,
    ease = srs.ease,
    intervalDays = srs.intervalDays,
    repetitions = srs.repetitions,
    dueAt = srs.dueAt,
    lapses = srs.lapses,
    lastReviewedAt = srs.lastReviewedAt,
)

fun CatalogItemEntity.toDomain(unread: Int = 0, downloaded: Int = 0, lastReadAt: Long = 0, chapterCount: Int = 0) = CatalogEntry(
    id = id,
    sourceId = sourceId,
    url = url,
    title = title,
    author = author,
    artist = artist,
    description = description,
    genres = splitList(genres),
    status = status,
    coverUrl = coverUrl,
    type = runCatching { ContentType.valueOf(type) }.getOrDefault(ContentType.MANGA),
    favorite = favorite,
    categoryId = categoryId,
    addedAt = addedAt,
    lastUpdateAt = lastUpdateAt,
    unread = unread,
    downloaded = downloaded,
    lastReadAt = lastReadAt,
    chapterCount = chapterCount,
)

fun ChapterEntity.toDomain() = ChapterInfo(
    id = id,
    entryId = itemId,
    url = url,
    name = name,
    number = number,
    scanlator = scanlator,
    uploadDate = uploadDate,
    read = read,
    bookmarked = bookmarked,
    lastPageRead = lastPageRead,
    downloaded = downloaded,
    localPath = localPath,
    isNew = isNew,
    sourceOrder = sourceOrder,
)

fun DownloadTaskEntity.toDomain() = DownloadItem(
    id = id,
    kind = runCatching { DownloadKind.valueOf(kind) }.getOrDefault(DownloadKind.BOOK),
    title = title,
    url = url,
    status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED),
    bytesDownloaded = bytesDownloaded,
    bytesTotal = bytesTotal,
    pagesDone = pagesDone,
    pagesTotal = pagesTotal,
    error = error,
    chapterId = chapterId,
    entryId = itemId,
    sourceId = sourceId,
    destPath = destPath,
    createdAt = createdAt,
    attempts = attempts,
    updatedAt = updatedAt,
)

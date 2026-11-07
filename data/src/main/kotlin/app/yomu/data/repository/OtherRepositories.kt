package app.yomu.data.repository

import app.yomu.core.database.ReadingSessionEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.domain.model.BookAnnotations
import app.yomu.domain.model.Bookmark
import app.yomu.domain.model.Highlight
import app.yomu.domain.model.Note
import app.yomu.domain.model.ReadingStats
import app.yomu.domain.model.ReviewMode
import app.yomu.domain.model.Word
import app.yomu.domain.repository.AnnotationRepository
import app.yomu.domain.repository.StatsRepository
import app.yomu.domain.model.ReadingSession
import app.yomu.domain.repository.WordRepository
import app.yomu.domain.srs.Sm2
import app.yomu.domain.text.Lemmatizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AnnotationRepositoryImpl(private val db: YomuDatabase) : AnnotationRepository {
    private val dao = db.annotations()

    override fun observeHighlights(bookId: Long): Flow<List<Highlight>> = dao.observeHighlights(bookId).map { l -> l.map { it.toDomain() } }
    override fun observeAllHighlights(): Flow<List<Highlight>> = dao.observeAllHighlights().map { l -> l.map { it.toDomain() } }
    override fun observeNotes(bookId: Long): Flow<List<Note>> = dao.observeNotes(bookId).map { l -> l.map { it.toDomain() } }
    fun observeAllNotes(): Flow<List<Note>> = dao.observeAllNotes().map { l -> l.map { it.toDomain() } }
    override fun observeBookmarks(bookId: Long): Flow<List<Bookmark>> = dao.observeBookmarks(bookId).map { l -> l.map { it.toDomain() } }
    fun observeAllBookmarks(): Flow<List<Bookmark>> = dao.observeAllBookmarks().map { l -> l.map { it.toDomain() } }

    /** Re-inserts a deleted item with its original id (undo). */
    suspend fun restore(highlight: Highlight? = null, note: Note? = null, bookmark: Bookmark? = null) {
        highlight?.let { dao.insertHighlights(listOf(it.toEntity())) }
        note?.let { dao.insertNotes(listOf(it.toEntity())) }
        bookmark?.let { dao.insertBookmarks(listOf(it.toEntity())) }
    }

    override suspend fun saveHighlight(highlight: Highlight): Long {
        val e = highlight.copy(updatedAt = System.currentTimeMillis()).toEntity()
        return if (highlight.id == 0L) dao.insertHighlight(e) else e.id.also { dao.updateHighlight(e) }
    }

    suspend fun getHighlight(id: Long): Highlight? = dao.highlight(id)?.toDomain()

    override suspend fun deleteHighlight(id: Long) {
        dao.deleteNotesOfHighlight(id)
        dao.deleteHighlight(id)
    }

    override suspend fun saveNote(note: Note): Long {
        val e = note.copy(updatedAt = System.currentTimeMillis()).toEntity()
        return if (note.id == 0L) dao.insertNote(e) else e.id.also { dao.updateNote(e) }
    }

    override suspend fun deleteNote(id: Long) = dao.deleteNote(id)

    override suspend fun saveBookmark(bookmark: Bookmark): Long {
        val e = bookmark.toEntity()
        return if (bookmark.id == 0L) dao.insertBookmark(e) else e.id.also { dao.updateBookmark(e) }
    }

    override suspend fun deleteBookmark(id: Long) = dao.deleteBookmark(id)

    override suspend fun bookAnnotations(bookId: Long): BookAnnotations? {
        val book = db.books().get(bookId) ?: return null
        return BookAnnotations(
            bookTitle = book.title,
            authors = book.toDomain().authors,
            highlights = dao.highlights(bookId).map { it.toDomain() },
            notes = dao.notes(bookId).map { it.toDomain() },
            bookmarks = dao.bookmarks(bookId).map { it.toDomain() },
        )
    }

    override suspend fun allAnnotations(): List<BookAnnotations> = dao.annotatedBookIds().mapNotNull { bookAnnotations(it) }
}

class WordRepositoryImpl(private val db: YomuDatabase) : WordRepository {
    private val dao = db.words()

    override fun observeWords(search: String, bookId: Long?, tag: String?, maxLevel: Int?): Flow<List<Word>> =
        dao.observe(search.trim(), bookId, tag, maxLevel).map { l -> l.map { it.toDomain() } }

    override fun observeDueCount(now: Long): Flow<Int> = dao.observeDue(now)
    fun observeCount(): Flow<Int> = dao.observeCount()
    fun observeSavedNormalized(): Flow<Set<String>> = dao.observeNormalized().map { it.toSet() }
    fun observeTags(): Flow<List<String>> = dao.observeTagStrings().map { l -> l.flatMap { splitList(it) }.distinct().sorted() }

    override suspend fun dueWords(now: Long, limit: Int): List<Word> = dao.due(now, limit).map { it.toDomain() }

    override suspend fun save(word: Word): Long {
        val normalized = Lemmatizer.normalize(word.term)
        val existing = if (word.id != 0L) dao.get(word.id) else dao.find(normalized, word.lang.orEmpty())
        return if (existing == null) {
            dao.insert(word.copy(id = 0).toEntity(normalized).copy(dueAt = if (word.srs.dueAt == 0L) System.currentTimeMillis() else word.srs.dueAt))
        } else {
            val merged = word.copy(
                id = existing.id,
                translation = word.translation ?: existing.translation,
                definition = word.definition ?: existing.definition,
                context = word.context ?: existing.context,
                createdAt = existing.createdAt,
            )
            val keepSrs = if (word.id == 0L) existing.toDomain().srs else word.srs
            dao.update(merged.copy(srs = keepSrs, level = if (word.id == 0L) existing.level else word.level).toEntity(normalized))
            existing.id
        }
    }

    override suspend fun find(term: String, lang: String?): Word? {
        val n = Lemmatizer.normalize(term)
        return (if (lang != null) dao.find(n, lang) else null)?.toDomain() ?: dao.findAnyLang(n)?.toDomain()
    }

    override suspend fun delete(ids: Collection<Long>) = dao.delete(ids)

    override suspend fun review(word: Word, knew: Boolean, mode: ReviewMode, now: Long): Word {
        val state = Sm2.review(word.srs, Sm2.qualityFor(knew), now)
        val updated = word.copy(srs = state, level = Sm2.level(state))
        dao.update(updated.toEntity(Lemmatizer.normalize(word.term)))
        return updated
    }

    override suspend fun reviewedToday(startOfDay: Long): Int = dao.reviewedSince(startOfDay)
    override suspend fun savedTerms(lang: String?): List<String> = dao.terms(lang)
    suspend fun all(): List<Word> = dao.all().map { it.toDomain() }
}

class StatsRepositoryImpl(private val db: YomuDatabase) : StatsRepository {
    override suspend fun recordSession(bookId: Long?, startedAt: Long, durationMs: Long, pages: Int, chars: Long, catalogItemId: Long?) {
        if (durationMs < MIN_SESSION_MS) return
        db.sessions().insert(
            ReadingSessionEntity(bookId = bookId, startedAt = startedAt, durationMs = durationMs, pages = pages, chars = chars, catalogItemId = catalogItemId),
        )
    }

    override fun observeSessions(): Flow<List<ReadingSession>> = db.sessions().observeAll().map { l ->
        l.map { ReadingSession(it.id, it.bookId, it.catalogItemId, it.startedAt, it.durationMs, it.pages, it.chars) }
    }

    override suspend fun resetStats() = db.sessions().deleteAll()

    override suspend fun stats(zoneOffsetMs: Long): ReadingStats {
        val sessions = db.sessions().all()
        val perDay = sessions.groupBy { Math.floorDiv(it.startedAt + zoneOffsetMs, DAY) }.mapValues { (_, v) -> v.sumOf { it.durationMs } }
        val days = perDay.keys.sorted()
        var best = 0
        var run = 0
        var prev = Long.MIN_VALUE
        days.forEach { d ->
            run = if (d == prev + 1) run + 1 else 1
            best = maxOf(best, run)
            prev = d
        }
        val today = Math.floorDiv(System.currentTimeMillis() + zoneOffsetMs, DAY)
        var streak = 0
        var day = if (perDay.containsKey(today)) today else today - 1
        while (perDay.containsKey(day)) {
            streak++
            day--
        }
        return ReadingStats(
            totalMs = sessions.sumOf { it.durationMs },
            sessions = sessions.size,
            pages = sessions.sumOf { it.pages },
            chars = sessions.sumOf { it.chars },
            booksFinished = db.books().finishedCount(),
            streakDays = streak,
            bestStreak = best,
            perDay = perDay,
        )
    }

    suspend fun timeForBook(bookId: Long): Long = db.sessions().timeForBook(bookId)

    companion object {
        const val DAY = 86_400_000L
        const val MIN_SESSION_MS = 5_000L
    }
}

package app.yomu.domain.repository

import app.yomu.domain.model.Book
import app.yomu.domain.model.BookAnnotations
import app.yomu.domain.model.BookCollection
import app.yomu.domain.model.Bookmark
import app.yomu.domain.model.Highlight
import app.yomu.domain.model.LibraryQuery
import app.yomu.domain.model.Locator
import app.yomu.domain.model.Note
import app.yomu.domain.model.ReadingStats
import app.yomu.domain.model.ReadingStatus
import app.yomu.domain.model.ReviewMode
import app.yomu.domain.model.Word
import kotlinx.coroutines.flow.Flow

interface BookRepository {
    fun observeBooks(query: LibraryQuery): Flow<List<Book>>
    fun observeContinueReading(limit: Int = 10): Flow<List<Book>>
    fun observeBook(id: Long): Flow<Book?>
    suspend fun getBook(id: Long): Book?
    suspend fun update(book: Book)
    suspend fun setStatus(ids: Collection<Long>, status: ReadingStatus)
    suspend fun delete(ids: Collection<Long>, deleteFiles: Boolean)
    suspend fun saveProgress(bookId: Long, locator: Locator)
    suspend fun getProgress(bookId: Long): Locator?
    suspend fun saveReaderSettings(bookId: Long, json: String?)
    fun observeCollections(): Flow<List<BookCollection>>
    suspend fun createCollection(name: String): Long
    suspend fun renameCollection(id: Long, name: String)
    suspend fun deleteCollection(id: Long)
    suspend fun addToCollection(bookIds: Collection<Long>, collectionId: Long)
    suspend fun removeFromCollection(bookIds: Collection<Long>, collectionId: Long)
    fun observeTags(): Flow<List<String>>
    fun observeLanguages(): Flow<List<String>>
    suspend fun setTags(bookId: Long, tags: List<String>)
    suspend fun addTag(bookIds: Collection<Long>, tag: String)
}

interface AnnotationRepository {
    fun observeHighlights(bookId: Long): Flow<List<Highlight>>
    fun observeAllHighlights(): Flow<List<Highlight>>
    fun observeNotes(bookId: Long): Flow<List<Note>>
    fun observeBookmarks(bookId: Long): Flow<List<Bookmark>>
    suspend fun saveHighlight(highlight: Highlight): Long
    suspend fun deleteHighlight(id: Long)
    suspend fun saveNote(note: Note): Long
    suspend fun deleteNote(id: Long)
    suspend fun saveBookmark(bookmark: Bookmark): Long
    suspend fun deleteBookmark(id: Long)
    suspend fun bookAnnotations(bookId: Long): BookAnnotations?
    suspend fun allAnnotations(): List<BookAnnotations>
}

interface WordRepository {
    fun observeWords(search: String = "", bookId: Long? = null, tag: String? = null, maxLevel: Int? = null): Flow<List<Word>>
    fun observeDueCount(now: Long): Flow<Int>
    suspend fun dueWords(now: Long, limit: Int): List<Word>
    suspend fun save(word: Word): Long
    suspend fun find(term: String, lang: String?): Word?
    suspend fun delete(ids: Collection<Long>)
    suspend fun review(word: Word, knew: Boolean, mode: ReviewMode, now: Long = System.currentTimeMillis()): Word
    suspend fun reviewedToday(startOfDay: Long): Int
    suspend fun savedTerms(lang: String?): List<String>
}

interface StatsRepository {
    suspend fun recordSession(bookId: Long?, startedAt: Long, durationMs: Long, pages: Int, chars: Long, catalogItemId: Long? = null)
    suspend fun stats(zoneOffsetMs: Long): ReadingStats

    /** Every recorded session, oldest first (history removals keep their sessions here). */
    fun observeSessions(): Flow<List<app.yomu.domain.model.ReadingSession>>
    suspend fun resetStats()
}

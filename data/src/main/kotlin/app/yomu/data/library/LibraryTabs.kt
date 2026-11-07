package app.yomu.data.library

import androidx.room.withTransaction
import app.yomu.core.database.BookCollectionCrossRef
import app.yomu.core.database.CatalogCollectionCrossRef
import app.yomu.core.database.CollectionEntity
import app.yomu.core.database.YomuDatabase
import app.yomu.domain.model.BookCollection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The user's library tabs (1.5.0): named groups that hold books and online library titles alike. Stored as collections
 * of kind BOOK; online titles join them through `catalog_collections`.
 */
class LibraryTabs(private val db: YomuDatabase) {
    private val dao get() = db.collections()

    /** Tabs in their order, with the number of books and online titles in each. */
    fun observe(): Flow<List<BookCollection>> = dao.observeBookCollections().map { l -> l.map { BookCollection(it.id, it.name, it.count) } }

    /** Tabs of every online library title: title id → tab ids. */
    fun observeOnline(): Flow<Map<Long, Set<Long>>> = dao.observeCatalogMemberships().map { refs ->
        refs.groupBy({ it.itemId }, { it.collectionId }).mapValues { it.value.toSet() }
    }

    /** Creates a tab at the end of the row; returns its id. */
    suspend fun create(name: String): Long = db.withTransaction {
        dao.insert(CollectionEntity(name = name.trim(), sortOrder = dao.maxSortOrder() + 1))
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim())

    /** Deletes the tab; its books and titles stay in the library. */
    suspend fun delete(id: Long) = dao.delete(id)

    /** Moves a tab one place to the left ([delta] < 0) or to the right. */
    suspend fun move(id: Long, delta: Int) = db.withTransaction {
        val tabs = dao.bookCollections().toMutableList()
        val from = tabs.indexOfFirst { it.id == id }
        val to = from + delta.coerceIn(-1, 1)
        if (from >= 0 && to in tabs.indices) {
            java.util.Collections.swap(tabs, from, to)
            tabs.forEachIndexed { index, c -> dao.setSortOrder(c.id, index) }
        }
    }

    suspend fun setBooks(bookIds: Collection<Long>, tabId: Long, member: Boolean) {
        if (bookIds.isEmpty()) return
        if (member) dao.add(bookIds.map { BookCollectionCrossRef(it, tabId) }) else dao.remove(bookIds, tabId)
    }

    suspend fun setOnline(itemIds: Collection<Long>, tabId: Long, member: Boolean) {
        if (itemIds.isEmpty()) return
        if (member) dao.addItems(itemIds.map { CatalogCollectionCrossRef(it, tabId) }) else dao.removeItems(itemIds, tabId)
    }

    /** Puts an online title in exactly the [tabIds]. */
    suspend fun setOnlineTabs(itemId: Long, tabIds: Set<Long>) = db.withTransaction {
        val current = dao.collectionsOfItem(itemId).toSet()
        (current - tabIds).forEach { dao.removeItems(listOf(itemId), it) }
        dao.addItems((tabIds - current).map { CatalogCollectionCrossRef(itemId, it) })
    }

    /** Puts a book in exactly the [tabIds]. */
    suspend fun setBookTabs(bookId: Long, tabIds: Set<Long>) = db.withTransaction {
        val current = dao.collectionsOf(bookId).toSet()
        (current - tabIds).forEach { dao.remove(listOf(bookId), it) }
        dao.add((tabIds - current).map { BookCollectionCrossRef(bookId, it) })
    }

    suspend fun onlineTabsOf(itemId: Long): Set<Long> = dao.collectionsOfItem(itemId).toSet()

    suspend fun bookTabsOf(bookId: Long): Set<Long> = dao.collectionsOf(bookId).toSet()

    /** Titles removed from the online library leave their tabs. */
    suspend fun forgetOnline(itemIds: Collection<Long>) {
        if (itemIds.isNotEmpty()) dao.clearItems(itemIds)
    }
}

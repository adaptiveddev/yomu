package app.yomu.domain.model

import app.yomu.domain.catalog.NaturalOrder

/** What a field of the batch editor does with the value of every selected book. */
sealed interface FieldEdit {
    /** Every book keeps its own value. */
    data object Keep : FieldEdit

    /** Every book loses the value. */
    data object Clear : FieldEdit

    /** Every book gets the [value]. */
    data class Set(val value: String) : FieldEdit
}

/** In which order the books of a batch edit are numbered in their series. */
enum class BatchOrder { TITLE, SELECTION }

/**
 * Metadata changes made to several books at once ("Edit metadata" of the library selection).
 *
 * Fields left at [FieldEdit.Keep] (and a null [status]) do not touch the books. When [numberFrom] is set, the books
 * get the numbers numberFrom, numberFrom + 1, … in the series, in the [order].
 */
data class BatchMetadata(
    val authors: FieldEdit = FieldEdit.Keep,
    val series: FieldEdit = FieldEdit.Keep,
    val numberFrom: Float? = null,
    val order: BatchOrder = BatchOrder.TITLE,
    val language: FieldEdit = FieldEdit.Keep,
    val publisher: FieldEdit = FieldEdit.Keep,
    val status: ReadingStatus? = null,
    val addTags: List<String> = emptyList(),
    val removeTags: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = authors == FieldEdit.Keep && series == FieldEdit.Keep && numberFrom == null && language == FieldEdit.Keep &&
            publisher == FieldEdit.Keep && status == null && !changesTags

    val changesTags: Boolean get() = addTags.any { it.isNotBlank() } || removeTags.isNotEmpty()

    /** The [books] in the order they are numbered in ([selection] is the order the user picked them in). */
    fun ordered(books: List<Book>, selection: List<Long> = books.map { it.id }): List<Book> = when (order) {
        BatchOrder.TITLE -> books.sortedWith(compareBy(NaturalOrder) { it.title })
        BatchOrder.SELECTION -> {
            val place = selection.withIndex().associate { (i, id) -> id to i }
            books.sortedBy { place[it.id] ?: Int.MAX_VALUE }
        }
    }

    /** The [book] after the change; [position] is its place in [ordered] (for the numbers in the series). */
    fun applyTo(book: Book, position: Int): Book {
        val newSeries = series.apply(book.series)
        val index = when {
            newSeries == null -> null
            numberFrom != null -> numberFrom + position
            else -> book.seriesIndex
        }
        return book.copy(
            authors = when (val a = authors) {
                FieldEdit.Keep -> book.authors
                FieldEdit.Clear -> emptyList()
                is FieldEdit.Set -> splitAuthors(a.value)
            },
            series = newSeries,
            seriesIndex = index,
            // A kept language stays as it is written; a typed one is stored like the importers store it.
            language = if (language == FieldEdit.Keep) book.language else language.apply(book.language)?.lowercase(),
            publisher = publisher.apply(book.publisher),
            status = status ?: book.status,
            tags = tagsOf(book),
        )
    }

    /** Tags of the [book] after the change: the removed ones go, the added ones join (once, whatever their case). */
    fun tagsOf(book: Book): List<String> {
        val removed = removeTags.map { it.lowercase() }.toSet()
        val kept = book.tags.filter { it.lowercase() !in removed }
        val added = addTags.map { it.trim() }.filter { it.isNotEmpty() }
        return (kept + added).distinctBy { it.lowercase() }
    }

    private fun FieldEdit.apply(current: String?): String? = when (this) {
        FieldEdit.Keep -> current
        FieldEdit.Clear -> null
        is FieldEdit.Set -> value.trim().ifEmpty { null }
    }

    companion object {
        /** Authors typed in one field: separated by commas, semicolons or ampersands. */
        fun splitAuthors(text: String): List<String> = text.split(',', ';', '&').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        /** The value all the [values] share (null when they differ or none is set). */
        fun common(values: List<String?>): String? = values.distinct().singleOrNull()

        /**
         * What a text field of the batch editor means: the [text] the user left, the value all books shared before
         * ([common], null when they differed) and whether the user asked to [clear] it.
         */
        fun fieldEdit(text: String, common: String?, clear: Boolean): FieldEdit {
            val t = text.trim()
            return when {
                clear -> FieldEdit.Clear
                t.isEmpty() -> if (common != null) FieldEdit.Clear else FieldEdit.Keep
                t == common?.trim() -> FieldEdit.Keep
                else -> FieldEdit.Set(t)
            }
        }
    }
}

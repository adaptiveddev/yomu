package app.yomu.data.catalog

import app.yomu.source.SBookLink
import app.yomu.source.SItem
import java.text.Normalizer

/** Ordering helpers for search results and download links. */
object SearchRanking {
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val MARKS = Regex("\\p{M}+")

    /** Lower case, without accents and punctuation: "Kaguya-sama: Love Is War" → "kaguya sama love is war". */
    fun normalize(text: String): String =
        MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "").replace('ё', 'е').let { NON_WORD.replace(it, " ").trim() }

    /** 0 = same title, 1 = starts with the query, 2 = contains it, 3 = contains every word, 4 = other. */
    fun score(title: String, query: String): Int {
        val q = normalize(query)
        if (q.isEmpty()) return 4
        val t = normalize(title)
        return when {
            t == q -> 0
            t.startsWith(q) -> 1
            t.contains(q) -> 2
            q.split(' ').all { it in t } -> 3
            else -> 4
        }
    }

    /** Best title matches first; the source's own order is kept inside each group. */
    fun rank(items: List<SItem>, query: String): List<SItem> {
        if (items.size < 2 || query.isBlank()) return items
        return items.withIndex().sortedWith(compareBy<IndexedValue<SItem>> { score(it.value.title, query) }.thenBy { it.index }).map { it.value }
    }

    /** Reader-friendly formats first; links with the same address appear once. */
    fun sortLinks(links: List<SBookLink>): List<SBookLink> =
        links.distinctBy { it.url }.withIndex().sortedWith(compareBy<IndexedValue<SBookLink>> { formatRank(it.value) }.thenBy { it.index }).map { it.value }

    fun formatRank(link: SBookLink): Int {
        val mime = link.mimeType.lowercase()
        val ext = link.url.substringBefore('?').substringAfterLast('/').lowercase()
        return when {
            "epub" in mime || ext.endsWith(".epub") -> 0
            "fb2" in mime || "fictionbook" in mime || ext.endsWith(".fb2") || ext.endsWith(".fb2.zip") -> 1
            "azw" in mime || "mobi" in mime || "kindle" in mime || ext.endsWith(".azw3") || ext.endsWith(".mobi") -> 2
            "pdf" in mime || ext.endsWith(".pdf") -> 3
            "djvu" in mime || ext.endsWith(".djvu") -> 4
            "cbz" in mime || "comicbook" in mime || ext.endsWith(".cbz") || ext.endsWith(".cbr") -> 5
            "rtf" in mime || "text/plain" in mime || ext.endsWith(".txt") || ext.endsWith(".rtf") -> 6
            "html" in mime -> 7
            else -> 8
        }
    }
}

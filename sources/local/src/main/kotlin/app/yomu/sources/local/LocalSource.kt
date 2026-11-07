package app.yomu.sources.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.yomu.source.CatalogSource
import app.yomu.source.ConfigurableSource
import app.yomu.source.FilterList
import app.yomu.source.ItemsPage
import app.yomu.source.Page
import app.yomu.source.SChapter
import app.yomu.source.SItem
import app.yomu.source.SourceContent
import app.yomu.source.SourceException
import app.yomu.source.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Titles stored on the device, Mihon style: `<root>/<Title>/<Chapter folder or archive>`.
 * Optional `cover.jpg` and `details.json` (title, author, artist, description, genre, status) inside a title folder.
 */
class LocalSource(private val context: Context) : CatalogSource, ConfigurableSource {
    override val id: Long = ID
    override val name = "Local"
    override val lang = "all"
    override val content = SourceContent.MANGA
    override val supportsLatest = true
    override val configKeys = listOf(KEY_FOLDER)

    private var root: Uri? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun configure(values: Map<String, String>) {
        root = values[KEY_FOLDER]?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    val isConfigured: Boolean get() = root != null

    private fun rootDir(): DocumentFile {
        val r = root ?: throw SourceException("Local folder is not selected")
        return DocumentFile.fromTreeUri(context, r) ?: throw SourceException("Local folder is not accessible")
    }

    private fun titles(): List<DocumentFile> = rootDir().listFiles().filter { it.isDirectory && it.name?.startsWith(".") == false }

    private fun toItem(dir: DocumentFile): SItem {
        val files = dir.listFiles()
        val cover = files.firstOrNull { it.name?.substringBeforeLast('.')?.equals("cover", true) == true && isImage(it.name) }
            ?: files.firstOrNull { it.isDirectory }?.listFiles()?.filter { isImage(it.name) }?.minByOrNull { it.name.orEmpty() }
        return SItem(url = dir.uri.toString(), title = dir.name.orEmpty(), thumbnailUrl = cover?.uri?.toString(), type = SourceContent.MANGA)
    }

    override suspend fun getPopular(page: Int): ItemsPage = withContext(Dispatchers.IO) {
        ItemsPage(titles().sortedBy { it.name?.lowercase() }.map(::toItem), false)
    }

    override suspend fun getLatest(page: Int): ItemsPage = withContext(Dispatchers.IO) {
        ItemsPage(titles().sortedByDescending { it.lastModified() }.map(::toItem), false)
    }

    override suspend fun search(query: String, filters: FilterList, page: Int): ItemsPage = withContext(Dispatchers.IO) {
        ItemsPage(titles().filter { it.name.orEmpty().contains(query, ignoreCase = true) }.map(::toItem), false)
    }

    @Serializable
    private data class Details(
        val title: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val description: String? = null,
        val genre: List<String> = emptyList(),
        val status: Int? = null,
    )

    override suspend fun getDetails(item: SItem): SItem = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(item.url)) ?: return@withContext item
        val detailsFile = dir.listFiles().firstOrNull { it.name.equals("details.json", true) }
        val details = detailsFile?.let { f ->
            runCatching { context.contentResolver.openInputStream(f.uri)!!.use { json.decodeFromString(Details.serializer(), it.readBytes().decodeToString()) } }.getOrNull()
        }
        item.copy(
            title = details?.title ?: item.title,
            author = details?.author,
            artist = details?.artist,
            description = details?.description,
            genres = details?.genre.orEmpty(),
            status = details?.status ?: Status.UNKNOWN,
            initialized = true,
        )
    }

    override suspend fun getChapters(item: SItem): List<SChapter> = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, Uri.parse(item.url)) ?: return@withContext emptyList()
        dir.listFiles()
            .filter { f -> f.isDirectory || isArchive(f.name) }
            .map { f ->
                val name = f.name.orEmpty().let { if (f.isDirectory) it else it.substringBeforeLast('.') }
                SChapter(url = f.uri.toString(), name = name, number = chapterNumber(name), dateUpload = f.lastModified())
            }
            .sortedByDescending { it.number }
    }

    override suspend fun getPages(chapter: SChapter): List<Page> = withContext(Dispatchers.IO) {
        val f = DocumentFile.fromTreeUri(context, Uri.parse(chapter.url)) ?: return@withContext emptyList()
        if (!f.isDirectory) throw SourceException("Archive chapters are opened by the archive reader")
        f.listFiles().filter { isImage(it.name) }.sortedWith(compareBy(NaturalComparator) { it.name.orEmpty() })
            .mapIndexed { i, img -> Page(i, img.uri.toString(), img.uri.toString()) }
    }

    companion object {
        const val ID = 0L
        const val KEY_FOLDER = "folder"
        private val images = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")
        private val archives = setOf("cbz", "zip", "cbr", "rar", "cb7", "7z", "epub", "pdf")

        fun isImage(name: String?) = name?.substringAfterLast('.', "")?.lowercase() in images
        fun isArchive(name: String?) = name?.substringAfterLast('.', "")?.lowercase() in archives

        fun chapterNumber(name: String): Float {
            val m = Regex("(?i)(?:ch(?:apter)?\\.?|глава|#)\\s*(\\d+(?:[.,]\\d+)?)").find(name) ?: Regex("(\\d+(?:[.,]\\d+)?)").findAll(name).lastOrNull()
            return m?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull() ?: -1f
        }
    }
}

private object NaturalComparator : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) (x.toBigIntegerOrNull() ?: 0.toBigInteger()).compareTo(y.toBigIntegerOrNull() ?: 0.toBigInteger()) else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }
}

package app.yomu.data.audio

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import app.yomu.core.common.AppError
import app.yomu.core.common.AppError.AudioLink.Reason
import app.yomu.core.common.Files
import app.yomu.core.database.BookEntity
import app.yomu.data.format.NaturalOrder
import app.yomu.data.library.ImportOutcome
import app.yomu.data.repository.BookRepositoryImpl
import app.yomu.data.repository.authorsToText
import app.yomu.data.repository.findByHash
import app.yomu.data.repository.findByUri
import app.yomu.data.repository.sortTitle
import app.yomu.domain.model.AudioChapter
import app.yomu.domain.model.AudioFiles
import app.yomu.domain.model.AudioOrigin
import app.yomu.domain.model.AudioTrack
import app.yomu.domain.model.AudiobookManifest
import app.yomu.domain.model.Book
import app.yomu.domain.model.BookFormat
import app.yomu.domain.model.ReadingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * Audiobooks of the library. A book of format [BookFormat.AUDIO] points to one audio file, a folder of audio files
 * (a SAF tree or a folder in app storage, e.g. an unpacked ZIP) or a `.yaudio` [AudiobookManifest] file.
 */
class AudiobookStore(
    private val context: Context,
    private val books: BookRepositoryImpl,
    private val client: () -> OkHttpClient,
    val youtube: YouTubeClient,
) {
    private val dir: File get() = File(context.filesDir, "audiobooks").also { it.mkdirs() }

    // ---------------------------------------------------------------- tracks

    /**
     * The tracks of [book] in listening order, with the chapter marks stored inside M4B/MP3 files. The player screen
     * and the service both ask when a book opens; a short cache (behind a lock, so the second caller waits instead of
     * walking a SAF folder again) answers the second one. Manifests are small and change when tracks are downloaded
     * or deleted, so they are always read again.
     */
    suspend fun tracks(book: Book): List<AudioTrack> {
        if (isManifest(book.uri)) return loadTracks(book)
        return tracksLock.withLock { cachedTracks(book) }
    }

    private suspend fun cachedTracks(book: Book): List<AudioTrack> {
        val now = System.currentTimeMillis()
        trackCache[book.uri]?.takeIf { now - it.first < TRACKS_TTL_MS }?.let { return it.second }
        val list = loadTracks(book)
        trackCache.entries.removeIf { now - it.value.first >= TRACKS_TTL_MS }
        trackCache[book.uri] = now to list
        return list
    }

    private val tracksLock = Mutex()
    private val trackCache = HashMap<String, Pair<Long, List<AudioTrack>>>()

    private suspend fun loadTracks(book: Book): List<AudioTrack> = withContext(Dispatchers.IO) {
        val uri = Uri.parse(book.uri)
        manifest(book)?.let { return@withContext it.tracks }
        if (isFolder(uri)) {
            return@withContext listFolder(uri).map { (path, track) -> AudioTrack(trackTitle(path), track.toString(), chapters = chapters(track, path)) }
                .ifEmpty { throw AppError.FileMissing(book.uri) }
        }
        listOf(AudioTrack(book.title, book.uri, chapters = chapters(uri, Uri.decode(book.uri))))
    }

    private val chapterCache = ConcurrentHashMap<String, List<AudioChapter>>()

    /** Chapters embedded in a local audio file (M4B, MP3); remembered, since the player and its screen both ask. */
    private fun chapters(uri: Uri, name: String): List<AudioChapter> {
        // Opening a SAF document costs a binder call: formats that never carry these chapters are not opened.
        val ext = name.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
        if (ext in AudioFiles.EXTENSIONS && ext !in CHAPTER_EXTENSIONS) return emptyList()
        return chapterCache.getOrPut(uri.toString()) {
            runCatching {
                when (uri.scheme) {
                    "file" -> FileInputStream(File(uri.path!!)).channel.use(EmbeddedChapters::read)
                    "content" -> context.contentResolver.openFileDescriptor(uri, "r")?.let { fd ->
                        ParcelFileDescriptor.AutoCloseInputStream(fd).channel.use(EmbeddedChapters::read)
                    }.orEmpty()
                    else -> emptyList()
                }
            }.onFailure { Timber.d(it, "No embedded chapters in $uri") }.getOrDefault(emptyList())
        }
    }

    fun manifest(book: Book): AudiobookManifest? {
        if (!isManifest(book.uri)) return null
        val text = Files.open(context, Uri.parse(book.uri)).use { it.readBytes().decodeToString() }
        return decode(text)
    }

    /** Replaces the manifest of [book] (only manifests in app storage, which every added one is). */
    internal fun writeManifest(book: Book, manifest: AudiobookManifest) {
        val file = manifestFile(book) ?: throw IOException("Not a stored audiobook manifest: ${book.uri}")
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(AudiobookManifest.serializer(), manifest))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Cannot replace ${file.name}")
        }
    }

    private fun manifestFile(book: Book): File? =
        Uri.parse(book.uri).takeIf { it.scheme == "file" && isManifest(book.uri) }?.path?.let(::File)

    /** Where the offline copies of an online audiobook's tracks are kept (deleted with the book). */
    fun downloadDir(book: Book): File? = manifestFile(book)?.let { downloadDirOf(it) }

    /** Where the book came from, for the player's subtitle. */
    fun origin(book: Book): AudioOrigin = runCatching { manifest(book)?.origin }.getOrNull() ?: AudioOrigin.FILES

    // ---------------------------------------------------------------- adding

    /** Adds an audiobook described by [manifest]; a book from the same source is not added twice. */
    suspend fun add(manifest: AudiobookManifest, cover: ByteArray? = null): ImportOutcome = withContext(Dispatchers.IO) {
        require(manifest.tracks.isNotEmpty()) { "An audiobook needs tracks" }
        val hash = "yaudio:" + sha1(manifest.sourceUrl ?: manifest.tracks.joinToString("\n") { it.uri })
        books.findByHash(hash)?.let { return@withContext ImportOutcome.Duplicate(manifest.title, it) }
        val file = File(dir, "${UUID.randomUUID()}.${AudiobookManifest.EXTENSION}")
        file.writeText(json.encodeToString(AudiobookManifest.serializer(), manifest))
        val id = try {
            books.insert(entity(manifest.title, manifest.authors, manifest.description, manifest.language, Uri.fromFile(file).toString(), false, file.length(), hash), manifest.authors, emptyList())
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        (cover ?: manifest.coverUrl?.let(::download))?.let { runCatching { books.setCover(id, it) } }
        ImportOutcome.Added(manifest.title, id)
    }

    /**
     * Adds a folder of audio files as one book. [linked] folders stay where they are (SAF); unlinked ones live in app
     * storage and are deleted with the book.
     */
    suspend fun addFolder(uri: Uri, name: String, linked: Boolean, scanFolder: String? = null): ImportOutcome = withContext(Dispatchers.IO) {
        books.findByUri(uri.toString())?.let { return@withContext ImportOutcome.Duplicate(name, it) }
        val tracks = listFolder(uri)
        if (tracks.isEmpty()) throw AppError.UnsupportedFormat("audio")
        val hash = "audiodir:" + if (linked) uri.toString() else sha1(tracks.joinToString("\n") { it.first })
        books.findByHash(hash)?.let { return@withContext ImportOutcome.Duplicate(name, it) }
        val image = if (uri.scheme == "file") File(uri.path!!).listFiles()?.firstOrNull { f -> f.isFile && f.nameWithoutExtension.lowercase() in COVER_NAMES && f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }?.readBytes() else null
        val meta = AudioMeta.extract(context, tracks.first().second, name, multiTrack = tracks.size > 1, cover = image)
        val size = if (uri.scheme == "file") Files.dirSize(File(uri.path!!)) else 0L
        val id = books.insert(entity(meta.title, meta.authors, null, null, uri.toString(), linked, size, hash, scanFolder), meta.authors, emptyList())
        meta.cover?.let { runCatching { books.setCover(id, it) } }
        ImportOutcome.Added(name, id)
    }

    /** A ZIP of audio files (LibriVox, Internet Archive) is unpacked into app storage and added as a folder. */
    suspend fun addZip(zip: File, name: String): ImportOutcome = withContext(Dispatchers.IO) {
        val target = File(dir, UUID.randomUUID().toString())
        try {
            unzipAudio(zip, target)
            val outcome = addFolder(Uri.fromFile(target), name.substringBeforeLast('.'), linked = false)
            if (outcome !is ImportOutcome.Added) target.deleteRecursively()
            outcome
        } catch (e: Exception) {
            target.deleteRecursively()
            throw e
        }
    }

    // ---------------------------------------------------------------- links

    /**
     * Turns a shared or pasted link into an audiobook: YouTube videos and playlists, podcast/audiobook feeds, audio
     * files and web pages that link one of those.
     */
    suspend fun addLink(text: String): ImportOutcome = withContext(Dispatchers.IO) {
        val url = extractUrl(text) ?: throw AppError.AudioLink(Reason.NO_LINK)
        val host = Uri.parse(url).host.orEmpty().lowercase()
        if (host.startsWith("notebooklm.")) throw AppError.AudioLink(Reason.NOTEBOOKLM)
        YouTubeClient.parseUrl(url)?.let { ref -> return@withContext add(youtube(ref)) }
        add(resolve(url, title = null))
    }

    /** "Listen online" of a catalog book: a feed or an audio file, named and pictured like the catalog title. */
    suspend fun addStream(url: String, title: String, author: String?, coverUrl: String?, description: String?): ImportOutcome = withContext(Dispatchers.IO) {
        val ref = YouTubeClient.parseUrl(url)
        val m = ref?.let { youtube(it) } ?: resolve(url, title)
        add(
            m.copy(
                title = title,
                authors = listOfNotNull(author).ifEmpty { m.authors },
                description = description ?: m.description,
                coverUrl = coverUrl ?: m.coverUrl,
                origin = if (ref != null) AudioOrigin.YOUTUBE else AudioOrigin.CATALOG,
                sourceUrl = url,
            ),
        )
    }

    /** YouTube videos and playlists for words typed instead of a link. */
    suspend fun searchYouTube(query: String): List<YouTubeResult> = youtube.search(query.trim())

    suspend fun addYouTube(result: YouTubeResult): ImportOutcome = withContext(Dispatchers.IO) { add(youtube(result.ref)) }

    private suspend fun youtube(ref: YouTubeRef): AudiobookManifest = try {
        // Mixes ("RD…") are endless generated lists, not books.
        val list = ref.playlistId?.takeIf { !it.startsWith("RD") }
        when {
            ref.channelUrl != null -> youtube.channel(ref.channelUrl)
            list != null -> youtube.playlist(list)
            else -> youtube.video(ref.videoId ?: throw AppError.AudioLink(Reason.UNSUPPORTED))
        }
    } catch (e: YouTubeException) {
        throw AppError.AudioLink(if (e.reason == YouTubeException.Reason.NOT_FOUND) Reason.UNSUPPORTED else Reason.YOUTUBE_BLOCKED, e)
    }

    /** A direct audio file, a feed, or a page linking one of them. */
    private fun resolve(url: String, title: String?, depth: Int = 0): AudiobookManifest {
        val request = Request.Builder().url(url).build()
        client().newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw AppError.Http(r.code)
            val type = r.body.contentType()?.toString().orEmpty().lowercase()
            val finalUrl = r.request.url.toString()
            if (type.startsWith("audio/") || AudioFiles.isAudio(finalUrl) && !type.contains("html") && !type.contains("xml")) {
                val name = title ?: Uri.parse(finalUrl).lastPathSegment?.substringBeforeLast('.')?.replace('_', ' ') ?: finalUrl
                return AudiobookManifest(title = name, origin = AudioOrigin.URL, sourceUrl = url, tracks = listOf(AudioTrack(name, finalUrl)))
            }
            // Pages and feeds are text; a few megabytes are plenty for either.
            val body = r.body.source().let { src -> src.request(MAX_PAGE); src.buffer.clone().readUtf8() }
            if (type.contains("xml") || PodcastFeed.looksLikeFeed(body)) {
                PodcastFeed.parse(body, finalUrl)?.let { return it }
            }
            if (depth == 0 && type.contains("html")) {
                val doc = Jsoup.parse(body, finalUrl)
                val feed = doc.select("link[rel=alternate][type~=(rss|atom)]").firstOrNull()?.absUrl("href")
                val audio = doc.selectFirst("meta[property=og:audio], meta[property=og:audio:url]")?.absUrl("content")?.takeIf { it.isNotBlank() }
                    ?: doc.selectFirst("audio[src], audio > source[src]")?.absUrl("src")?.takeIf { it.isNotBlank() }
                val name = title ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() } ?: doc.title().takeIf { it.isNotBlank() }
                feed?.takeIf { it.isNotBlank() }?.let { return resolve(it, title, depth + 1) }
                audio?.let { return resolve(it, name, depth + 1) }
            }
        }
        throw AppError.AudioLink(Reason.UNSUPPORTED)
    }

    // ---------------------------------------------------------------- helpers

    private fun download(url: String): ByteArray? = runCatching {
        client().newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body.bytes() else null }
    }.onFailure { Timber.d(it, "Cover download failed") }.getOrNull()

    private fun entity(
        title: String, authors: List<String>, description: String?, language: String?, uri: String, linked: Boolean, size: Long, hash: String,
        scanFolder: String? = null,
    ) = BookEntity(
        title = title,
        sortTitle = sortTitle(title),
        authorsText = authorsToText(authors),
        seriesName = null,
        seriesId = null,
        seriesIndex = null,
        language = language,
        description = description,
        publisher = null,
        format = BookFormat.AUDIO.name,
        uri = uri,
        isLinked = linked,
        fileSize = size,
        fileHash = hash,
        coverPath = null,
        status = ReadingStatus.NEW.name,
        isManga = false,
        addedAt = System.currentTimeMillis(),
        lastReadAt = null,
        progress = 0f,
        readerSettingsJson = null,
        scanFolder = scanFolder,
    )

    /** Audio files under a folder (at most [MAX_DEPTH] levels), in natural order of their paths. */
    fun listFolder(uri: Uri): List<Pair<String, Uri>> {
        val out = mutableListOf<Pair<String, Uri>>()
        if (uri.scheme == "file") {
            val root = File(uri.path!!)
            root.walkTopDown().maxDepth(MAX_DEPTH).filter { it.isFile && AudioFiles.isAudio(it.name) }
                .forEach { out += it.relativeTo(root).invariantSeparatorsPath to Uri.fromFile(it) }
        } else {
            val docId = if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri)
            fun walk(id: String, prefix: String, depth: Int) {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, id)
                val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
                val dirs = mutableListOf<Pair<String, String>>()
                context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id2 = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < MAX_DEPTH && !name.startsWith(".")) dirs += id2 to "$prefix$name/"
                        } else if (AudioFiles.isAudio(name)) {
                            out += "$prefix$name" to DocumentsContract.buildDocumentUriUsingTree(uri, id2)
                        }
                    }
                }
                dirs.forEach { (d, p) -> walk(d, p, depth + 1) }
            }
            walk(docId, "", 0)
        }
        return out.sortedWith { a, b -> NaturalOrder.compare(a.first, b.first) }
    }

    private fun isFolder(uri: Uri): Boolean = when (uri.scheme) {
        "file" -> File(uri.path!!).isDirectory
        "content" -> runCatching {
            DocumentsContract.isTreeUri(uri) && context.contentResolver.getType(
                if (DocumentsContract.isDocumentUri(context, uri)) uri else DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri)),
            ) == DocumentsContract.Document.MIME_TYPE_DIR
        }.getOrDefault(false)
        else -> false
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
        private const val MAX_DEPTH = 3
        private const val MAX_PAGE = 4L * 1024 * 1024
        private const val MAX_ZIP_BYTES = 8L * 1024 * 1024 * 1024
        private val COVER_NAMES = setOf("cover", "folder", "front", "album")
        private val CHAPTER_EXTENSIONS = setOf("m4b", "m4a", "mp4", "aac", "mp3")
        private const val TRACKS_TTL_MS = 30_000L
        private val URL = Regex("""https?://[^\s<>"']+""")

        fun isManifest(uri: String) = uri.substringBefore('?').endsWith(".${AudiobookManifest.EXTENSION}", ignoreCase = true)

        /** `…/x.yaudio` keeps its downloaded tracks in `…/x.files/`. */
        fun downloadDirOf(manifest: File): File = File(manifest.parentFile, manifest.name.substringBeforeLast('.') + ".files")

        /** The address to play [track] from: its downloaded copy while it exists, else the track itself. */
        fun playableUri(track: AudioTrack): String =
            track.file?.takeIf { it.startsWith("file://") && File(it.removePrefix("file://")).isFile } ?: track.uri

        fun decode(text: String): AudiobookManifest = json.decodeFromString(AudiobookManifest.serializer(), text)

        fun extractUrl(text: String): String? {
            val t = text.trim()
            URL.find(t)?.let { return it.value.trimEnd('.', ',', ')', ']') }
            // "youtu.be/…" or "www.youtube.com/…" pasted without the scheme.
            return t.takeIf { !it.contains(' ') && it.contains('.') && it.contains('/') }?.let { "https://$it" }
        }

        /** "03 - Chapter Three.mp3" → "03 - Chapter Three"; folders of a path are dropped. */
        fun trackTitle(path: String): String = path.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim()

        /** True when a ZIP holds audio and no more images than audio files (a comic stays a comic). */
        fun isAudioZip(entries: List<String>): Boolean {
            val files = entries.filter { !it.endsWith('/') && !it.startsWith("__MACOSX") }
            val audio = files.count(AudioFiles::isAudio)
            return audio > 0 && audio >= files.count { app.yomu.data.format.FormatDetector.isImage(it) }
        }

        fun unzipAudio(zip: File, target: File) {
            target.mkdirs()
            var total = 0L
            ZipFile(zip).use { z ->
                val root = target.canonicalFile
                z.entries().asSequence().filter { !it.isDirectory && !it.name.startsWith("__MACOSX") }.forEach { e ->
                    val name = e.name.substringAfterLast('/')
                    val keep = AudioFiles.isAudio(name) || name.substringBeforeLast('.').lowercase() in COVER_NAMES || app.yomu.data.format.FormatDetector.isImage(name)
                    if (!keep) return@forEach
                    val out = File(target, e.name).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) throw IOException("Unsafe path in ZIP: ${e.name}")
                    out.parentFile?.mkdirs()
                    z.getInputStream(e).use { input -> out.outputStream().use { total += input.copyTo(it) } }
                    if (total > MAX_ZIP_BYTES) throw IOException("ZIP too large")
                }
            }
        }

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

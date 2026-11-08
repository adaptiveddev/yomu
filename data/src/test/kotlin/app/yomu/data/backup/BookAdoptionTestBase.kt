package app.yomu.data.backup

import android.content.Context
import android.content.ContextWrapper
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.data.library.CoverStore
import app.yomu.data.library.ImportOutcome
import app.yomu.data.library.ImportService
import app.yomu.data.repository.BookRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Two simulated libraries exchange a book through one shared sync target; the file then changes
 * and the change is adopted (deliberately or automatically), re-keying every record. Each scenario
 * lives in its own class: Robolectric workers on the host are fragile with multi-method classes of
 * this weight, and per-class JVMs keep the runs observable.
 */
abstract class BookAdoptionTestBase {
    private val app: Context = org.robolectric.RuntimeEnvironment.getApplication()
    private val root = File(app.cacheDir, "book-adoption-tests-${UUID.randomUUID()}").apply { mkdirs() }
    private val devices = mutableListOf<Device>()

    inner class Device(name: String) {
        private val dir = File(root, name).apply { mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(dir, "files").apply { mkdirs() }
            override fun getCacheDir() = File(dir, "cache").apply { mkdirs() }
        }
        val settings = AppSettings(context)
        val db = YomuDatabase.build(context, inMemory = true)
        private val covers = CoverStore(context)
        val importer = ImportService(context, BookRepositoryImpl(context, db, covers), settings, covers)
        val manager = SyncManager(context, db, settings, { okhttp3.OkHttpClient() }, importer)

        suspend fun add(name: String, bytes: ByteArray): Long {
            val f = File(context.filesDir, name).apply { parentFile?.mkdirs(); writeBytes(bytes) }
            val result = importer.importLocalFile(f, name)
            assertTrue(result.toString(), result is ImportOutcome.Added)
            return (result as ImportOutcome.Added).bookId
        }

        init { devices += this }
    }

    /** One shared sync target: metadata + book index + blobs + tracker links with CAS writes. */
    class FakeBackend : SyncBackend, BookFilesTransport, TrackLinkTransport {
        override val id = "fake"
        var doc: ByteArray? = null
        var docRevision = 0
        var index: ByteArray? = null
        var indexRevision = 0
        var links: ByteArray? = null
        var linksRevision = 0
        val blobs = HashMap<String, ByteArray>()

        private fun cas(current: ByteArray?, revision: Int, previous: SyncFile?, update: (ByteArray) -> Unit) {
            if (previous?.tag != current?.let { "\"$revision\"" }) throw SyncConflictException()
            update(previous?.bytes ?: ByteArray(0))
        }
        override suspend fun read(): SyncFile? = doc?.let { SyncFile(it.copyOf(), "\"$docRevision\"") }
        override suspend fun write(bytes: ByteArray, previous: SyncFile?) {
            cas(doc, docRevision, previous) { doc = bytes.copyOf() }
            docRevision++
        }
        override suspend fun check() { }
        override suspend fun readBookIndex(): SyncFile? = index?.let { SyncFile(it.copyOf(), "\"$indexRevision\"") }
        override suspend fun writeBookIndex(bytes: ByteArray, previous: SyncFile?) {
            cas(index, indexRevision, previous) { index = bytes.copyOf() }
            indexRevision++
        }
        override suspend fun uploadBook(sha256: String, file: File) {
            val existing = blobs[sha256]
            if (existing != null) BookFileSync.verifyStream(existing.inputStream(), sha256, file.length())
            else blobs[sha256] = file.readBytes()
        }
        override suspend fun <T> readBook(sha256: String, consume: (InputStream) -> T): T {
            val bytes = blobs[sha256] ?: throw java.io.IOException("missing blob")
            return consume(bytes.inputStream())
        }
        override suspend fun readTrackLinks(): SyncFile? = links?.let { SyncFile(it.copyOf(), "\"$linksRevision\"") }
        override suspend fun writeTrackLinks(bytes: ByteArray, previous: SyncFile?) {
            cas(links, linksRevision, previous) { links = bytes.copyOf() }
            linksRevision++
        }
    }

    @After fun closeDevices(): Unit = runBlocking {
        // The DataStore delegate resolves through the application context: reset what the tests set.
        devices.forEach { it.settings.syncBookFiles.set(false); it.settings.syncDeletions.set(true) }
        devices.forEach { it.db.close() }
        root.deleteRecursively()
    }

    suspend fun Device.sync(backend: FakeBackend) {
        settings.syncBookFiles.set(true)
        // Mirror SyncManager.cycle(): an adoption inside the run invalidates this run's read, and
        // the conditional merge write fails once; the retry re-reads the re-keyed documents.
        var attempt = 0
        while (true) {
            try { manager.runOnce(backend); break }
            catch (e: SyncConflictException) { if (++attempt >= 3) throw e }
        }
    }

    fun Device.problemKinds(): List<String> = SyncProblemStore(context).load().map { it.kind }

    /** The book's file as production code resolves it (the stored uri is percent-encoded). */
    fun bookFile(uri: String): File = File(android.net.Uri.parse(uri).path!!)
}

package app.yomu.data.backup

import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.content.ContextWrapper
import app.yomu.core.database.YomuDatabase
import app.yomu.core.datastore.AppSettings
import app.yomu.data.library.CoverStore
import app.yomu.data.library.ImportOutcome
import app.yomu.data.library.ImportService
import app.yomu.data.repository.BookRepositoryImpl
import android.content.ContentProvider
import android.content.ContentValues
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/** Tests SAF through a fake document provider, not a substitute for real cloud/device testing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderSidecarsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val root = Uri.parse("content://app.yomu.folder.sidecar/tree/root")
    private val backend get() = FolderSyncBackend(context, root)
    private lateinit var provider: FakeDocuments
    private lateinit var dir: File

    class FakeDocuments : ContentProvider() {
        lateinit var dir: File
        var denyRename = false
        var failRenameTo: String? = null
        var corruptRenameTo: String? = null
        private fun file(id: String): File {
            require(id != "root" && !id.contains('/') && !id.contains(".."))
            return File(dir, id)
        }
        override fun onCreate() = true
        override fun getType(uri: Uri): String = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            return MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)).apply {
                dir.listFiles().orEmpty().sortedBy { it.name }.forEach { f -> addRow(arrayOf(f.name, f.name, "application/octet-stream")) }
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val f = file(DocumentsContract.getDocumentId(uri))
            if (mode == "r") return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE)
        }
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            val input = requireNotNull(extras)
            @Suppress("DEPRECATION")
            val uri = input.getParcelable<Uri>("uri")!!
            val documentId = DocumentsContract.getDocumentId(uri)
            val name = input.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val resultId = when (method) {
                "android:createDocument" -> { val f = file(name!!); if (!f.createNewFile()) throw IOException("already exists"); name }
                "android:renameDocument" -> {
                    if (denyRename || name == failRenameTo) throw UnsupportedOperationException("rename unsupported")
                    val target = file(name!!); if (target.exists() || !file(documentId).renameTo(target)) throw IOException("rename failed")
                    if (name == corruptRenameTo) target.writeText("corrupt")
                    name
                }
                "android:deleteDocument" -> { if (!file(documentId).delete()) throw FileNotFoundException(documentId); null }
                else -> throw UnsupportedOperationException(method)
            }
            return Bundle().apply { if (resultId != null) putParcelable("uri", DocumentsContract.buildDocumentUriUsingTree(uri, resultId)) }
        }
    }
    @Before fun setup() {
        dir = File(context.cacheDir, "folder-sidecars-${UUID.randomUUID()}").apply { mkdirs() }
        provider = FakeDocuments().apply { this.dir = this@FolderSidecarsTest.dir }
        provider.attachInfo(context, ProviderInfo().apply { authority = "app.yomu.folder.sidecar"; exported = true; grantUriPermissions = true; readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS" })
        ShadowContentResolver.registerProviderInternal("app.yomu.folder.sidecar", provider)
    }
    @After fun close() { dir.deleteRecursively() }

    @Test fun manifestsRoundTripAndDetectChanges() = runBlocking {
        assertNull(backend.readBookIndex()); assertNull(backend.readTrackLinks())
        backend.writeBookIndex("book".toByteArray(), null)
        backend.writeTrackLinks("track".toByteArray(), null)
        val books = backend.readBookIndex()!!; val tracks = backend.readTrackLinks()!!
        assertEquals("book", books.bytes.decodeToString()); assertEquals("track", tracks.bytes.decodeToString())
        File(dir, "yomu-books.json").writeText("other device")
        assertTrue(runCatching { backend.writeBookIndex("mine".toByteArray(), books) }.exceptionOrNull() is SyncConflictException)
        backend.writeTrackLinks("updated".toByteArray(), tracks)
        assertEquals("other device", File(dir, "yomu-books.json").readText())
    }
    @Test fun folderIndexIsSeparateAndProtectsAgainstVisibleConflicts() = runBlocking {
        assertNull(backend.readFolderIndex())
        backend.writeFolderIndex("first".toByteArray(), null)
        val previous = backend.readFolderIndex()!!
        assertEquals("first", previous.bytes.decodeToString())
        File(dir, "yomu-folders.json").writeText("other")
        assertTrue(runCatching { backend.writeFolderIndex("mine".toByteArray(), previous) }.exceptionOrNull() is SyncConflictException)
        assertEquals("other", File(dir, "yomu-folders.json").readText())
    }

    @Test fun interruptedIndexReplacementPreservesRecoveryAndBlocksFurtherSync() = runBlocking {
        val names = listOf("yomu-sync.json", "yomu-books.json", "yomu-tracking.json")
        for (name in names) {
            fun read() = when (name) {
                "yomu-sync.json" -> runBlocking { backend.read() }
                "yomu-books.json" -> runBlocking { backend.readBookIndex() }
                else -> runBlocking { backend.readTrackLinks() }
            }
            suspend fun write(bytes: ByteArray, previous: SyncFile?) = when (name) {
                "yomu-sync.json" -> backend.write(bytes, previous)
                "yomu-books.json" -> backend.writeBookIndex(bytes, previous)
                else -> backend.writeTrackLinks(bytes, previous)
            }
            write("original".toByteArray(), null)
            val previous = read()!!
            provider.failRenameTo = name
            assertTrue(runCatching { write("new".toByteArray(), previous) }.isFailure)
            val backups = dir.listFiles().orEmpty().filter { it.name.startsWith("$name.yomu-backup-") }
            assertEquals(1, backups.size)
            assertEquals("original", backups.single().readText())
            val error = runCatching { read() }.exceptionOrNull()
            assertTrue(error is IOException && error.message!!.contains("recovery copy"))
            assertTrue(runCatching { write("overwrite".toByteArray(), null) }.exceptionOrNull() is IOException)
            assertEquals("original", backups.single().readText())
            provider.failRenameTo = null
            assertTrue(backups.single().renameTo(File(dir, name))) // explicit recovery
            assertEquals("original", read()!!.bytes.decodeToString())
            assertTrue(File(dir, name).delete())
        }
    }

    @Test fun corruptionAfterPublishingPreservesPreviousIndexAndFailsClosed() = runBlocking {
        backend.writeBookIndex("original".toByteArray(), null)
        val previous = backend.readBookIndex()!!
        provider.corruptRenameTo = "yomu-books.json"
        assertTrue(runCatching { backend.writeBookIndex("new".toByteArray(), previous) }.isFailure)
        val backups = dir.listFiles().orEmpty().filter { it.name.startsWith("yomu-books.json.yomu-backup-") }
        assertEquals(1, backups.size)
        assertEquals("original", backups.single().readText())
        assertEquals("corrupt", File(dir, "yomu-books.json").readText())
        val e = runCatching { backend.readBookIndex() }.exceptionOrNull()
        assertTrue(e is IOException && e.message!!.contains("recovery copy"))
    }

    @Test fun providerWithoutRenameNeverTruncatesExistingSyncFile() = runBlocking {
        backend.writeTrackLinks("original".toByteArray(), null)
        val previous = backend.readTrackLinks()!!
        provider.denyRename = true
        assertTrue(runCatching { backend.writeTrackLinks("new".toByteArray(), previous) }.isFailure)
        assertEquals("original", File(dir, "yomu-tracking.json").readText())
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test fun immutableBlobIsVerifiedAndReused() = runBlocking {
        val local = File(dir.parentFile, "local-${UUID.randomUUID()}").apply { writeText("a book of text") }
        try {
            val digest = local.inputStream().use(BookFileSync::digest)
            backend.uploadBook(digest.sha256, local)
            assertEquals("a book of text", backend.readBook(digest.sha256) { it.readBytes().decodeToString() })
            backend.uploadBook(digest.sha256, local)
            File(dir, "yomu-book-${digest.sha256}.bin").writeText("corrupt")
            assertTrue(runCatching { backend.uploadBook(digest.sha256, local) }.exceptionOrNull() is IOException)
            assertEquals("corrupt", File(dir, "yomu-book-${digest.sha256}.bin").readText())
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally { local.delete() }
    }
    @Test fun twoDevicesTransferAndKeepLocalDeletionLocal() = runBlocking {
        val devicesRoot = File(dir.parentFile, "folder-devices-${UUID.randomUUID()}").apply { mkdirs() }
        fun deviceContext(name: String) = object : ContextWrapper(context) {
            override fun getFilesDir() = File(devicesRoot, "$name/files").apply { mkdirs() }
            override fun getCacheDir() = File(devicesRoot, "$name/cache").apply { mkdirs() }
        }
        val a = deviceContext("a"); val b = deviceContext("b")
        val dbA = YomuDatabase.build(a, inMemory = true); val dbB = YomuDatabase.build(b, inMemory = true)
        fun importer(c: android.content.Context, db: YomuDatabase): ImportService {
            val covers = CoverStore(c)
            return ImportService(c, BookRepositoryImpl(c, db, covers), AppSettings(c), covers)
        }
        try {
            val importA = importer(a, dbA); val importB = importer(b, dbB)
            val file = File(a.filesDir, "example.txt").apply { writeText("example book content") }
            val added = importA.importLocalFile(file, file.name) as ImportOutcome.Added
            val syncA = BookFileSync(a, dbA, importA); val syncB = BookFileSync(b, dbB, importB)
            assertEquals(0, syncA.sync(backend, "shared")); assertEquals(1, syncB.sync(backend, "shared"))
            val copy = dbB.books().all().single()
            assertEquals("example book content", File(Uri.parse(copy.uri).path!!).readText())
            dbA.books().delete(listOf(added.bookId))
            assertEquals(0, syncA.sync(backend, "shared")); assertTrue(dbA.books().all().isEmpty())
            assertEquals(1, dbB.books().all().size)
        } finally { dbA.close(); dbB.close(); devicesRoot.deleteRecursively() }
    }

    @Test fun failedPublicationNeverCreatesAnIndexOrVisibleBlob() = runBlocking {
        val local = File(dir.parentFile, "local-${UUID.randomUUID()}").apply { writeText("book") }
        try {
            val digest = local.inputStream().use(BookFileSync::digest)
            provider.denyRename = true
            assertTrue(runCatching { backend.uploadBook(digest.sha256, local) }.isFailure)
            assertFalse(File(dir, "yomu-book-${digest.sha256}.bin").exists())
            assertNull(backend.readBookIndex())
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally { local.delete() }
    }
}

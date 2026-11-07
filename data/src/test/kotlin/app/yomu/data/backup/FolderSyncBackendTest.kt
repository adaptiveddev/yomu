package app.yomu.data.backup

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
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
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderSyncBackendTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var fake: FakeProvider
    private val root = Uri.parse("content://app.yomu.foldertest/tree/root")
    private val backend get() = FolderSyncBackend(context, root)

    class FakeProvider : ContentProvider() {
        lateinit var file: File
        var failQuery = false
        var names = listOf(SyncManager.FILE_NAME)
        var writes = 0
        override fun onCreate() = true
        override fun getType(uri: Uri): String = "application/json"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            if (failQuery) return null
            return MatrixCursor(arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)).apply {
                names.forEachIndexed { i, name -> addRow(arrayOf("file$i", name, "application/json")) }
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (mode != "r") { writes++; throw IOException("write not expected") }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    @Before fun setup() {
        fake = FakeProvider()
        fake.file = File.createTempFile("folder-sync-test", ".json", context.cacheDir).apply { writeText("original") }
        fake.attachInfo(context, ProviderInfo().apply { authority = "app.yomu.foldertest"; exported = true })
        ShadowContentResolver.registerProviderInternal("app.yomu.foldertest", fake)
    }
    @After fun close() { fake.file.delete() }

    @Test fun changedFileIsNotOverwritten() = runBlocking {
        val snapshot = backend.read()!!
        assertEquals("original", snapshot.bytes.decodeToString())
        fake.file.writeText("other device")
        assertTrue(runCatching { backend.write("mine".toByteArray(), snapshot) }.exceptionOrNull() is SyncConflictException)
        assertEquals("other device", fake.file.readText())
        assertEquals(0, fake.writes)
    }

    @Test fun inaccessibleOrAmbiguousFolderDoesNotLookEmpty() = runBlocking {
        fake.failQuery = true
        assertTrue(runCatching { backend.read() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { backend.check() }.exceptionOrNull() is IOException)
        fake.failQuery = false
        fake.names = listOf(SyncManager.FILE_NAME, SyncManager.FILE_NAME)
        assertTrue(runCatching { backend.read() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { backend.write("mine".toByteArray(), null) }.exceptionOrNull() is IOException)
        assertEquals(0, fake.writes)
    }

    @Test fun removedFileIsNotSilentlyRecreated() = runBlocking {
        val snapshot = backend.read()!!
        fake.names = emptyList()
        assertTrue(runCatching { backend.write("mine".toByteArray(), snapshot) }.exceptionOrNull() is SyncConflictException)
        assertEquals(0, fake.writes)
    }
}

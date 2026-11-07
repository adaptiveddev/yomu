package app.yomu.data.backup

import android.net.Uri
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.provider.DocumentsContract
import app.yomu.data.book.DocumentTreeContainer
import app.yomu.data.book.Containers
import org.robolectric.shadows.ShadowContentResolver
import java.io.IOException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderSnapshotTest {
    private val context = RuntimeEnvironment.getApplication()
    private val root = File(context.cacheDir, "folder-snapshot-${UUID.randomUUID()}").apply { mkdirs() }
    private val id = "folder:8e7cd974-c1aa-43c0-bb8c-529f4065d819"
    @After fun cleanup() { root.deleteRecursively() }
    @Test fun naturalOrderAndChangesAreCaptured() {
        val dir = File(root, "chapter").apply { mkdirs() }
        File(dir, "10.jpg").writeText("ten")
        val second = File(dir, "2.jpg").apply { writeText("two") }
        File(dir, "notes.txt").writeText("ignored")
        val first = FolderSnapshot.snapshot(context, Uri.fromFile(root), id, "Manga")
        assertEquals(listOf("chapter/2.jpg", "chapter/10.jpg"), first.pages.map { it.path })
        second.writeText("changed")
        File(dir, "1.png").writeText("one")
        File(dir, "10.jpg").delete()
        val next = FolderSnapshot.snapshot(context, Uri.fromFile(root), id, "Manga")
        assertEquals(listOf("chapter/1.png", "chapter/2.jpg"), next.pages.map { it.path })
        assertNotEquals(first.pages.first().sha256, next.pages.last().sha256)
    }
    @Test fun missingAndEmptyFoldersFailClosed() {
        assertTrue(runCatching { FolderSnapshot.snapshot(context, Uri.fromFile(root), id, "Manga") }.isFailure)
        assertTrue(runCatching { FolderSnapshot.enumerate(context, Uri.fromFile(File(root, "missing"))) }.isFailure)
    }
    class UnavailableProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    }
    @Test fun unavailableProviderNeverLooksLikeAnEmptyFolder() {
        val provider = UnavailableProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "app.yomu.folder.unavailable"; exported = true })
        ShadowContentResolver.registerProviderInternal("app.yomu.folder.unavailable", provider)
        val tree = Uri.parse("content://app.yomu.folder.unavailable/tree/root")
        assertTrue(runCatching { FolderSnapshot.enumerate(context, tree) }.exceptionOrNull() is IOException)
    }
    @Test fun symbolicLinksCannotEscapeFolder() {
        val target = File(root.parentFile, "outside-${UUID.randomUUID()}.jpg").apply { writeText("outside") }
        try {
            java.nio.file.Files.createSymbolicLink(File(root, "escape.jpg").toPath(), target.toPath())
            assertTrue(runCatching { FolderSnapshot.enumerate(context, Uri.fromFile(root)) }.isFailure)
        } finally { target.delete() }
    }
    class NestedProvider : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val id = DocumentsContract.getDocumentId(uri)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            return MatrixCursor(columns).apply {
                when (id) {
                    "root" -> { addRow(arrayOf("root-image", "root.jpg", "image/jpeg")); addRow(arrayOf("sub", "sub", DocumentsContract.Document.MIME_TYPE_DIR)) }
                    "sub" -> { addRow(arrayOf("p10", "10.jpg", "image/jpeg")); addRow(arrayOf("p2", "2.jpg", "image/jpeg")); addRow(arrayOf("chapter", "chapter", DocumentsContract.Document.MIME_TYPE_DIR)) }
                    "chapter" -> addRow(arrayOf("p1", "1.png", "image/png"))
                    "document" -> addRow(arrayOf("tree-image", "page.jpg", "image/jpeg"))
                }
            }
        }
    }
    @Test fun readerAndSyncUseSelectedSubfolderAndTheSamePageOrder() {
        val provider = NestedProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "app.yomu.folder.nested"; exported = true })
        ShadowContentResolver.registerProviderInternal("app.yomu.folder.nested", provider)
        val tree = Uri.parse("content://app.yomu.folder.nested/tree/root")
        val subfolder = DocumentsContract.buildDocumentUriUsingTree(tree, "sub")
        val synced = FolderSnapshot.enumerate(context, subfolder).map { it.path }
        val read = DocumentTreeContainer(context, subfolder).use { Containers.imageEntries(it) }
        assertEquals(listOf("2.jpg", "10.jpg", "chapter/1.png"), synced)
        assertEquals(synced, read)
        assertFalse(read.contains("root.jpg"))
    }

    @Test fun treeIdNamedDocumentIsNotMistakenForDocumentUri() {
        val provider = NestedProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "app.yomu.folder.literal"; exported = true })
        ShadowContentResolver.registerProviderInternal("app.yomu.folder.literal", provider)
        val tree = Uri.parse("content://app.yomu.folder.literal/tree/document")
        assertEquals(listOf("page.jpg"), FolderSnapshot.enumerate(context, tree).map { it.path })
        assertEquals(listOf("page.jpg"), DocumentTreeContainer(context, tree).use { Containers.imageEntries(it) })
    }

}

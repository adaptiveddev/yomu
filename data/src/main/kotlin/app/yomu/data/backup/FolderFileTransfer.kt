package app.yomu.data.backup

import android.content.Context
import android.net.Uri
import app.yomu.core.common.Files
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/** Stages every page before publication. Callers publish the manifest only after upload completes. */
internal object FolderFileTransfer {
    suspend fun upload(context: Context, uri: Uri, entry: FolderEntry, transport: FolderFilesTransport) {
        FolderManifestCodec.validate(FolderManifest(folders = listOf(entry)))
        val sources = FolderSnapshot.enumerate(context, uri)
        if (sources.map { it.path } != entry.pages.map { it.path }) throw IOException("Image folder changed during upload")
        val tempDir = File(context.cacheDir, "folder-sync").apply { mkdirs() }
        for ((page, source) in entry.pages.zip(sources)) {
            currentCoroutineContext().ensureActive()
            val temp = File.createTempFile("page-", ".part", tempDir)
            try {
                Files.open(context, source.uri).use { input ->
                    temp.outputStream().use { out -> BookFileSync.copyVerified(input, out, page.sha256, page.size) }
                }
                transport.uploadBook(page.sha256, temp)
            } finally { temp.delete() }
        }
        if (FolderSnapshot.snapshot(context, uri, entry.id, entry.title).pages != entry.pages)
            throw IOException("Image folder changed during upload; its index was not published")
    }

    /** Returns a verified private folder. The caller owns it and must remove it if not committed. */
    suspend fun download(context: Context, entry: FolderEntry, transport: FolderFilesTransport): File {
        FolderManifestCodec.validate(FolderManifest(folders = listOf(entry)))
        val parent = File(context.filesDir, "synced-books").apply { mkdirs() }
        val stage = java.nio.file.Files.createTempDirectory(parent.toPath(), "folder-").toFile()
        var complete = false
        try {
            for (page in entry.pages) {
                currentCoroutineContext().ensureActive()
                val dest = File(stage, page.path)
                if (!dest.canonicalPath.startsWith(stage.canonicalPath + File.separator)) throw IOException("Unsafe image path")
                if (dest.parentFile?.mkdirs() == false && dest.parentFile?.isDirectory != true) throw IOException("Cannot create image folder")
                transport.readBook(page.sha256) { input ->
                    dest.outputStream().use { out -> BookFileSync.copyVerified(input, out, page.sha256, page.size) }
                }
            }
            val copied = FolderSnapshot.snapshot(context, Uri.fromFile(stage), entry.id, entry.title)
            if (copied.pages != entry.pages) throw IOException("Downloaded image folder failed verification")
            complete = true
            return stage
        } finally { if (!complete) stage.deleteRecursively() }
    }
}

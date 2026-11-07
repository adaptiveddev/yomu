package app.yomu.data.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.yomu.core.common.Files
import app.yomu.data.format.FormatDetector
import app.yomu.data.format.NaturalOrder
import java.io.File
import java.io.IOException

/** Strict enumeration: an inaccessible subtree must never look like an intentionally deleted page. */
internal object FolderSnapshot {
    internal data class SourcePage(val path: String, val uri: Uri)
    private const val MAX_ENTRIES = 30000
    private const val MAX_DEPTH = 4 // Matches the SAF reader; deeper pages must not be silently lost.

    internal fun enumerate(context: Context, uri: Uri): List<SourcePage> {
        val pages = mutableListOf<SourcePage>()
        val seenDocumentIds = HashSet<String>()
        var entries = 0
        fun add(path: String, page: Uri) {
            if (!FormatDetector.isImage(path) || path.startsWith("__MACOSX")) return
            if (!FolderManifestCodec.validPath(path)) throw IOException("Unsafe image path in folder: $path")
            pages += SourcePage(path, page)
        }
        fun checkName(name: String) {
            if (name.isBlank() || name == "." || name == ".." || name.length > 200 ||
                name.endsWith('.') || name.endsWith(' ') || name.any { it == '/' || it == '\\' || it == ':' || it.code < 32 || it.code == 127 })
                throw IOException("Unsafe name in image folder")
            if (++entries > MAX_ENTRIES) throw IOException("Image folder contains too many entries")
        }
        when (uri.scheme) {
            "file", null -> {
                val root = File(uri.path ?: throw IOException("Invalid folder path"))
                if (!root.isDirectory || java.nio.file.Files.isSymbolicLink(root.toPath())) throw IOException("Image folder is missing or not a directory")
                fun walk(dir: File, prefix: String, depth: Int) {
                    val children = dir.listFiles() ?: throw IOException("Cannot list image folder: $prefix")
                    for (file in children) {
                        checkName(file.name)
                        if (java.nio.file.Files.isSymbolicLink(file.toPath())) throw IOException("Symbolic links are not supported in image folders")
                        val path = prefix + file.name
                        when {
                            file.isDirectory -> {
                                if (depth >= MAX_DEPTH) throw IOException("Image folder is nested too deeply")
                                walk(file, "$path/", depth + 1)
                            }
                            file.isFile -> add(path, Uri.fromFile(file))
                            else -> throw IOException("Cannot read folder entry: $path")
                        }
                    }
                }
                walk(root, "", 0)
            }
            "content" -> {
                val root = try {
                    // A tree ID can itself be the literal word "document". Only the
                    // structural /tree/{id}/document/{id} segment selects a subfolder.
                    val parts = uri.pathSegments
                    val tree = parts.indexOf("tree")
                    if (tree >= 0 && parts.size > tree + 3 && parts[tree + 2] == "document")
                        DocumentsContract.getDocumentId(uri) else DocumentsContract.getTreeDocumentId(uri)
                } catch (e: Exception) { throw IOException("Invalid image folder URI", e) }
                fun walk(id: String, prefix: String, depth: Int) {
                    if (!seenDocumentIds.add(id)) throw IOException("Image folder contains a cycle or duplicate document")
                    val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, id)
                    val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val cursor = context.contentResolver.query(childUri, columns, null, null, null) ?: throw IOException("Cannot list image folder: $prefix")
                    cursor.use { c ->
                        val idColumn = c.getColumnIndexOrThrow(columns[0]); val nameColumn = c.getColumnIndexOrThrow(columns[1]); val mimeColumn = c.getColumnIndexOrThrow(columns[2])
                        while (c.moveToNext()) {
                            val childId = c.getString(idColumn) ?: throw IOException("Missing image folder document ID")
                            val name = c.getString(nameColumn) ?: throw IOException("Missing image folder entry name")
                            val mime = c.getString(mimeColumn) ?: throw IOException("Missing image folder entry type")
                            checkName(name)
                            val path = prefix + name
                            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (depth >= MAX_DEPTH) throw IOException("Image folder is nested too deeply")
                                walk(childId, "$path/", depth + 1)
                            } else {
                                if (!seenDocumentIds.add(childId)) throw IOException("Duplicate image folder document")
                                add(path, DocumentsContract.buildDocumentUriUsingTree(uri, childId))
                            }
                        }
                    }
                }
                walk(root, "", 0)
            }
            else -> throw IOException("Unsupported image folder URI")
        }
        return pages.sortedWith { a, b -> NaturalOrder.compare(a.path, b.path) }
    }

    /** Hash every image, then verify the listing and bytes again so a changing folder is not published. */
    internal fun snapshot(context: Context, uri: Uri, id: String, title: String): FolderEntry {
        fun read(): Pair<FolderEntry, List<SourcePage>> {
            val pages = enumerate(context, uri)
            val items = pages.map { page ->
                val digest = Files.open(context, page.uri).use { BookFileSync.digest(it) }
                if (digest.size <= 0) throw IOException("Empty image: ${page.path}")
                FolderPage(page.path, digest.sha256, digest.size)
            }
            return FolderEntry(id, title, items).also { FolderManifestCodec.validate(FolderManifest(folders = listOf(it))) } to pages
        }
        val first = read().first
        val second = read().first
        if (first != second) throw IOException("Image folder changed during the scan; retry synchronization")
        return first
    }
}

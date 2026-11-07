package app.yomu.data.library

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

data class SafFile(val uri: Uri, val name: String, val mime: String, val size: Long, val path: String) {
    val isDirectory: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
}

/** Fast recursive listing of a SAF tree using DocumentsContract queries. */
object SafTree {
    fun walk(context: Context, treeUri: Uri, maxDepth: Int = 8, includeDirs: Boolean = true): List<SafFile> {
        val out = mutableListOf<SafFile>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        fun list(docId: String, path: String, depth: Int) {
            if (depth > maxDepth) return
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val cols = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            val dirs = mutableListOf<Pair<String, String>>()
            runCatching {
                context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2).orEmpty()
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        val f = SafFile(uri, name, mime, size, "$path$name")
                        if (f.isDirectory) {
                            if (!name.startsWith(".")) dirs += id to "$path$name/"
                            if (includeDirs) out += f
                        } else {
                            out += f
                        }
                    }
                }
            }
            dirs.forEach { (id, p) -> list(id, p, depth + 1) }
        }
        list(rootId, "", 0)
        return out
    }
}

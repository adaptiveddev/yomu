package app.yomu.data.book

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Xhtml {
    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Converts an HTML fragment into well formed XHTML. */
    fun fromFragment(html: String): String {
        val doc = Jsoup.parseBodyFragment(html)
        configure(doc)
        return doc.body().html()
    }

    fun configure(doc: Document): Document {
        doc.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
            .prettyPrint(false)
        return doc
    }

    fun page(title: String, body: String, lang: String?, css: String = "../styles.css", bodyClass: String? = null): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"")
        if (!lang.isNullOrBlank()) append(" lang=\"${escape(lang)}\" xml:lang=\"${escape(lang)}\"")
        append(">\n<head><meta charset=\"utf-8\"/><title>").append(escape(title)).append("</title>")
        append("<link rel=\"stylesheet\" type=\"text/css\" href=\"").append(css).append("\"/></head>\n<body")
        if (bodyClass != null) append(" class=\"$bodyClass\"")
        append(">\n").append(body).append("\n</body>\n</html>\n")
    }
}

/** Builds an EPUB 3 publication (with an NCX for EPUB 2 readers). Used by converters, the editor and bilingual export. */
class EpubBuilder(var meta: EpubMeta) {
    data class Chapter(val title: String, val href: String, val xhtml: String, val level: Int)
    data class Resource(val href: String, val mediaType: String, val bytes: ByteArray)

    val chapters = mutableListOf<Chapter>()
    val resources = mutableListOf<Resource>()
    var css: String = DEFAULT_CSS
    var coverHref: String? = null
    var rtl: Boolean = false

    /** Adds a chapter whose body is an HTML fragment. Returns its href relative to the OPF. */
    fun addChapter(title: String, bodyHtml: String, level: Int = 0, alreadyXhtml: Boolean = false): String {
        val href = "text/ch%04d.xhtml".format(chapters.size + 1)
        val body = if (alreadyXhtml) bodyHtml else Xhtml.fromFragment(bodyHtml)
        chapters += Chapter(title, href, Xhtml.page(title, body, meta.language), level)
        return href
    }

    fun addRawChapter(title: String, href: String, xhtml: String, level: Int = 0) {
        chapters += Chapter(title, href, xhtml, level)
    }

    fun addResource(name: String, mediaType: String, bytes: ByteArray): String {
        val folder = if (mediaType.startsWith("image/")) "images" else if (mediaType.contains("font")) "fonts" else "misc"
        var href = "$folder/" + name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (resources.any { it.href == href }) href = "$folder/${resources.size}_" + href.substringAfterLast('/')
        resources += Resource(href, mediaType, bytes)
        return href
    }

    fun write(out: OutputStream) {
        val id = meta.identifier ?: "urn:uuid:${UUID.randomUUID()}"
        ZipOutputStream(out).use { zip ->
            writeMimetype(zip)
            zip.text("META-INF/container.xml", CONTAINER_XML)
            zip.text("OEBPS/styles.css", css)
            chapters.forEach { zip.text("OEBPS/${it.href}", it.xhtml) }
            resources.forEach { zip.bytes("OEBPS/${it.href}", it.bytes) }
            zip.text("OEBPS/nav.xhtml", nav())
            zip.text("OEBPS/toc.ncx", ncx(id))
            zip.text("OEBPS/content.opf", opf(id))
        }
    }

    fun writeTo(file: File) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().buffered().use { write(it) }
        if (file.exists()) file.delete()
        tmp.renameTo(file)
    }

    private fun opf(id: String): String = buildString {
        val e = Xhtml::escape
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\">\n")
        append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:opf=\"http://www.idpf.org/2007/opf\">\n")
        append("<dc:identifier id=\"bookid\">${e(id)}</dc:identifier>\n")
        append("<dc:title>${e(meta.title.ifBlank { "Untitled" })}</dc:title>\n")
        meta.authors.forEach { append("<dc:creator>${e(it)}</dc:creator>\n") }
        append("<dc:language>${e(meta.language ?: "und")}</dc:language>\n")
        meta.description?.let { append("<dc:description>${e(it)}</dc:description>\n") }
        meta.publisher?.let { append("<dc:publisher>${e(it)}</dc:publisher>\n") }
        meta.date?.let { append("<dc:date>${e(it)}</dc:date>\n") }
        meta.subjects.forEach { append("<dc:subject>${e(it)}</dc:subject>\n") }
        meta.series?.let {
            append("<meta name=\"calibre:series\" content=\"${e(it)}\"/>\n")
            append("<meta property=\"belongs-to-collection\" id=\"series\">${e(it)}</meta>\n")
            meta.seriesIndex?.let { i ->
                append("<meta name=\"calibre:series_index\" content=\"$i\"/>\n")
                append("<meta refines=\"#series\" property=\"group-position\">$i</meta>\n")
            }
        }
        coverHref?.let { append("<meta name=\"cover\" content=\"${itemId(it)}\"/>\n") }
        append("<meta property=\"dcterms:modified\">${java.time.Instant.now().toString().substringBefore('.') + "Z"}</meta>\n")
        append("</metadata>\n<manifest>\n")
        append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
        append("<item id=\"css\" href=\"styles.css\" media-type=\"text/css\"/>\n")
        chapters.forEach { append("<item id=\"${itemId(it.href)}\" href=\"${e(it.href)}\" media-type=\"application/xhtml+xml\"/>\n") }
        resources.forEach {
            val props = if (it.href == coverHref) " properties=\"cover-image\"" else ""
            append("<item id=\"${itemId(it.href)}\" href=\"${e(it.href)}\" media-type=\"${it.mediaType}\"$props/>\n")
        }
        append("</manifest>\n<spine toc=\"ncx\"${if (rtl) " page-progression-direction=\"rtl\"" else ""}>\n")
        chapters.forEach { append("<itemref idref=\"${itemId(it.href)}\"/>\n") }
        append("</spine>\n</package>\n")
    }

    private fun nav(): String {
        val body = buildString {
            append("<nav epub:type=\"toc\" id=\"toc\"><h1>${Xhtml.escape(meta.title)}</h1><ol>")
            var depth = 0
            chapters.forEachIndexed { i, c ->
                val level = c.level.coerceIn(0, depth + 1)
                when {
                    i == 0 -> Unit
                    level > depth -> append("<ol>")
                    else -> {
                        append("</li>")
                        repeat(depth - level) { append("</ol></li>") }
                    }
                }
                depth = level
                append("<li><a href=\"${Xhtml.escape(c.href)}\">${Xhtml.escape(c.title)}</a>")
            }
            if (chapters.isNotEmpty()) {
                append("</li>")
                repeat(depth) { append("</ol></li>") }
            }
            append("</ol></nav>")
        }
        return Xhtml.page(meta.title, body, meta.language, css = "styles.css")
    }

    private fun ncx(id: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n")
        append("<head><meta name=\"dtb:uid\" content=\"${Xhtml.escape(id)}\"/></head>\n")
        append("<docTitle><text>${Xhtml.escape(meta.title)}</text></docTitle>\n<navMap>\n")
        chapters.forEachIndexed { i, c ->
            append("<navPoint id=\"np$i\" playOrder=\"${i + 1}\"><navLabel><text>${Xhtml.escape(c.title)}</text></navLabel>")
            append("<content src=\"${Xhtml.escape(c.href)}\"/></navPoint>\n")
        }
        append("</navMap>\n</ncx>\n")
    }

    companion object {
        fun itemId(href: String) = "i_" + href.replace(Regex("[^A-Za-z0-9]"), "_")

        const val CONTAINER_XML = "<?xml version=\"1.0\"?>\n<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
            "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles>\n</container>\n"

        val DEFAULT_CSS = """
            body { margin: 0; padding: 0; line-height: 1.5; }
            h1, h2, h3, h4 { text-align: center; line-height: 1.25; }
            p { margin: 0 0 0.4em 0; text-indent: 1.2em; }
            p.v { text-indent: 0; margin-left: 2em; }
            .poem, .stanza { margin: 0.8em 0; }
            blockquote, .epigraph, .cite { margin: 1em 2em; font-style: italic; }
            .subtitle { text-align: center; font-weight: bold; text-indent: 0; }
            img { max-width: 100%; height: auto; }
            .center { text-align: center; text-indent: 0; }
            aside { font-size: 0.9em; }
            p.yomu-tr { opacity: 0.75; font-style: italic; }
            table { border-collapse: collapse; }
            td, th { border: 1px solid #8888; padding: 0.2em 0.4em; }
        """.trimIndent()

        fun writeMimetype(zip: ZipOutputStream) {
            val bytes = "application/epub+zip".toByteArray()
            val entry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                crc = CRC32().also { it.update(bytes) }.value
            }
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }

        private fun ZipOutputStream.text(name: String, text: String) = bytes(name, text.toByteArray())

        private fun ZipOutputStream.bytes(name: String, bytes: ByteArray) {
            putNextEntry(ZipEntry(name))
            write(bytes)
            closeEntry()
        }

        /** Re-packs an extracted EPUB directory (editor working copy) into [out]; mimetype is stored first. */
        fun zipDirectory(dir: File, out: File) {
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile, out.name + ".tmp")
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                writeMimetype(zip)
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val name = f.relativeTo(dir).path.replace('\\', '/')
                    if (name == "mimetype") return@forEach
                    zip.putNextEntry(ZipEntry(name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            if (out.exists()) out.delete()
            tmp.renameTo(out)
        }
    }
}

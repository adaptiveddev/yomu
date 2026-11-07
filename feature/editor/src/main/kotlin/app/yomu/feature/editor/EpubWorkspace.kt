package app.yomu.feature.editor

import app.yomu.data.book.DirContainer
import app.yomu.data.book.EpubMeta
import app.yomu.data.book.EpubPackage
import app.yomu.data.book.EpubParser
import app.yomu.data.book.EpubPaths
import app.yomu.data.book.Xhtml
import app.yomu.domain.text.SearchReplace
import app.yomu.domain.text.Typography
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.nodes.XmlDeclaration
import org.jsoup.parser.ParseSettings
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import java.io.File
import java.io.StringReader
import java.util.UUID
import javax.xml.parsers.SAXParserFactory

/**
 * Editable EPUB working copy: an extracted EPUB directory. All operations rewrite files inside [root];
 * the caller is responsible for undo snapshots ([History]) and for re-packing ([app.yomu.data.book.EpubBuilder.zipDirectory]).
 * Pure JVM code (Jsoup + java.io) so that it is covered by unit tests.
 */
class EpubWorkspace(val root: File) {
    lateinit var pkg: EpubPackage
        private set
    private val titleCache = HashMap<String, Pair<Long, String?>>()

    init {
        reload()
    }

    fun reload(): EpubPackage {
        pkg = DirContainer(root).use { EpubParser.parse(it) }
        return pkg
    }

    fun file(path: String) = File(root, path)
    val opfPath: String get() = pkg.opfPath
    val spinePaths: List<String> get() = pkg.spine.map { it.path }
    val isEpub3: Boolean get() = runCatching { readXml(opfPath).firstByLocal("package")?.attr("version")?.startsWith("3") == true }.getOrDefault(false)

    fun documents(): List<String> = pkg.manifest.filter { it.isDocument && file(it.path).isFile }.map { it.path }.distinct()
    fun stylesheets(): List<String> = pkg.manifest.filter { it.mediaType == "text/css" && file(it.path).isFile }.map { it.path }
    fun images(): List<ManifestImage> = pkg.manifest.filter { it.isImage }.map {
        ManifestImage(it.path, it.mediaType, file(it.path).length(), it.path == pkg.coverPath)
    }

    /** OPF, nav and NCX (plus the path a new nav document would get). Touched by every structural change. */
    fun structureFiles(): List<String> = listOfNotNull(opfPath, pkg.navPath, pkg.ncxPath, if (pkg.navPath == null) newNavPath() else null).distinct()

    /** Every non-binary file (for rare, wide operations such as validation fixes). */
    fun textFiles(): List<String> = root.walkTopDown().filter { it.isFile && !MediaTypes.isBinary(it.name) }.map { rel(it) }.toList()

    private fun rel(f: File) = f.relativeTo(root).path.replace('\\', '/')

    // ---------------------------------------------------------------- XML helpers

    fun readText(path: String): String = file(path).readText()

    fun writeText(path: String, text: String) {
        file(path).parentFile?.mkdirs()
        file(path).writeText(text)
        titleCache.remove(path)
    }

    fun readXml(path: String): Document = Xhtml.configure(EpubParser.parseXml(file(path).readBytes()))

    fun writeXml(path: String, doc: Document) {
        doc.childNodes().filterIsInstance<XmlDeclaration>().forEach { if (it.hasAttr("encoding")) it.attr("encoding", "utf-8") }
        writeText(path, doc.outerHtml())
    }

    private inline fun editOpf(block: (Document) -> Unit) {
        val doc = readXml(opfPath)
        block(doc)
        writeXml(opfPath, doc)
    }

    private fun Document.manifestEl(): Element = firstByLocal("manifest") ?: firstByLocal("package")!!.appendElement("manifest")
    private fun Document.spineEl(): Element = firstByLocal("spine") ?: firstByLocal("package")!!.appendElement("spine")
    private fun idOf(path: String): String? = pkg.manifest.firstOrNull { it.path == path }?.id

    private fun uniqueId(prefix: String, taken: MutableSet<String> = pkg.manifest.map { it.id }.toMutableSet()): String {
        var n = 1
        while ("$prefix$n" in taken) n++
        return "$prefix$n".also { taken += it }
    }

    private fun uniquePath(dir: String, prefix: String, suffix: String, reserved: Set<String> = emptySet()): String {
        var n = 1
        while (true) {
            val p = (if (dir.isEmpty()) "" else "$dir/") + prefix + n + suffix
            if (!file(p).exists() && p !in reserved) return p
            n++
        }
    }

    private fun opfDir(): String = opfPath.substringBeforeLast('/', "")
    private fun siblingOfOpf(name: String) = if (opfDir().isEmpty()) name else "${opfDir()}/$name"
    private fun newNavPath(): String = if (!file(siblingOfOpf("nav.xhtml")).exists()) siblingOfOpf("nav.xhtml") else uniquePath(opfDir(), "nav", ".xhtml")
    private fun hrefFromOpf(path: String) = EpubPaths.relative(opfPath, path)

    private fun addManifestItem(doc: Document, path: String, mediaType: String, idPrefix: String, properties: String? = null): String {
        val id = uniqueId(idPrefix, (pkg.manifest.map { it.id } + doc.manifestEl().children().map { it.attr("id") }).toMutableSet())
        val item = doc.manifestEl().appendElement("item").attr("id", id).attr("href", hrefFromOpf(path)).attr("media-type", mediaType)
        if (!properties.isNullOrBlank()) item.attr("properties", properties)
        return id
    }

    // ---------------------------------------------------------------- metadata

    val meta: EpubMeta get() = pkg.meta

    fun writeMeta(m: EpubMeta) {
        editOpf { doc ->
            val pack = doc.firstByLocal("package")!!
            val metadata = doc.firstByLocal("metadata") ?: pack.prependElement("metadata")
            if (!metadata.hasAttr("xmlns:dc") && !pack.hasAttr("xmlns:dc")) metadata.attr("xmlns:dc", DC_NS)
            val prefix = metadata.children().firstOrNull { it.local() == "identifier" }?.tagName()?.substringBefore(':', "dc")?.ifEmpty { "dc" } ?: "dc"
            val dcNames = setOf("title", "creator", "language", "description", "publisher", "subject", "date")
            val removedIds = mutableSetOf<String>()
            metadata.children().toList().forEach { el ->
                val local = el.local()
                val isDc = el.tagName().contains(':') && local in dcNames
                val isSeries = local == "meta" && (el.attr("name") in setOf("calibre:series", "calibre:series_index") || el.attr("property") == "belongs-to-collection")
                if (isDc || isSeries) {
                    if (el.id().isNotEmpty()) removedIds += el.id()
                    el.remove()
                }
            }
            metadata.children().filter { it.local() == "meta" && it.attr("refines").removePrefix("#") in removedIds }.forEach { it.remove() }
            fun add(name: String, value: String?) {
                value?.trim()?.takeIf { it.isNotEmpty() }?.let { metadata.appendElement("$prefix:$name").text(it) }
            }
            add("title", m.title.ifBlank { "Untitled" })
            m.authors.forEach { add("creator", it) }
            add("language", m.language?.ifBlank { null } ?: "und")
            add("description", m.description)
            add("publisher", m.publisher)
            add("date", m.date)
            m.subjects.forEach { add("subject", it) }
            val series = m.series?.trim()?.takeIf { it.isNotEmpty() }
            if (series != null) {
                metadata.appendElement("meta").attr("name", "calibre:series").attr("content", series)
                m.seriesIndex?.let { metadata.appendElement("meta").attr("name", "calibre:series_index").attr("content", formatIndex(it)) }
                if (pack.attr("version").startsWith("3")) {
                    metadata.appendElement("meta").attr("property", "belongs-to-collection").attr("id", "yomu-series").text(series)
                    metadata.appendElement("meta").attr("refines", "#yomu-series").attr("property", "collection-type").text("series")
                    m.seriesIndex?.let { metadata.appendElement("meta").attr("refines", "#yomu-series").attr("property", "group-position").text(formatIndex(it)) }
                }
            }
            touchModified(pack, metadata)
        }
        reload()
    }

    private fun formatIndex(f: Float): String = if (f % 1f == 0f) f.toInt().toString() else f.toString()

    private fun touchModified(pack: Element, metadata: Element) {
        if (!pack.attr("version").startsWith("3")) return
        val now = java.time.Instant.now().toString().substringBefore('.').removeSuffix("Z") + "Z"
        val el = metadata.children().firstOrNull { it.local() == "meta" && it.attr("property") == "dcterms:modified" }
            ?: metadata.appendElement("meta").attr("property", "dcterms:modified")
        el.text(now)
    }

    // ---------------------------------------------------------------- cover

    /** Where a new cover image will be written (existing cover file or a new one next to the OPF). */
    fun coverTarget(mime: String): String = pkg.coverPath?.takeIf { pkg.item(it)?.isImage == true }
        ?: uniquePath(siblingOfOpf("images").trimStart('/'), "cover", "." + MediaTypes.extensionOf(mime))

    fun setCover(bytes: ByteArray, mime: String, target: String = coverTarget(mime)) {
        file(target).parentFile?.mkdirs()
        file(target).writeBytes(bytes)
        editOpf { doc ->
            val epub3 = doc.firstByLocal("package")!!.attr("version").startsWith("3")
            val manifest = doc.manifestEl()
            // Only one cover-image item is allowed.
            manifest.children().forEach { item ->
                val props = item.attr("properties").split(' ').filter { it.isNotBlank() }
                if ("cover-image" in props) item.attr("properties", (props - "cover-image").joinToString(" ")).also { if (it.attr("properties").isBlank()) it.removeAttr("properties") }
            }
            val item = manifest.children().firstOrNull { it.local() == "item" && EpubPaths.resolve(opfPath, it.attr("href")) == target }
            val id: String
            if (item == null) {
                id = addManifestItem(doc, target, mime, "cover-image", if (epub3) "cover-image" else null)
            } else {
                item.attr("media-type", mime)
                if (epub3) item.attr("properties", (item.attr("properties").split(' ').filter { it.isNotBlank() } + "cover-image").distinct().joinToString(" "))
                id = item.attr("id")
            }
            val metadata = doc.firstByLocal("metadata")!!
            metadata.children().filter { it.local() == "meta" && it.attr("name") == "cover" }.forEach { it.remove() }
            metadata.appendElement("meta").attr("name", "cover").attr("content", id)
        }
        reload()
    }

    // ---------------------------------------------------------------- chapters and TOC

    fun toc(): List<TocEntry> = pkg.toc.mapNotNull { t -> pkg.spine.getOrNull(t.chapter)?.let { TocEntry(t.title, it.path, t.anchor, t.level) } }

    fun chapters(): List<EditorChapter> {
        val toc = toc()
        return pkg.spine.mapIndexed { i, item ->
            val title = toc.firstOrNull { it.path == item.path && it.fragment == null }?.title
                ?: toc.firstOrNull { it.path == item.path }?.title
                ?: docTitle(item.path)
                ?: item.path.substringAfterLast('/')
            EditorChapter(i, item.path, title, file(item.path).length())
        }
    }

    private fun docTitle(path: String): String? {
        val f = file(path)
        titleCache[path]?.let { (stamp, title) -> if (stamp == f.lastModified()) return title }
        val title = runCatching {
            val text = f.readText()
            val heading = HEADING.find(text)?.groupValues?.get(1)
            val raw = heading ?: TITLE.find(text)?.groupValues?.get(1)
            raw?.let { Jsoup.parse(it).text().trim().take(120) }?.takeIf { it.isNotBlank() }
        }.getOrNull()
        titleCache[path] = f.lastModified() to title
        return title
    }

    fun writeToc(entries: List<TocEntry>) {
        val navPath = pkg.navPath
        val ncxPath = pkg.ncxPath
        if (navPath != null && file(navPath).isFile) writeNav(navPath, entries)
        if (ncxPath != null && file(ncxPath).isFile) writeNcx(ncxPath, entries)
        if ((navPath == null || !file(navPath).isFile) && (isEpub3 || ncxPath == null)) createNav(entries)
        reload()
    }

    private fun hrefOf(fromPath: String, e: TocEntry): String {
        val base = if (e.path == fromPath) "" else EpubPaths.relative(fromPath, e.path)
        return base + (e.fragment?.let { "#$it" } ?: "")
    }

    private fun buildOl(parent: Element, entries: List<TocEntry>, fromPath: String) {
        val rootOl = parent.appendElement("ol")
        val stack = ArrayDeque<Pair<Int, Element>>()
        stack.addLast(0 to rootOl)
        var lastLi: Element? = null
        entries.forEach { e ->
            val current = stack.last().first
            if (e.level > current && lastLi != null) {
                stack.addLast(current + 1 to lastLi.appendElement("ol"))
            } else {
                while (stack.size > 1 && stack.last().first > e.level) stack.removeLast()
            }
            val li = stack.last().second.appendElement("li")
            li.appendElement("a").attr("href", hrefOf(fromPath, e)).text(e.title)
            lastLi = li
        }
    }

    private fun writeNav(navPath: String, entries: List<TocEntry>) {
        val doc = readXml(navPath)
        val navs = doc.allByLocal("nav")
        val nav = navs.firstOrNull { "toc" in it.attr("epub:type").split(' ') } ?: navs.firstOrNull()
            ?: (doc.firstByLocal("body") ?: doc.firstByLocal("html")!!).appendElement("nav").attr("epub:type", "toc").attr("id", "toc")
        nav.childrenByLocal("ol").forEach { it.remove() }
        buildOl(nav, entries, navPath)
        writeXml(navPath, doc)
    }

    private fun writeNcx(ncxPath: String, entries: List<TocEntry>) {
        val doc = readXml(ncxPath)
        val ncx = doc.firstByLocal("ncx") ?: return
        val navMap = doc.firstByLocal("navmap") ?: ncx.appendElement("navMap")
        navMap.childrenByLocal("navpoint").forEach { it.remove() }
        val stack = ArrayDeque<Pair<Int, Element>>()
        stack.addLast(0 to navMap)
        var last: Element? = null
        entries.forEachIndexed { i, e ->
            val current = stack.last().first
            if (e.level > current && last != null) {
                stack.addLast(current + 1 to last)
            } else {
                while (stack.size > 1 && stack.last().first > e.level) stack.removeLast()
            }
            val np = stack.last().second.appendElement("navPoint").attr("id", "np-${i + 1}").attr("playOrder", "${i + 1}")
            np.appendElement("navLabel").appendElement("text").text(e.title)
            np.appendElement("content").attr("src", hrefOf(ncxPath, e).ifEmpty { e.path.substringAfterLast('/') })
            last = np
        }
        writeXml(ncxPath, doc)
    }

    private fun createNav(entries: List<TocEntry>) {
        val path = newNavPath()
        val doc = Xhtml.configure(Jsoup.parse(page(path, "Contents", ""), "", Parser.xmlParser()))
        val body = doc.firstByLocal("body")!!
        val nav = body.appendElement("nav").attr("epub:type", "toc").attr("id", "toc")
        nav.appendElement("h1").text(meta.title.ifBlank { "Contents" })
        buildOl(nav, entries, path)
        writeXml(path, doc)
        editOpf { opf -> addManifestItem(opf, path, "application/xhtml+xml", "nav", "nav") }
        reload()
    }

    /** Minimal XHTML page linking all stylesheets of the book. */
    fun page(path: String, title: String, body: String): String = buildString {
        val lang = meta.language?.takeIf { it.isNotBlank() && it != "und" }
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n")
        append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"")
        if (lang != null) append(" lang=\"${Xhtml.escape(lang)}\" xml:lang=\"${Xhtml.escape(lang)}\"")
        append(">\n<head>\n<title>").append(Xhtml.escape(title)).append("</title>\n")
        stylesheets().forEach { append("<link rel=\"stylesheet\" type=\"text/css\" href=\"${Xhtml.escape(EpubPaths.relative(path, it))}\"/>\n") }
        append("</head>\n<body>\n").append(body).append("\n</body>\n</html>\n")
    }

    private fun reorderSpineIds(doc: Document, ids: List<String>) {
        val spine = doc.spineEl()
        val refs = spine.childrenByLocal("itemref")
        refs.forEach { it.remove() }
        val byId = LinkedHashMap<String, Element>()
        val rest = mutableListOf<Element>()
        refs.forEach { r ->
            if (byId.containsKey(r.attr("idref"))) rest.add(r) else byId[r.attr("idref")] = r
        }
        ids.forEach { id ->
            val ref = byId.remove(id)
            if (ref != null) spine.appendChild(ref) else spine.appendElement("itemref").attr("idref", id)
        }
        byId.values.forEach { spine.appendChild(it) }
        rest.forEach { spine.appendChild(it) }
    }

    /** Sorts the TOC so that it follows the reading order (stable inside one document). */
    private fun sortedToc(entries: List<TocEntry>): List<TocEntry> {
        val order = spinePaths.withIndex().associate { it.value to it.index }
        return entries.filter { it.path in order }.sortedBy { order[it.path] ?: Int.MAX_VALUE }
    }

    fun moveChapter(from: Int, to: Int) {
        val paths = spinePaths.toMutableList()
        if (from !in paths.indices || to !in paths.indices || from == to) return
        paths.add(to, paths.removeAt(from))
        reorderChapters(paths)
    }

    fun reorderChapters(paths: List<String>) {
        val toc = toc()
        editOpf { doc -> reorderSpineIds(doc, paths.mapNotNull { idOf(it) }) }
        reload()
        writeToc(sortedToc(toc))
    }

    /** Adds an empty chapter after spine index [after] (-1 = at the beginning). Returns its path. */
    fun addChapter(after: Int, title: String): String {
        val base = spinePaths.getOrNull(after.coerceAtLeast(0)) ?: spinePaths.first()
        val dir = base.substringBeforeLast('/', "")
        val path = uniquePath(dir, "chapter_", ".xhtml")
        writeText(path, page(path, title, "<h2>${Xhtml.escape(title)}</h2>\n<p></p>"))
        val toc = toc().toMutableList()
        editOpf { doc ->
            val id = addManifestItem(doc, path, "application/xhtml+xml", "ch")
            val ids = pkg.spine.map { it.id }.toMutableList()
            ids.add((after + 1).coerceIn(0, ids.size), id)
            reorderSpineIds(doc, ids)
        }
        reload()
        val newIndex = spinePaths.indexOf(path)
        val order = spinePaths.withIndex().associate { it.value to it.index }
        val insertAt = toc.indexOfLast { (order[it.path] ?: Int.MAX_VALUE) < newIndex } + 1
        val level = toc.firstOrNull { it.path == base }?.level ?: 0
        toc.add(insertAt, TocEntry(title, path, null, if (after < 0) 0 else level))
        writeToc(toc)
        return path
    }

    fun deleteChapter(path: String) {
        check(spinePaths.size > 1) { "A book needs at least one chapter" }
        val toc = toc().filterNot { it.path == path }
        val id = idOf(path)
        editOpf { doc ->
            doc.spineEl().childrenByLocal("itemref").filter { it.attr("idref") == id }.forEach { it.remove() }
            doc.manifestEl().childrenByLocal("item").filter { it.attr("id") == id }.forEach { it.remove() }
            doc.firstByLocal("guide")?.children()?.filter { EpubPaths.resolve(opfPath, it.attr("href")) == path }?.forEach { it.remove() }
        }
        file(path).delete()
        reload()
        writeToc(toc)
    }

    fun renameChapter(path: String, title: String) {
        val toc = toc().toMutableList()
        val idx = toc.indexOfFirst { it.path == path && it.fragment == null }.takeIf { it >= 0 } ?: toc.indexOfFirst { it.path == path }
        if (idx >= 0) {
            toc[idx] = toc[idx].copy(title = title)
        } else {
            toc += TocEntry(title, path)
        }
        runCatching {
            val d = readXml(path)
            d.firstByLocal("title")?.text(title)
            writeXml(path, d)
        }
        writeToc(sortedToc(toc))
    }

    /** Builds a TOC from h1–h3 headings (ids are added where missing). */
    fun tocFromHeadings(): List<TocEntry> {
        val out = mutableListOf<TocEntry>()
        var counter = 0
        spinePaths.forEach { path ->
            val doc = runCatching { readXml(path) }.getOrNull() ?: return@forEach
            val body = doc.firstByLocal("body") ?: return@forEach
            val headings = body.getAllElements().filter { it.local() in setOf("h1", "h2", "h3") && it.text().isNotBlank() }
            if (headings.isEmpty()) {
                out += TocEntry(docTitle(path) ?: path.substringAfterLast('/'), path)
                return@forEach
            }
            var changed = false
            headings.forEachIndexed { i, h ->
                val level = h.local().last().digitToInt() - 1
                val fragment = if (i == 0) {
                    null
                } else {
                    h.id().ifEmpty {
                        var id: String
                        do {
                            id = "toc-${++counter}"
                        } while (doc.getElementById(id) != null)
                        h.attr("id", id)
                        changed = true
                        id
                    }
                }
                out += TocEntry(h.text().trim().take(200), path, fragment, level)
            }
            if (changed) writeXml(path, doc)
        }
        // Normalise levels so that the first entry starts at 0 and nesting grows by one at most.
        val min = out.minOfOrNull { it.level } ?: 0
        var prev = -1
        return out.map { e ->
            val lvl = (e.level - min).coerceAtMost(prev + 1).coerceAtLeast(0)
            prev = lvl
            e.copy(level = lvl)
        }
    }

    fun tocFromChapters(): List<TocEntry> = chapters().map { TocEntry(it.title, it.path) }

    // ---------------------------------------------------------------- split / merge

    private fun contentContainer(body: Element): Element {
        var c = body
        while (true) {
            val kids = c.children()
            val hasText = c.textNodes().any { it.text().isNotBlank() }
            if (kids.size == 1 && !hasText && kids[0].local() in setOf("div", "section", "article", "main")) c = kids[0] else break
        }
        return c
    }

    private fun routeOf(from: Element, to: Element): List<Int> {
        val route = mutableListOf<Int>()
        var e = to
        while (e !== from) {
            route.add(0, e.elementSiblingIndex())
            e = e.parent() ?: break
        }
        return route
    }

    private fun follow(from: Element, route: List<Int>): Element = route.fold(from) { e, i -> e.child(i) }

    fun blocks(path: String): List<BlockInfo> {
        val body = readXml(path).firstByLocal("body") ?: return emptyList()
        return contentContainer(body).children().mapIndexed { i, el ->
            val text = el.text().trim()
            val preview = text.ifEmpty { if (el.allByLocal("img").isNotEmpty() || el.allByLocal("image").isNotEmpty()) "🖼" else "<${el.local()}>" }
            BlockInfo(i, el.local(), preview.take(100), el.local().matches(Regex("h[1-6]")) || el.allByLocal("h1").isNotEmpty() || el.allByLocal("h2").isNotEmpty())
        }
    }

    /** Splits [path] before each block index in [at]. Returns the paths of the new documents. */
    fun splitChapter(path: String, at: List<Int>): List<String> {
        val doc = readXml(path)
        val body = doc.firstByLocal("body") ?: return emptyList()
        val cont = contentContainer(body)
        val route = routeOf(body, cont)
        val kidsCount = cont.children().size
        val cuts = at.filter { it in 1 until kidsCount }.distinct().sorted()
        if (cuts.isEmpty()) return emptyList()
        val segments = (listOf(0) + cuts).zip(cuts + kidsCount)
        val dir = path.substringBeforeLast('/', "")
        val baseName = path.substringAfterLast('/').substringBeforeLast('.')
        val reserved = mutableSetOf<String>()
        val newPaths = segments.drop(1).map { uniquePath(dir, "${baseName}_", ".xhtml", reserved).also { reserved += it } }
        val moved = HashMap<String, String>() // element id -> new document
        val titles = mutableListOf<String>()
        val baseTitle = chapters().firstOrNull { it.path == path }?.title ?: baseName
        val clones = segments.drop(1).mapIndexed { si, (s, e) ->
            val clone = doc.clone()
            val cCont = follow(clone.firstByLocal("body")!!, route)
            cCont.children().toList().forEachIndexed { i, el -> if (i < s || i >= e) el.remove() }
            cCont.getAllElements().forEach { el -> el.id().takeIf { it.isNotEmpty() }?.let { moved[it] = newPaths[si] } }
            val heading = cCont.getAllElements().firstOrNull { it.local().matches(Regex("h[1-6]")) && it.text().isNotBlank() }?.text()?.trim()
            val title = heading ?: "$baseTitle (${si + 2})"
            clone.firstByLocal("title")?.text(title)
            titles += title
            clone
        }
        cont.children().toList().forEachIndexed { i, el -> if (i >= cuts.first()) el.remove() }
        val mapper: (String, String?) -> Pair<String, String?>? = { p, frag -> if (p == path && frag != null && frag in moved) moved[frag]!! to frag else null }
        rewriteLinks(path, doc, path, mapper)
        writeXml(path, doc)
        clones.forEachIndexed { i, clone ->
            rewriteLinks(newPaths[i], clone, path, mapper)
            writeXml(newPaths[i], clone)
        }
        documents().filter { it != path && it !in newPaths }.forEach { other ->
            val d = runCatching { readXml(other) }.getOrNull() ?: return@forEach
            if (rewriteLinks(other, d, other, mapper)) writeXml(other, d)
        }
        // OPF: new items right after the original.
        val toc = toc().map { e -> mapper(e.path, e.fragment)?.let { (p, f) -> e.copy(path = p, fragment = f) } ?: e }.toMutableList()
        editOpf { opf ->
            val ids = pkg.spine.map { it.id }.toMutableList()
            val pos = ids.indexOf(idOf(path)) + 1
            val newIds = newPaths.map { addManifestItem(opf, it, "application/xhtml+xml", "ch") }
            ids.addAll(pos, newIds)
            reorderSpineIds(opf, ids)
        }
        reload()
        val level = toc.firstOrNull { it.path == path }?.level ?: 0
        newPaths.forEachIndexed { i, p -> if (toc.none { it.path == p && it.fragment == null }) toc += TocEntry(titles[i], p, null, level) }
        // Entries of a document: the whole-document entry first, then anchors in their old order.
        val order = spinePaths.withIndex().associate { it.value to it.index }
        writeToc(toc.withIndex().sortedWith(compareBy({ order[it.value.path] ?: Int.MAX_VALUE }, { if (it.value.fragment == null) -1 else it.index })).map { it.value })
        return newPaths
    }

    fun mergeWithNext(path: String) {
        val i = spinePaths.indexOf(path)
        val next = spinePaths.getOrNull(i + 1) ?: return
        val doc = readXml(path)
        val nextDoc = readXml(next)
        val body = doc.firstByLocal("body") ?: return
        val nBody = nextDoc.firstByLocal("body") ?: return
        var marker = "yomu-merge"
        var n = 1
        while (doc.getElementById(marker) != null || nextDoc.getElementById(marker) != null) marker = "yomu-merge-${n++}"
        val mapper: (String, String?) -> Pair<String, String?>? = { p, frag -> if (p == next) path to (frag ?: marker) else null }
        // Make references inside the moved content relative to the target document.
        relocate(nextDoc, next, path)
        rewriteLinks(path, nextDoc, path, mapper)
        body.appendElement("div").attr("id", marker)
        nBody.childNodes().toList().forEach { body.appendChild(it) }
        // Keep stylesheets of the merged document.
        val head = doc.firstByLocal("head")
        val have = head?.childrenByLocal("link")?.map { EpubPaths.resolve(path, it.attr("href")) }.orEmpty().toMutableSet()
        nextDoc.firstByLocal("head")?.childrenByLocal("link")?.forEach { link ->
            val target = EpubPaths.resolve(path, link.attr("href"))
            if (target !in have && head != null) {
                head.appendChild(link.clone())
                have += target
            }
        }
        rewriteLinks(path, doc, path, mapper)
        writeXml(path, doc)
        documents().filter { it != path && it != next }.forEach { other ->
            val d = runCatching { readXml(other) }.getOrNull() ?: return@forEach
            if (rewriteLinks(other, d, other, mapper)) writeXml(other, d)
        }
        val toc = toc().map { e -> mapper(e.path, e.fragment)?.let { (p, f) -> e.copy(path = p, fragment = f) } ?: e }
        val id = idOf(next)
        editOpf { opf ->
            opf.spineEl().childrenByLocal("itemref").filter { it.attr("idref") == id }.forEach { it.remove() }
            opf.manifestEl().childrenByLocal("item").filter { it.attr("id") == id }.forEach { it.remove() }
        }
        file(next).delete()
        reload()
        writeToc(toc)
    }

    /** Rewrites relative src/href attributes of [doc] (stored at [from]) so they stay valid when the content moves to [to]. */
    private fun relocate(doc: Document, from: String, to: String) {
        if (from.substringBeforeLast('/', "") == to.substringBeforeLast('/', "")) return
        doc.getAllElements().forEach { el ->
            for (attr in LINK_ATTRS) {
                val v = el.attr(attr)
                if (v.isEmpty() || v.startsWith("#") || isExternal(v)) continue
                val target = EpubPaths.resolve(from, v)
                val frag = v.substringAfter('#', "")
                el.attr(attr, EpubPaths.relative(to, target) + if (frag.isNotEmpty()) "#$frag" else "")
            }
        }
    }

    /**
     * Rewrites internal hyperlinks of [doc] (stored at [docPath]); relative references are resolved against [base].
     * [map] returns the new (path, fragment) of a target or null to keep it. Returns true when something changed.
     */
    private fun rewriteLinks(docPath: String, doc: Document, base: String, map: (String, String?) -> Pair<String, String?>?): Boolean {
        var changed = false
        doc.getAllElements().forEach { el ->
            val href = el.attr("href")
            if (href.isEmpty() || isExternal(href) || el.local() == "link") return@forEach
            val target = EpubPaths.resolve(if (href.startsWith("#")) base else docPath, href)
            val frag = href.substringAfter('#', "").ifEmpty { null }
            val (p, f) = map(target, frag) ?: return@forEach
            val newHref = (if (p == docPath) "" else EpubPaths.relative(docPath, p)) + (f?.let { "#$it" } ?: "")
            el.attr("href", newHref.ifEmpty { "#" })
            changed = true
        }
        return changed
    }

    private fun isExternal(v: String) = v.contains("://") || v.startsWith("mailto:") || v.startsWith("data:") || v.startsWith("tel:") || v.startsWith("javascript:")

    // ---------------------------------------------------------------- editing content

    /** Replaces the content of <body> with an HTML fragment (from the visual editor). */
    fun replaceBody(path: String, html: String) {
        val doc = readXml(path)
        val body = doc.firstByLocal("body") ?: throw IllegalStateException("No body in $path")
        val fragment = Jsoup.parseBodyFragment(html, "")
        Xhtml.configure(fragment)
        fragment.body().allElements.forEach { it.removeAttr("contenteditable") }
        body.empty()
        body.append(fragment.body().html())
        writeXml(path, doc)
    }

    fun bodyHtml(path: String): String = readXml(path).firstByLocal("body")?.html().orEmpty()

    // ---------------------------------------------------------------- search & replace

    private fun textNodes(doc: Document): List<TextNode> {
        val body = doc.firstByLocal("body") ?: return emptyList()
        val out = mutableListOf<TextNode>()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is TextNode && node.parent().let { it is Element && it.local() !in SKIP_TEXT }) out += node
            }
        }, body)
        return out
    }

    fun search(q: SearchQuery, scope: List<String>): List<EditorSearchHit> {
        if (q.text.isEmpty()) return emptyList()
        val pattern = SearchReplace.pattern(q.text, q.regex, q.caseSensitive, q.wholeWord)
        return scope.mapNotNull { path ->
            var count = 0
            val previews = mutableListOf<String>()
            if (q.inMarkup) {
                val matches = SearchReplace.find(readText(path), pattern, 30, 1000)
                count = matches.size
                matches.take(PREVIEWS).forEach { previews += it.preview }
            } else {
                val doc = runCatching { readXml(path) }.getOrNull() ?: return@mapNotNull null
                textNodes(doc).forEach { node ->
                    val m = SearchReplace.find(node.wholeText, pattern, 30, 1000)
                    count += m.size
                    if (previews.size < PREVIEWS) m.take(PREVIEWS - previews.size).forEach { previews += it.preview }
                }
            }
            if (count > 0) EditorSearchHit(path, count, previews) else null
        }
    }

    /** Replaces all matches in [scope]; returns the number of replacements. */
    fun replaceAll(q: SearchQuery, replacement: String, scope: List<String>): Int {
        if (q.text.isEmpty()) return 0
        val pattern = SearchReplace.pattern(q.text, q.regex, q.caseSensitive, q.wholeWord)
        var total = 0
        scope.forEach { path ->
            if (q.inMarkup) {
                val (text, n) = SearchReplace.replaceAll(readText(path), pattern, replacement, q.regex)
                if (n > 0) {
                    writeText(path, text)
                    total += n
                }
            } else {
                val doc = runCatching { readXml(path) }.getOrNull() ?: return@forEach
                var n = 0
                textNodes(doc).forEach { node ->
                    val (text, c) = SearchReplace.replaceAll(node.wholeText, pattern, replacement, q.regex)
                    if (c > 0) {
                        node.text(text)
                        n += c
                    }
                }
                if (n > 0) {
                    writeXml(path, doc)
                    total += n
                }
            }
        }
        return total
    }

    // ---------------------------------------------------------------- clean-up

    /** Returns the number of changed documents. */
    fun cleanup(o: CleanupOptions, scope: List<String>): Int {
        val lang = meta.language
        var changedDocs = 0
        scope.forEach { path ->
            val doc = runCatching { readXml(path) }.getOrNull() ?: return@forEach
            var changed = false
            textNodes(doc).forEach { node ->
                if (node.parent().let { it is Element && it.local() in setOf("pre", "code") }) return@forEach
                var t = node.wholeText
                if (o.spaces) t = Typography.collapseSpaces(t)
                if (o.dashes) t = Typography.fixDashes(t)
                if (o.quotes) t = Typography.fixQuotes(t, lang)
                if (t != node.wholeText) {
                    node.text(t)
                    changed = true
                }
            }
            if (o.emptyParagraphs) {
                doc.firstByLocal("body")?.allByLocal("p")?.filter { p ->
                    p.text().replace('\u00A0', ' ').isBlank() && p.getAllElements().none { it.local() in MEDIA }
                }?.forEach {
                    it.remove()
                    changed = true
                }
            }
            if (changed) {
                writeXml(path, doc)
                changedDocs++
            }
        }
        if (o.hyphenation) addHyphenation()
        return changedDocs
    }

    /** Path of the stylesheet that [addHyphenation] would use or create. */
    fun mainStylesheetOrNew(): String = stylesheets().firstOrNull() ?: uniquePath(siblingOfOpf("styles").trimStart('/'), "yomu", ".css")

    private fun addHyphenation() {
        val css = stylesheets().firstOrNull() ?: createStylesheet()
        val text = readText(css)
        if (!text.contains("hyphens: auto")) writeText(css, text + "\nbody, p { -webkit-hyphens: auto; -epub-hyphens: auto; hyphens: auto; }\n")
    }

    private fun createStylesheet(): String {
        val path = mainStylesheetOrNew()
        writeText(path, "/* Yomu */\n")
        editOpf { doc -> addManifestItem(doc, path, "text/css", "css") }
        reload()
        documents().forEach { doc ->
            val d = runCatching { readXml(doc) }.getOrNull() ?: return@forEach
            val head = d.firstByLocal("head") ?: return@forEach
            head.appendElement("link").attr("rel", "stylesheet").attr("type", "text/css").attr("href", EpubPaths.relative(doc, path))
            writeXml(doc, d)
        }
        return path
    }

    /** Removes embedded fonts / colours / inline styles. Returns the number of changed files. */
    fun cleanStyles(o: StyleCleanup): Int {
        var changed = 0
        fun cleanCss(css: String): String {
            var t = css
            if (o.fonts) t = t.replace(FONT_FACE, "").replace(FONT_FAMILY, "$1")
            if (o.colors) t = t.replace(COLORS, "$1")
            return t
        }
        stylesheets().forEach { path ->
            val old = readText(path)
            val new = cleanCss(old)
            if (new != old) {
                writeText(path, new)
                changed++
            }
        }
        documents().forEach { path ->
            val doc = runCatching { readXml(path) }.getOrNull() ?: return@forEach
            var docChanged = false
            doc.allByLocal("style").forEach { st ->
                val old = st.data().ifEmpty { st.wholeText() }
                val new = cleanCss(old)
                if (new != old) {
                    st.empty()
                    st.appendText(new)
                    docChanged = true
                }
            }
            doc.getAllElements().forEach { el ->
                if (el.hasAttr("style")) {
                    val old = el.attr("style")
                    val new = if (o.inlineStyles) "" else cleanCss(old).trim().trim(';').trim()
                    if (new != old) {
                        if (new.isEmpty()) el.removeAttr("style") else el.attr("style", new)
                        docChanged = true
                    }
                }
                if (o.colors && (el.hasAttr("color") || el.hasAttr("bgcolor"))) {
                    el.removeAttr("color")
                    el.removeAttr("bgcolor")
                    docChanged = true
                }
                if (o.fonts && el.local() == "font") {
                    el.unwrap()
                    docChanged = true
                }
            }
            if (docChanged) {
                writeXml(path, doc)
                changed++
            }
        }
        if (o.fonts) {
            val fonts = pkg.manifest.filter { MediaTypes.isFont(it.path, it.mediaType) }
            if (fonts.isNotEmpty()) {
                val ids = fonts.map { it.id }.toSet()
                editOpf { doc -> doc.manifestEl().childrenByLocal("item").filter { it.attr("id") in ids }.forEach { it.remove() } }
                fonts.forEach { file(it.path).delete() }
                changed += fonts.size
                reload()
            }
        }
        return changed
    }

    fun fontFiles(): List<String> = pkg.manifest.filter { MediaTypes.isFont(it.path, it.mediaType) }.map { it.path }

    // ---------------------------------------------------------------- images

    fun newImagePath(name: String): String {
        val dir = images().firstOrNull()?.path?.substringBeforeLast('/', "") ?: siblingOfOpf("images").trimStart('/')
        val clean = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "image.jpg" }
        val stem = clean.substringBeforeLast('.')
        val ext = clean.substringAfterLast('.', "jpg")
        val first = (if (dir.isEmpty()) "" else "$dir/") + clean
        return if (!file(first).exists()) first else uniquePath(dir, "${stem}_", ".$ext")
    }

    fun addImage(path: String, bytes: ByteArray, mime: String, insertInto: String?) {
        file(path).parentFile?.mkdirs()
        file(path).writeBytes(bytes)
        editOpf { doc -> addManifestItem(doc, path, mime, "img") }
        reload()
        if (insertInto != null) {
            val doc = readXml(insertInto)
            val body = doc.firstByLocal("body") ?: return
            contentContainer(body).appendElement("div").attr("class", "illustration")
                .appendElement("img").attr("src", EpubPaths.relative(insertInto, path)).attr("alt", "")
            writeXml(insertInto, doc)
        }
    }

    fun replaceImage(path: String, bytes: ByteArray, mime: String) {
        file(path).writeBytes(bytes)
        editOpf { doc ->
            doc.manifestEl().childrenByLocal("item").filter { EpubPaths.resolve(opfPath, it.attr("href")) == path }.forEach { it.attr("media-type", mime) }
        }
        reload()
    }

    /** Documents referencing [path] (img/image/link). */
    fun usagesOf(path: String): List<String> = documents().filter { doc ->
        val text = runCatching { readText(doc) }.getOrDefault("")
        text.contains(path.substringAfterLast('/')) && runCatching {
            readXml(doc).getAllElements().any { el -> RES_ATTRS.any { a -> el.attr(a).let { v -> v.isNotEmpty() && !isExternal(v) && EpubPaths.resolve(doc, v) == path } } }
        }.getOrDefault(false)
    }

    fun deleteImage(path: String) {
        usagesOf(path).forEach { docPath ->
            val doc = readXml(docPath)
            doc.getAllElements().filter { el ->
                el.local() in setOf("img", "image") && RES_ATTRS.any { a -> el.attr(a).let { v -> v.isNotEmpty() && EpubPaths.resolve(docPath, v) == path } }
            }.forEach { el ->
                // Drop an <svg> wrapper that only contained this image.
                val parent = el.parent()
                el.remove()
                if (parent != null && parent.local() == "svg" && parent.children().isEmpty()) parent.remove()
            }
            writeXml(docPath, doc)
        }
        editOpf { doc ->
            val items = doc.manifestEl().childrenByLocal("item").filter { EpubPaths.resolve(opfPath, it.attr("href")) == path }
            val ids = items.map { it.attr("id") }.toSet()
            items.forEach { it.remove() }
            doc.firstByLocal("metadata")?.children()?.filter { it.local() == "meta" && it.attr("name") == "cover" && it.attr("content") in ids }?.forEach { it.remove() }
            doc.spineEl().childrenByLocal("itemref").filter { it.attr("idref") in ids }.forEach { it.remove() }
        }
        file(path).delete()
        reload()
    }

    // ---------------------------------------------------------------- validation

    fun validate(): List<Issue> {
        val issues = mutableListOf<Issue>()
        if (!file("META-INF/container.xml").isFile) issues += Issue(IssueCode.NO_CONTAINER)
        val m = meta
        if (m.title.isBlank()) issues += Issue(IssueCode.NO_TITLE)
        if (m.language.isNullOrBlank()) issues += Issue(IssueCode.NO_LANGUAGE)
        if (m.identifier.isNullOrBlank()) issues += Issue(IssueCode.NO_IDENTIFIER)
        pkg.manifest.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { issues += Issue(IssueCode.DUPLICATE_ID, detail = it) }
        pkg.manifest.filter { !file(it.path).isFile }.forEach { issues += Issue(IssueCode.MISSING_FILE, it.path) }
        val listed = pkg.manifest.map { it.path }.toSet()
        root.walkTopDown().filter { it.isFile }.map { rel(it) }.forEach { p ->
            if (p == "mimetype" || p.startsWith("META-INF/") || p == opfPath || p in listed) return@forEach
            issues += Issue(if (isJunk(p)) IssueCode.JUNK_FILE else IssueCode.UNLISTED_FILE, p)
        }
        if (isEpub3 && pkg.navPath == null) issues += Issue(IssueCode.NO_NAV)
        if (pkg.coverPath == null) issues += Issue(IssueCode.NO_COVER)
        // TOC files that point nowhere.
        listOfNotNull(pkg.navPath, pkg.ncxPath).filter { file(it).isFile }.forEach { tocPath ->
            val doc = runCatching { readXml(tocPath) }.getOrNull() ?: return@forEach
            val refs = doc.allByLocal("a").map { it.attr("href") } + doc.allByLocal("content").map { it.attr("src") }
            val broken = refs.filter { it.isNotEmpty() && !isExternal(it) }.map { EpubPaths.resolve(tocPath, it) }.firstOrNull { !file(it).isFile }
            if (broken != null) issues += Issue(IssueCode.TOC_BROKEN, tocPath, broken)
        }
        val idCache = HashMap<String, Set<String>>()
        fun idsOf(path: String): Set<String> = idCache.getOrPut(path) {
            runCatching { readXml(path).getAllElements().mapNotNull { it.id().ifEmpty { null } }.toSet() }.getOrDefault(emptySet())
        }
        documents().forEach { path ->
            val text = runCatching { readText(path) }.getOrNull() ?: return@forEach
            wellFormedError(text)?.let { issues += Issue(IssueCode.NOT_WELL_FORMED, path, it) }
            val doc = runCatching { readXml(path) }.getOrNull() ?: return@forEach
            if (path in spinePaths) {
                val body = doc.firstByLocal("body")
                if (body != null && body.text().isBlank() && body.getAllElements().none { it.local() in MEDIA }) issues += Issue(IssueCode.EMPTY_CHAPTER, path)
            }
            doc.getAllElements().forEach { el ->
                val href = el.attr("href")
                if (href.isNotEmpty() && el.local() == "a" && !isExternal(href)) {
                    val target = EpubPaths.resolve(path, href)
                    val frag = href.substringAfter('#', "")
                    if (!file(target).isFile) {
                        issues += Issue(IssueCode.BROKEN_LINK, path, href)
                    } else if (frag.isNotEmpty() && pkg.item(target)?.isDocument != false && frag !in idsOf(target)) {
                        issues += Issue(IssueCode.BROKEN_ANCHOR, path, href)
                    }
                }
                val res = when (el.local()) {
                    "img", "source", "audio", "video", "script" -> el.attr("src")
                    "image" -> el.attr("xlink:href").ifEmpty { el.attr("href") }
                    "link" -> el.attr("href")
                    else -> ""
                }
                if (res.isNotEmpty() && !isExternal(res) && !file(EpubPaths.resolve(path, res)).isFile) issues += Issue(IssueCode.MISSING_RESOURCE, path, res)
            }
        }
        return issues
    }

    /** Applies automatic fixes for [issues]; returns the number of fixed issues. */
    fun fix(issues: List<Issue>): Int {
        var fixed = 0
        val byCode = issues.filter { it.code.fixable }.groupBy { it.code }
        byCode[IssueCode.NO_CONTAINER]?.let {
            writeText("META-INF/container.xml", "<?xml version=\"1.0\"?>\n<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n<rootfiles><rootfile full-path=\"${Xhtml.escape(opfPath)}\" media-type=\"application/oebps-package+xml\"/></rootfiles>\n</container>\n")
            fixed++
        }
        if (byCode.keys.any { it in setOf(IssueCode.NO_TITLE, IssueCode.NO_LANGUAGE) }) {
            writeMeta(meta.copy(title = meta.title.ifBlank { "Untitled" }, language = meta.language?.ifBlank { null } ?: "und"))
            fixed += (byCode[IssueCode.NO_TITLE]?.size ?: 0) + (byCode[IssueCode.NO_LANGUAGE]?.size ?: 0)
        }
        if (byCode.containsKey(IssueCode.NO_IDENTIFIER)) {
            editOpf { doc ->
                val pack = doc.firstByLocal("package")!!
                val metadata = doc.firstByLocal("metadata") ?: pack.prependElement("metadata")
                if (!metadata.hasAttr("xmlns:dc") && !pack.hasAttr("xmlns:dc")) metadata.attr("xmlns:dc", DC_NS)
                val idAttr = pack.attr("unique-identifier").ifEmpty { "bookid" }
                metadata.children().filter { it.local() == "identifier" && it.text().isBlank() }.forEach { it.remove() }
                metadata.prependElement("dc:identifier").attr("id", idAttr).text("urn:uuid:${UUID.randomUUID()}")
                pack.attr("unique-identifier", idAttr)
            }
            fixed++
        }
        if (byCode.containsKey(IssueCode.DUPLICATE_ID) || byCode.containsKey(IssueCode.MISSING_FILE) || byCode.containsKey(IssueCode.UNLISTED_FILE)) {
            editOpf { doc ->
                val manifest = doc.manifestEl()
                val seen = mutableSetOf<String>()
                manifest.childrenByLocal("item").forEach { item ->
                    val id = item.attr("id")
                    if (!seen.add(id) && byCode.containsKey(IssueCode.DUPLICATE_ID)) {
                        item.attr("id", uniqueId("$id-", seen))
                        fixed++
                    }
                }
                byCode[IssueCode.MISSING_FILE]?.forEach { issue ->
                    val items = manifest.childrenByLocal("item").filter { EpubPaths.resolve(opfPath, it.attr("href")) == issue.path }
                    val ids = items.map { it.attr("id") }.toSet()
                    items.forEach { it.remove() }
                    doc.spineEl().childrenByLocal("itemref").filter { it.attr("idref") in ids }.forEach { it.remove() }
                    fixed++
                }
                val taken = manifest.childrenByLocal("item").map { it.attr("id") }.toMutableSet()
                byCode[IssueCode.UNLISTED_FILE]?.forEach { issue ->
                    val p = issue.path ?: return@forEach
                    val id = uniqueId("res", taken)
                    manifest.appendElement("item").attr("id", id).attr("href", hrefFromOpf(p)).attr("media-type", MediaTypes.of(p))
                    fixed++
                }
            }
            reload()
        }
        byCode[IssueCode.JUNK_FILE]?.forEach { issue ->
            issue.path?.let { file(it).delete() }
            fixed++
        }
        byCode[IssueCode.NOT_WELL_FORMED]?.forEach { issue ->
            val p = issue.path ?: return@forEach
            writeText(p, normalizeXhtml(readText(p)))
            fixed++
        }
        val linkIssues = (byCode[IssueCode.BROKEN_LINK].orEmpty() + byCode[IssueCode.BROKEN_ANCHOR].orEmpty() + byCode[IssueCode.MISSING_RESOURCE].orEmpty())
        linkIssues.groupBy { it.path }.forEach { (p, list) ->
            if (p == null || !file(p).isFile) return@forEach
            val doc = runCatching { readXml(p) }.getOrNull() ?: return@forEach
            val broken = list.filter { it.code == IssueCode.BROKEN_LINK }.mapNotNull { it.detail }.toSet()
            val anchors = list.filter { it.code == IssueCode.BROKEN_ANCHOR }.mapNotNull { it.detail }.toSet()
            val missing = list.filter { it.code == IssueCode.MISSING_RESOURCE }.mapNotNull { it.detail }.toSet()
            doc.getAllElements().toList().forEach { el ->
                val href = el.attr("href")
                when {
                    el.local() == "a" && href in broken -> {
                        el.unwrap()
                        fixed++
                    }
                    el.local() == "a" && href in anchors -> {
                        el.attr("href", href.substringBefore('#').ifEmpty { "#" })
                        if (el.attr("href") == "#") el.unwrap()
                        fixed++
                    }
                    listOf(el.attr("src"), el.attr("xlink:href"), if (el.local() in setOf("link", "image")) href else "").any { it.isNotEmpty() && it in missing } -> {
                        el.remove()
                        fixed++
                    }
                }
            }
            writeXml(p, doc)
        }
        reload()
        if (byCode.containsKey(IssueCode.NO_NAV) || byCode.containsKey(IssueCode.TOC_BROKEN)) {
            writeToc(toc())
            fixed += (byCode[IssueCode.NO_NAV]?.size ?: 0) + (byCode[IssueCode.TOC_BROKEN]?.size ?: 0)
        }
        reload()
        return fixed
    }

    private fun isJunk(p: String): Boolean {
        val name = p.substringAfterLast('/')
        return name.startsWith(".") || name.equals("Thumbs.db", true) || p.startsWith("__MACOSX") || name.endsWith("~")
    }

    // ---------------------------------------------------------------- export helpers

    /** Plain text of the book in reading order (chapters separated by blank lines). */
    fun plainTextChapters(): List<Pair<String, String>> {
        val titles = chapters().associate { it.path to it.title }
        return spinePaths.mapNotNull { path ->
            val doc = runCatching { Jsoup.parse(readText(path)) }.getOrNull() ?: return@mapNotNull null
            val text = blockText(doc.body())
            if (text.isBlank()) null else (titles[path] ?: "") to text
        }
    }

    fun plainText(): String = buildString {
        if (meta.title.isNotBlank()) append(meta.title).append("\n")
        if (meta.authors.isNotEmpty()) append(meta.authors.joinToString(", ")).append("\n")
        append("\n")
        plainTextChapters().forEach { (_, text) -> append(text.trim()).append("\n\n\n") }
    }.trimEnd() + "\n"

    companion object {
        const val DC_NS = "http://purl.org/dc/elements/1.1/"
        private const val PREVIEWS = 3
        private val HEADING = Regex("<h[1-3][^>]*>([\\s\\S]*?)</h[1-3]>", RegexOption.IGNORE_CASE)
        private val TITLE = Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)
        private val SKIP_TEXT = setOf("script", "style", "title")
        private val MEDIA = setOf("img", "image", "svg", "video", "audio", "object", "iframe", "math", "hr")
        private val LINK_ATTRS = listOf("href", "src", "xlink:href", "poster")
        private val RES_ATTRS = listOf("src", "xlink:href", "href")
        private val FONT_FACE = Regex("@font-face\\s*\\{[^}]*\\}", RegexOption.IGNORE_CASE)
        private val FONT_FAMILY = Regex("(^|[;{\\s])font-family\\s*:[^;}]*;?", RegexOption.IGNORE_CASE)
        private val COLORS = Regex("(^|[;{\\s])(color|background-color|background)\\s*:[^;}]*;?", RegexOption.IGNORE_CASE)
        private val XML_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")

        /** Returns a "line:column message" description of the first XML error, or null when [text] is well formed. */
        fun wellFormedError(text: String): String? {
            val cleaned = text.replace(Regex("&([A-Za-z][A-Za-z0-9]*);")) { m -> if (m.groupValues[1] in XML_ENTITIES) m.value else "&#160;" }
            return try {
                val factory = SAXParserFactory.newInstance()
                factory.isNamespaceAware = true
                factory.isValidating = false
                runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
                val reader = factory.newSAXParser().xmlReader
                reader.entityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }
                reader.parse(InputSource(StringReader(cleaned)))
                null
            } catch (e: SAXParseException) {
                "${e.lineNumber}:${e.columnNumber} ${e.message}"
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
        }

        /** Re-serialises a (possibly broken) HTML document as well formed XHTML. */
        fun normalizeXhtml(text: String): String {
            val doc = Jsoup.parse(text, "", Parser.htmlParser().settings(ParseSettings(false, true)))
            Xhtml.configure(doc)
            val html = doc.firstByLocal("html")
            val lang = html?.attr("lang")?.takeIf { it.isNotBlank() }
            return buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n")
                append("<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"")
                if (lang != null) append(" lang=\"${Xhtml.escape(lang)}\" xml:lang=\"${Xhtml.escape(lang)}\"")
                append(">\n")
                append(doc.head().outerHtml()).append("\n")
                append(doc.body().outerHtml()).append("\n</html>\n")
            }
        }

        /** Text of an HTML element with line breaks between blocks. */
        fun blockText(root: Element): String {
            val sb = StringBuilder()
            NodeTraversor.traverse(object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when {
                        node is TextNode -> sb.append(node.text())
                        node is Element && node.local() == "br" -> sb.append('\n')
                        node is Element && node.isBlock && sb.isNotEmpty() && sb.last() != '\n' -> sb.append('\n')
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node is Element && node.isBlock && sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                    if (node is Element && node.local().matches(Regex("h[1-6]"))) sb.append('\n')
                }
            }, root)
            return sb.toString().lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
        }
    }
}

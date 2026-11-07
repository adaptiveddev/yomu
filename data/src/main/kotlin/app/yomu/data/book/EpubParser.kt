package app.yomu.data.book

import app.yomu.core.common.AppError
import app.yomu.domain.model.TocItem
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLDecoder

data class EpubItem(val id: String, val path: String, val mediaType: String, val properties: String = "", val layoutOverride: Boolean? = null) {
    val isImage: Boolean get() = mediaType.startsWith("image/")
    val isDocument: Boolean get() = mediaType.contains("html") || mediaType.contains("xml") && path.endsWith("htm", true)
}

data class EpubMeta(
    val title: String = "",
    val authors: List<String> = emptyList(),
    val language: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    val series: String? = null,
    val seriesIndex: Float? = null,
    val identifier: String? = null,
    val subjects: List<String> = emptyList(),
    val date: String? = null,
)

class EpubPackage(
    val opfPath: String,
    val meta: EpubMeta,
    val manifest: List<EpubItem>,
    val spine: List<EpubItem>,
    val toc: List<TocItem>,
    val coverPath: String?,
    val rtl: Boolean,
    val fixedLayout: Boolean,
    val navPath: String?,
    val ncxPath: String?,
) {
    fun spineIndexOf(path: String): Int = spine.indexOfFirst { it.path == path.substringBefore('#') }
    fun item(path: String): EpubItem? = manifest.firstOrNull { it.path == path }
}

object EpubPaths {
    fun decode(href: String): String = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)

    /** Resolves [href] relative to the file [base] (both are paths inside the container). */
    fun resolve(base: String, href: String): String {
        val clean = decode(href.substringBefore('#'))
        if (clean.isEmpty()) return base
        if (clean.startsWith("/")) return normalize(clean.removePrefix("/"))
        val dir = base.substringBeforeLast('/', "")
        return normalize(if (dir.isEmpty()) clean else "$dir/$clean")
    }

    fun normalize(path: String): String {
        val out = ArrayDeque<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(part)
            }
        }
        return out.joinToString("/")
    }

    /** Relative href from file [from] to file [to]. */
    fun relative(from: String, to: String): String {
        val fromParts = from.split('/').dropLast(1)
        val toParts = to.split('/')
        var common = 0
        while (common < fromParts.size && common < toParts.size - 1 && fromParts[common] == toParts[common]) common++
        return (List(fromParts.size - common) { ".." } + toParts.drop(common)).joinToString("/")
    }
}

internal fun Element.allLocal(local: String): List<Element> = getAllElements().filter { it.tagName().substringAfterLast(':').equals(local, true) }
internal fun Element.firstLocal(local: String): Element? = getAllElements().firstOrNull { it.tagName().substringAfterLast(':').equals(local, true) }
internal fun Element.childrenLocal(local: String): List<Element> = children().filter { it.tagName().substringAfterLast(':').equals(local, true) }

object EpubParser {
    fun parseXml(bytes: ByteArray, baseUri: String = ""): Document = Jsoup.parse(bytes.inputStream(), null, baseUri, Parser.xmlParser())

    fun parse(container: ResourceContainer): EpubPackage {
        // EPUBs may obfuscate fonts without DRM; encrypted content, however, cannot be read by this engine.
        container.read("META-INF/encryption.xml")?.let { encryption ->
            val encrypted = parseXml(encryption).allLocal("EncryptedData")
            val fontObfuscation = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")
            if (encrypted.any { it.firstLocal("EncryptionMethod")?.attr("Algorithm") !in fontObfuscation }) {
                throw AppError.UnsupportedFormat("EPUB (encrypted / DRM)")
            }
        }
        val containerXml = container.read("META-INF/container.xml")
        val opfPath = containerXml?.let { parseXml(it).firstLocal("rootfile")?.attr("full-path") }?.takeIf { it.isNotBlank() }
            ?: container.entries.firstOrNull { it.endsWith(".opf", true) }
            ?: throw AppError.CorruptedFile("No OPF package in EPUB")
        val opf = parseXml(container.read(opfPath) ?: throw AppError.CorruptedFile("Missing $opfPath"))
        val metadata = opf.firstLocal("metadata")
        val manifest = opf.firstLocal("manifest")?.childrenLocal("item").orEmpty().map {
            EpubItem(it.attr("id"), EpubPaths.resolve(opfPath, it.attr("href")), it.attr("media-type"), it.attr("properties"))
        }
        val byId = manifest.associateBy { it.id }
        val spineEl = opf.firstLocal("spine")
        val spine = spineEl?.childrenLocal("itemref").orEmpty()
            .filter { it.attr("linear") != "no" || true }
            .mapNotNull { ref ->
                byId[ref.attr("idref")]?.let { item ->
                    val props = ref.attr("properties").split(Regex("\\s+"))
                    val layout = when {
                        "rendition:layout-pre-paginated" in props -> true
                        "rendition:layout-reflowable" in props -> false
                        else -> null
                    }
                    item.copy(layoutOverride = layout)
                }
            }
            .filter { container.exists(it.path) }
        if (spine.isEmpty()) throw AppError.CorruptedFile("Empty spine")
        val meta = parseMeta(metadata)
        val metaTags = metadata?.allLocal("meta").orEmpty()
        val coverId = metaTags.firstOrNull { it.attr("name") == "cover" }?.attr("content")
        val coverPath = manifest.firstOrNull { "cover-image" in it.properties }?.path
            ?: coverId?.let { byId[it] }?.takeIf { it.isImage }?.path
            ?: manifest.firstOrNull { it.isImage && (it.id.contains("cover", true) || it.path.contains("cover", true)) }?.path
            ?: firstImageOf(container, spine.first())
        val fixed = metaTags.any { it.attr("property") == "rendition:layout" && it.text() == "pre-paginated" }
        val rtl = spineEl?.attr("page-progression-direction") == "rtl"
        val navItem = manifest.firstOrNull { "nav" in it.properties.split(' ') }
        val ncxItem = spineEl?.attr("toc")?.let { byId[it] } ?: manifest.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        val pkg = EpubPackage(opfPath, meta, manifest, spine, emptyList(), coverPath, rtl, fixed, navItem?.path, ncxItem?.path)
        val toc = runCatching { navItem?.let { parseNav(container, it.path, pkg) } }.getOrNull().orEmpty()
            .ifEmpty { runCatching { ncxItem?.let { parseNcx(container, it.path, pkg) } }.getOrNull().orEmpty() }
            .ifEmpty { spine.mapIndexed { i, item -> TocItem(titleOf(container, item) ?: "${i + 1}", i) } }
        return EpubPackage(opfPath, meta, manifest, spine, toc, coverPath, rtl, fixed, navItem?.path, ncxItem?.path)
    }

    private fun parseMeta(metadata: Element?): EpubMeta {
        if (metadata == null) return EpubMeta()
        val metas = metadata.allLocal("meta")
        fun dc(name: String) = metadata.allLocal(name).map { it.text().trim() }.filter { it.isNotEmpty() }
        val collection = metas.firstOrNull { it.attr("property") == "belongs-to-collection" }
        val series = metas.firstOrNull { it.attr("name") == "calibre:series" }?.attr("content")?.takeIf { it.isNotBlank() }
            ?: collection?.text()?.takeIf { it.isNotBlank() }
        val index = metas.firstOrNull { it.attr("name") == "calibre:series_index" }?.attr("content")?.toFloatOrNull()
            ?: collection?.id()?.let { id -> metas.firstOrNull { it.attr("refines") == "#$id" && it.attr("property") == "group-position" } }?.text()?.toFloatOrNull()
        val description = dc("description").firstOrNull()?.let { Jsoup.parse(it).text() }
        return EpubMeta(
            title = dc("title").firstOrNull().orEmpty(),
            authors = dc("creator").distinct(),
            language = dc("language").firstOrNull(),
            description = description,
            publisher = dc("publisher").firstOrNull(),
            series = series,
            seriesIndex = index,
            identifier = dc("identifier").firstOrNull(),
            subjects = dc("subject"),
            date = dc("date").firstOrNull(),
        )
    }

    private fun parseNav(container: ResourceContainer, navPath: String, pkg: EpubPackage): List<TocItem> {
        val doc = Jsoup.parse(container.read(navPath)!!.inputStream(), null, "", Parser.xmlParser())
        val nav = doc.allLocal("nav").firstOrNull { it.attr("epub:type") == "toc" } ?: doc.allLocal("nav").firstOrNull() ?: return emptyList()
        val out = mutableListOf<TocItem>()
        fun walk(ol: Element, level: Int) {
            ol.childrenLocal("li").forEach { li ->
                val a = li.children().firstOrNull { it.tagName().endsWith("a") || it.tagName().endsWith("span") }
                val href = a?.attr("href").orEmpty()
                val title = a?.text()?.trim().orEmpty()
                if (title.isNotEmpty() && href.isNotEmpty()) {
                    val path = EpubPaths.resolve(navPath, href)
                    val idx = pkg.spineIndexOf(path)
                    if (idx >= 0) out += TocItem(title, idx, href.substringAfter('#', "").ifEmpty { null }, level)
                }
                li.childrenLocal("ol").forEach { walk(it, level + 1) }
            }
        }
        nav.childrenLocal("ol").forEach { walk(it, 0) }
        return out
    }

    private fun parseNcx(container: ResourceContainer, ncxPath: String, pkg: EpubPackage): List<TocItem> {
        val doc = parseXml(container.read(ncxPath)!!)
        val out = mutableListOf<TocItem>()
        fun walk(parent: Element, level: Int) {
            parent.childrenLocal("navPoint").forEach { np ->
                val title = np.firstLocal("text")?.text()?.trim().orEmpty()
                val src = np.childrenLocal("content").firstOrNull()?.attr("src").orEmpty()
                val idx = pkg.spineIndexOf(EpubPaths.resolve(ncxPath, src))
                if (title.isNotEmpty() && idx >= 0) out += TocItem(title, idx, src.substringAfter('#', "").ifEmpty { null }, level)
                walk(np, level + 1)
            }
        }
        doc.firstLocal("navMap")?.let { walk(it, 0) }
        return out
    }

    private fun titleOf(container: ResourceContainer, item: EpubItem): String? = runCatching {
        val doc = Jsoup.parse(container.read(item.path)!!.inputStream(), null, "")
        doc.selectFirst("h1, h2, h3")?.text()?.take(80) ?: doc.title().takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun firstImageOf(container: ResourceContainer, item: EpubItem): String? = runCatching {
        val doc = Jsoup.parse(container.read(item.path)!!.inputStream(), null, "")
        val src = doc.selectFirst("img[src]")?.attr("src") ?: doc.getElementsByTag("image").firstOrNull()?.let { it.attr("xlink:href").ifEmpty { it.attr("href") } }
        src?.let { EpubPaths.resolve(item.path, it) }?.takeIf { container.exists(it) }
    }.getOrNull()

    /** All images in reading order (used for image based EPUBs read in the manga engine). */
    fun imagesInSpineOrder(container: ResourceContainer, pkg: EpubPackage): List<String> {
        val out = LinkedHashSet<String>()
        pkg.spine.forEach { item ->
            if (item.isImage) {
                out += item.path
                return@forEach
            }
            runCatching {
                val doc = Jsoup.parse(container.read(item.path)!!.inputStream(), null, "")
                doc.select("img[src], image").forEach { el ->
                    val src = el.attr("src").ifEmpty { el.attr("xlink:href") }.ifEmpty { el.attr("href") }
                    if (src.isNotEmpty() && !src.startsWith("data:")) out += EpubPaths.resolve(item.path, src)
                }
            }
        }
        return out.filter { container.exists(it) }
    }

    /** True when most spine documents consist of a single image (comic EPUB). */
    fun looksLikeComic(container: ResourceContainer, pkg: EpubPackage): Boolean {
        // Fixed pages can contain text, SVG, links and image composites. The
        // image-only manga reader would silently discard their other content.
        if (pkg.fixedLayout || pkg.spine.any { it.layoutOverride == true }) return false
        val sample = pkg.spine.take(12)
        if (sample.isEmpty()) return false
        val imageOnly = sample.count { item ->
            runCatching {
                val doc = Jsoup.parse(container.read(item.path)!!.inputStream(), null, "")
                doc.body().text().trim().length < 20 && doc.select("img, image").size >= 1
            }.getOrDefault(false)
        }
        return imageOnly * 10 >= sample.size * 8
    }
}

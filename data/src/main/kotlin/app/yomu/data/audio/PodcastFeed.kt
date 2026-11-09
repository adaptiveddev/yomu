package app.yomu.data.audio

import app.yomu.domain.model.AudioFiles
import app.yomu.domain.model.AudioOrigin
import app.yomu.domain.model.AudioTrack
import app.yomu.domain.model.AudiobookManifest
import app.yomu.domain.model.ChapterMarks
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * RSS and Atom feeds with audio enclosures (podcasts, LibriVox and Internet Archive book feeds) as an audiobook.
 * Episodes are put in listening order: a feed listing the newest first is reversed.
 */
object PodcastFeed {
    fun looksLikeFeed(head: String): Boolean {
        val start = head.trimStart().take(2048).lowercase()
        return start.startsWith("<?xml") && ("<rss" in start || "<feed" in start) || start.startsWith("<rss") || start.startsWith("<feed")
    }

    fun parse(xml: String, feedUrl: String, origin: AudioOrigin = AudioOrigin.PODCAST): AudiobookManifest? {
        val doc = Jsoup.parse(xml, feedUrl, Parser.xmlParser())
        val channel = doc.selectFirst("channel") ?: doc.selectFirst("feed") ?: return null
        val items = channel.select("> item").ifEmpty { channel.select("> entry") }
        val dated = items.mapNotNull { item ->
            val url = enclosure(item) ?: return@mapNotNull null
            val title = child(item, "title") ?: url.substringAfterLast('/').substringBefore('?')
            val durationMs = child(item, "itunes:duration")?.let(::duration) ?: 0
            val chapters = ChapterMarks.parse(child(item, "description")?.let { Jsoup.parse(it).wholeText() }, durationMs)
            date(child(item, "pubDate") ?: child(item, "published") ?: child(item, "updated")) to AudioTrack(title, url, durationMs, chapters)
        }
        if (dated.isEmpty()) return null
        val first = dated.first().first
        val last = dated.last().first
        val ordered = if (first != null && last != null && first > last) dated.reversed() else dated
        return AudiobookManifest(
            title = child(channel, "title") ?: feedUrl,
            authors = listOfNotNull(child(channel, "itunes:author") ?: channel.selectFirst("> author > name")?.text()?.takeIf { it.isNotBlank() }),
            description = (child(channel, "description") ?: child(channel, "subtitle"))?.let { Jsoup.parse(it).text() },
            language = child(channel, "language")?.substringBefore('-')?.lowercase(),
            coverUrl = channel.selectFirst("> itunes|image")?.attr("href")?.takeIf { it.isNotBlank() }
                ?: channel.selectFirst("> image > url")?.text()?.takeIf { it.isNotBlank() }
                ?: channel.selectFirst("> logo, > icon")?.text()?.takeIf { it.isNotBlank() },
            origin = origin,
            sourceUrl = feedUrl,
            tracks = ordered.map { it.second },
        )
    }

    private fun enclosure(item: Element): String? {
        item.select("> enclosure").firstOrNull { it.attr("type").startsWith("audio") || AudioFiles.isAudio(it.attr("url")) }
            ?.attr("url")?.takeIf { it.isNotBlank() }?.let { return it }
        item.select("> link[rel=enclosure]").firstOrNull { it.attr("type").startsWith("audio") || AudioFiles.isAudio(it.attr("href")) }
            ?.attr("href")?.takeIf { it.isNotBlank() }?.let { return it }
        return item.select("> media|content").firstOrNull { it.attr("type").startsWith("audio") || AudioFiles.isAudio(it.attr("url")) }
            ?.attr("url")?.takeIf { it.isNotBlank() }
    }

    private fun child(e: Element, tag: String): String? =
        e.children().firstOrNull { it.tagName().equals(tag, ignoreCase = true) }?.text()?.trim()?.takeIf { it.isNotEmpty() }

    /** "3906", "65:06" or "1:05:06" to milliseconds. */
    internal fun duration(text: String): Long? = text.trim().toLongOrNull()?.let { it * 1000 } ?: ChapterMarks.parseTime(text.trim())

    private fun date(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return listOf(DateTimeFormatter.RFC_1123_DATE_TIME, DateTimeFormatter.ISO_OFFSET_DATE_TIME).firstNotNullOfOrNull { f ->
            runCatching { ZonedDateTime.parse(text.trim(), f).toInstant().toEpochMilli() }.getOrNull()
        }
    }
}

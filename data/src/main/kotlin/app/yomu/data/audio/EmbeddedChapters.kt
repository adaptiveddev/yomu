package app.yomu.data.audio

import app.yomu.domain.model.AudioChapter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset

/**
 * Chapters stored inside an audio file: MP4/M4B (a QuickTime chapter text track or Nero `chpl`) and MP3 (ID3v2
 * `CHAP` frames). Reads only the metadata, never the audio; chapters come back in time order.
 */
object EmbeddedChapters {
    private const val MAX_ID3 = 32L * 1024 * 1024
    private const val MAX_TEXT_SAMPLE = 4096
    private const val MAX_CHAPTER_TRAK = 4L * 1024 * 1024
    private const val MAX_BOXES = 4096

    fun read(channel: SeekableByteChannel): List<AudioChapter> {
        val head = readAt(channel, 0, 12) ?: return emptyList()
        val chapters = when {
            head.ascii(0, 3) == "ID3" -> id3(channel)
            head.ascii(4, 4) == "ftyp" -> mp4(channel)
            else -> emptyList()
        }
        return normalize(chapters)
    }

    /** Sorted by start, without duplicates; the first chapter starts the track so every position has a chapter. */
    internal fun normalize(chapters: List<AudioChapter>): List<AudioChapter> {
        val sorted = chapters.filter { it.startMs >= 0 }.sortedBy { it.startMs }.distinctBy { it.startMs }
        if (sorted.size < 2) return emptyList()
        return listOf(sorted.first().copy(startMs = 0)) + sorted.drop(1)
    }

    // ---------------------------------------------------------------- MP4

    private class Box(val type: String, val start: Int, val end: Int)

    private fun mp4(channel: SeekableByteChannel): List<AudioChapter> {
        val moov = boxesAt(channel, 0, channel.size()).firstOrNull { it.type == "moov" } ?: return emptyList()
        // Only the boxes chapters live in are read: a long book's sample tables and its cover art take megabytes.
        val kids = boxesAt(channel, moov.bodyStart, moov.end)
        class Trak(val box: FileBox, val head: ByteArray, val id: Int?, val refs: Set<Int>)
        val traks = kids.filter { it.type == "trak" }.map { t ->
            val head = cat(boxesAt(channel, t.bodyStart, t.end).filter { it.type == "tkhd" || it.type == "tref" }.mapNotNull { raw(channel, it) })
            val b = ByteBuffer.wrap(head)
            var id: Int? = null
            val refs = mutableSetOf<Int>()
            for (box in children(b, 0, b.limit())) {
                when (box.type) {
                    "tkhd" -> id = runCatching { trackId(b, box) }.getOrNull()
                    "tref" -> child(b, box, "chap")?.let { c -> (c.start until c.end - 3 step 4).forEach { refs += b.getInt(it) } }
                }
            }
            Trak(t, head, id, refs)
        }
        val chapterIds = traks.flatMap { it.refs }.toSet()
        val trimmed = ByteArrayOutputStream()
        for (t in traks) {
            // The chapter text track is small (one sample per chapter); every other track keeps only its header.
            val full = t.id in chapterIds && t.box.end - t.box.start <= MAX_CHAPTER_TRAK
            trimmed.write((if (full) raw(channel, t.box) else null) ?: wrap("trak", t.head))
        }
        kids.firstOrNull { it.type == "udta" }?.let { udta ->
            boxesAt(channel, udta.bodyStart, udta.end).firstOrNull { it.type == "chpl" }?.let { raw(channel, it) }?.let { trimmed.write(wrap("udta", it)) }
        }
        val buffer = ByteBuffer.wrap(trimmed.toByteArray())
        return quickTimeChapters(buffer, channel).ifEmpty { neroChapters(buffer) }
    }

    /** A box of the file: [start] of its header, [bodyStart] of its content, [end] exclusive. */
    private class FileBox(val type: String, val start: Long, val bodyStart: Long, val end: Long)

    /** The boxes between [from] and [to], found by reading their headers only. */
    private fun boxesAt(channel: SeekableByteChannel, from: Long, to: Long): List<FileBox> {
        val out = mutableListOf<FileBox>()
        var pos = from
        while (pos + 8 <= to && out.size < MAX_BOXES) {
            val h = readAt(channel, pos, minOf(16L, to - pos).toInt()) ?: break
            var size = h.getInt(0).toLong() and 0xFFFFFFFFL
            var header = 8
            if (size == 1L) {
                if (h.limit() < 16) break
                size = h.getLong(8)
                header = 16
            }
            if (size == 0L) size = to - pos
            if (size < header || pos + size > to) break
            out += FileBox(h.ascii(4, 4), pos, pos + header, pos + size)
            pos += size
        }
        return out
    }

    private fun raw(channel: SeekableByteChannel, box: FileBox): ByteArray? =
        readAt(channel, box.start, (box.end - box.start).toInt())?.let { b -> ByteArray(b.limit()).also { b.get(it) } }

    private fun wrap(type: String, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()

    private fun cat(parts: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

    private fun children(b: ByteBuffer, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var p = from
        while (p + 8 <= to) {
            var size = b.getInt(p).toLong() and 0xFFFFFFFFL
            var header = 8
            if (size == 1L && p + 16 <= to) { size = b.getLong(p + 8); header = 16 }
            if (size == 0L) size = (to - p).toLong()
            if (size < header || p + size > to) break
            out += Box(b.ascii(p + 4, 4), p + header, (p + size).toInt())
            p += size.toInt()
        }
        return out
    }

    private fun child(b: ByteBuffer, parent: Box?, vararg path: String): Box? {
        var cur = parent ?: return null
        for (type in path) cur = children(b, cur.start, cur.end).firstOrNull { it.type == type } ?: return null
        return cur
    }

    /** Nero chapters: `moov/udta/chpl`, start times in 100 ns units. */
    internal fun neroChapters(moov: ByteBuffer): List<AudioChapter> {
        val root = Box("moov", 0, moov.limit())
        val chpl = child(moov, root, "udta", "chpl") ?: return emptyList()
        var p = chpl.start
        val version = moov.get(p).toInt()
        p += 4
        if (version != 0) p += 4
        if (p >= chpl.end) return emptyList()
        val count = moov.get(p).toInt() and 0xFF
        p += 1
        val out = mutableListOf<AudioChapter>()
        repeat(count) {
            if (p + 9 > chpl.end) return out
            val start = moov.getLong(p) / 10_000
            val len = moov.get(p + 8).toInt() and 0xFF
            p += 9
            if (p + len > chpl.end) return out
            out += AudioChapter(moov.text(p, len, Charsets.UTF_8).ifBlank { "${out.size + 1}" }, start)
            p += len
        }
        return out
    }

    /** QuickTime chapters: a text track referenced by an audio track's `tref/chap`; one text sample per chapter. */
    private fun quickTimeChapters(moov: ByteBuffer, channel: SeekableByteChannel): List<AudioChapter> {
        val root = Box("moov", 0, moov.limit())
        val traks = children(moov, root.start, root.end).filter { it.type == "trak" }
        val chapterIds = traks.mapNotNull { child(moov, it, "tref", "chap") }
            .flatMap { chap -> (chap.start until chap.end - 3 step 4).map { moov.getInt(it) } }.toSet()
        if (chapterIds.isEmpty()) return emptyList()
        val trak = traks.firstOrNull { t -> child(moov, t, "tkhd")?.let { trackId(moov, it) } in chapterIds } ?: return emptyList()
        val mdhd = child(moov, trak, "mdia", "mdhd") ?: return emptyList()
        val timescale = if (moov.get(mdhd.start).toInt() == 1) moov.getInt(mdhd.start + 20) else moov.getInt(mdhd.start + 12)
        if (timescale <= 0) return emptyList()
        val stbl = child(moov, trak, "mdia", "minf", "stbl") ?: return emptyList()
        val table = sampleTable(moov, stbl) ?: return emptyList()
        val out = mutableListOf<AudioChapter>()
        var time = 0L
        for (i in table.offsets.indices) {
            val title = sampleText(channel, table.offsets[i], table.sizes[i])
            out += AudioChapter(title?.takeIf { it.isNotBlank() } ?: "${i + 1}", time * 1000 / timescale)
            time += table.durations.getOrElse(i) { 0 }
        }
        return out
    }

    private fun trackId(b: ByteBuffer, tkhd: Box): Int = if (b.get(tkhd.start).toInt() == 1) b.getInt(tkhd.start + 20) else b.getInt(tkhd.start + 12)

    private class SampleTable(val offsets: List<Long>, val sizes: List<Int>, val durations: List<Long>)

    private fun sampleTable(b: ByteBuffer, stbl: Box): SampleTable? {
        val stts = child(b, stbl, "stts") ?: return null
        val stsz = child(b, stbl, "stsz") ?: return null
        val stsc = child(b, stbl, "stsc") ?: return null
        val co64 = child(b, stbl, "co64")
        val stco = child(b, stbl, "stco") ?: co64 ?: return null

        val durations = mutableListOf<Long>()
        for (e in 0 until b.getInt(stts.start + 4)) {
            val at = stts.start + 8 + e * 8
            if (at + 8 > stts.end) break
            val count = b.getInt(at)
            val delta = b.getInt(at + 4).toLong() and 0xFFFFFFFFL
            repeat(count.coerceIn(0, 10_000)) { durations += delta }
        }

        val uniform = b.getInt(stsz.start + 4)
        val sampleCount = b.getInt(stsz.start + 8).coerceIn(0, 10_000)
        val sizes = List(sampleCount) { i -> if (uniform != 0) uniform else b.getInt(stsz.start + 12 + i * 4) }

        val wide = stco === co64
        val chunkCount = b.getInt(stco.start + 4)
        val chunks = List(chunkCount.coerceIn(0, 10_000)) { i ->
            if (wide) b.getLong(stco.start + 8 + i * 8) else b.getInt(stco.start + 8 + i * 4).toLong() and 0xFFFFFFFFL
        }

        val runs = List(b.getInt(stsc.start + 4).coerceIn(0, 10_000)) { i -> b.getInt(stsc.start + 8 + i * 12) to b.getInt(stsc.start + 12 + i * 12) }
        val offsets = mutableListOf<Long>()
        var sample = 0
        for ((chunkIndex, chunkOffset) in chunks.withIndex()) {
            val perChunk = runs.lastOrNull { it.first <= chunkIndex + 1 }?.second ?: 1
            var at = chunkOffset
            repeat(perChunk) {
                if (sample >= sizes.size) return@repeat
                offsets += at
                at += sizes[sample]
                sample++
            }
        }
        return SampleTable(offsets, sizes.take(offsets.size), durations)
    }

    /** A text sample: a 16-bit length, then UTF-8 or UTF-16 (with a byte-order mark) text. */
    private fun sampleText(channel: SeekableByteChannel, offset: Long, size: Int): String? {
        if (size < 2) return null
        val b = readAt(channel, offset, size.coerceAtMost(MAX_TEXT_SAMPLE)) ?: return null
        val len = (b.getShort(0).toInt() and 0xFFFF).coerceAtMost(b.limit() - 2)
        if (len <= 0) return null
        val utf16 = len >= 2 && (b.get(2).toInt() and 0xFF) == 0xFE && (b.get(3).toInt() and 0xFF) == 0xFF
        return b.text(2, len, if (utf16) Charsets.UTF_16 else Charsets.UTF_8).trim()
    }

    // ---------------------------------------------------------------- ID3v2

    private fun id3(channel: SeekableByteChannel): List<AudioChapter> {
        val h = readAt(channel, 0, 10) ?: return emptyList()
        val major = h.get(3).toInt()
        val flags = h.get(5).toInt()
        // ID3v2.2 has no chapters; a whole-tag unsynchronised tag is rare and not worth decoding.
        if (major !in 3..4 || flags and 0x80 != 0) return emptyList()
        val size = syncsafe(h, 6).toLong()
        if (size <= 0 || size > MAX_ID3) return emptyList()
        val end = minOf(10 + size, channel.size())
        var pos = 10L
        if (flags and 0x40 != 0) {
            val ext = readAt(channel, pos, 4) ?: return emptyList()
            pos += if (major == 4) syncsafe(ext, 0).toLong() else (ext.getInt(0).toLong() and 0xFFFFFFFFL) + 4
        }
        // Frame headers are read one by one and only CHAP frames are loaded: the cover picture can be megabytes.
        val chaps = ByteArrayOutputStream()
        while (pos + 10 <= end) {
            val f = readAt(channel, pos, 10) ?: break
            if (f.get(0).toInt() == 0) break
            val frameSize = if (major == 4) syncsafe(f, 4).toLong() else f.getInt(4).toLong() and 0xFFFFFFFFL
            if (frameSize < 0 || pos + 10 + frameSize > end) break
            if (f.ascii(0, 4) == "CHAP") readAt(channel, pos, (10 + frameSize).toInt())?.let { b -> chaps.write(b.array(), 0, b.limit()) }
            pos += 10 + frameSize
        }
        val tag = ByteBuffer.wrap(chaps.toByteArray())
        return id3Chapters(tag, 0, tag.limit(), major)
    }

    private class Frame(val id: String, val start: Int, val end: Int)

    private fun frames(b: ByteBuffer, from: Int, to: Int, major: Int): List<Frame> {
        val out = mutableListOf<Frame>()
        var p = from
        while (p + 10 <= to) {
            if (b.get(p).toInt() == 0) break
            val id = b.ascii(p, 4)
            val size = if (major == 4) syncsafe(b, p + 4) else b.getInt(p + 4)
            if (size < 0 || p + 10 + size > to) break
            out += Frame(id, p + 10, p + 10 + size)
            p += 10 + size
        }
        return out
    }

    internal fun id3Chapters(tag: ByteBuffer, from: Int, to: Int, major: Int): List<AudioChapter> {
        val chapters = LinkedHashMap<String, AudioChapter>()
        frames(tag, from, to, major).filter { it.id == "CHAP" }.forEach { f ->
            val idEnd = (f.start until f.end).firstOrNull { tag.get(it).toInt() == 0 } ?: return@forEach
            val element = tag.text(f.start, idEnd - f.start, Charsets.ISO_8859_1)
            val times = idEnd + 1
            if (times + 16 > f.end) return@forEach
            val startMs = tag.getInt(times).toLong() and 0xFFFFFFFFL
            val title = frames(tag, times + 16, f.end, major).firstOrNull { it.id == "TIT2" }?.let { textFrame(tag, it) }
            chapters[element] = AudioChapter(title?.takeIf { it.isNotBlank() } ?: "${chapters.size + 1}", startMs)
        }
        return chapters.values.toList()
    }

    private fun textFrame(b: ByteBuffer, f: Frame): String? {
        if (f.end - f.start < 2) return null
        val charset = when (b.get(f.start).toInt()) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return b.text(f.start + 1, f.end - f.start - 1, charset).trimEnd('\u0000').trim()
    }

    private fun syncsafe(b: ByteBuffer, at: Int): Int = (0 until 4).fold(0) { acc, i -> (acc shl 7) or (b.get(at + i).toInt() and 0x7F) }

    // ---------------------------------------------------------------- bytes

    private fun readAt(channel: SeekableByteChannel, position: Long, length: Int): ByteBuffer? {
        if (length < 0 || position < 0 || position + length > channel.size()) return null
        val buffer = ByteBuffer.allocate(length)
        channel.position(position)
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) return null
        buffer.flip()
        return buffer
    }

    private fun ByteBuffer.ascii(at: Int, length: Int): String = text(at, length, Charsets.ISO_8859_1)

    private fun ByteBuffer.text(at: Int, length: Int, charset: Charset): String {
        val bytes = ByteArray(length.coerceIn(0, limit() - at))
        for (i in bytes.indices) bytes[i] = get(at + i)
        return String(bytes, charset)
    }
}

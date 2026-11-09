package app.yomu.data.audio

import app.yomu.domain.model.AudioChapter
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset

/** Chapter marks stored inside an audio file: MP4/M4B (Nero `chpl` and QuickTime chapter text tracks) and MP3 (ID3v2 CHAP frames). */
object EmbeddedChapters {
    private const val MAX_CHAPTERS = 10_000
    private const val MAX_TITLE = 500
    private const val MAX_TABLE = 16 * 1024 * 1024
    private const val MAX_ID3 = 32 * 1024 * 1024
    private const val MAX_BOXES = 100_000

    /** Chapters of the file read through [channel] (random access), sorted by start; empty when fewer than 2 or unknown format. Never throws on malformed data. */
    fun read(channel: SeekableByteChannel): List<AudioChapter> = runCatching {
        val head = channel.readAt(0, 10)
        val marks = when {
            String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> readMp4(channel)
            String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> readId3(channel, head)
            else -> emptyList()
        }
        normalize(marks)
    }.getOrDefault(emptyList())

    private fun normalize(marks: List<AudioChapter>): List<AudioChapter> {
        val named = marks.take(MAX_CHAPTERS).mapIndexed { i, c ->
            AudioChapter(c.title.trim().take(MAX_TITLE).ifBlank { "Chapter ${i + 1}" }, c.startMs)
        }
        return named.sortedBy { it.startMs }.distinctBy { it.startMs }.takeIf { it.size >= 2 }.orEmpty()
    }

    private fun SeekableByteChannel.readAt(pos: Long, n: Int): ByteArray {
        require(pos >= 0 && n in 0..MAX_ID3)
        position(pos)
        val buf = ByteBuffer.allocate(n)
        while (buf.hasRemaining()) if (read(buf) < 0) throw EOFException()
        return buf.array()
    }

    // MP4

    private class Box(val type: String, val start: Long, val end: Long)

    private fun SeekableByteChannel.boxes(from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p + 8 <= to && out.size < MAX_BOXES) {
            val h = readAt(p, 8)
            var size = ByteBuffer.wrap(h).int.toLong() and 0xFFFFFFFFL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            when (size) {
                1L -> {
                    size = ByteBuffer.wrap(readAt(p + 8, 8)).long
                    header = 16
                }
                0L -> size = to - p
            }
            if (size < header) break
            val end = if (size > to - p) to else p + size
            out += Box(type, p + header, end)
            p = end
        }
        return out
    }

    private fun SeekableByteChannel.child(parent: Box, type: String) =
        boxes(parent.start, parent.end).firstOrNull { it.type == type }

    private fun SeekableByteChannel.payload(box: Box): ByteBuffer {
        val n = box.end - box.start
        check(n <= MAX_TABLE) { "table too large" }
        return ByteBuffer.wrap(readAt(box.start, n.toInt()))
    }

    private fun readMp4(ch: SeekableByteChannel): List<AudioChapter> {
        val moov = ch.boxes(0, ch.size()).firstOrNull { it.type == "moov" } ?: return emptyList()
        return readChapterTrack(ch, moov).takeIf { it.size >= 2 } ?: readChpl(ch, moov)
    }

    private fun readChpl(ch: SeekableByteChannel, moov: Box): List<AudioChapter> {
        val chpl = ch.child(moov, "udta")?.let { ch.child(it, "chpl") } ?: return emptyList()
        val b = ch.payload(chpl)
        val version = b.get().toInt()
        b.position(b.position() + 3)
        if (version == 1) b.position(b.position() + 4)
        val count = b.get().toInt() and 0xFF
        return List(count) {
            val start = b.long / 10_000
            val title = ByteArray(b.get().toInt() and 0xFF).also { b.get(it) }
            AudioChapter(String(title, Charsets.UTF_8), start)
        }
    }

    /** Track ID from `tkhd`, or timescale from `mdhd`: both put the field at 12 (version 0) or 20 (version 1). */
    private fun versionedInt(b: ByteBuffer) = b.getInt(if (b.get(0).toInt() == 1) 20 else 12).toLong() and 0xFFFFFFFFL

    private fun readChapterTrack(ch: SeekableByteChannel, moov: Box): List<AudioChapter> {
        val traks = ch.boxes(moov.start, moov.end).filter { it.type == "trak" }
        val refs = traks.flatMap { trak ->
            val chap = ch.child(trak, "tref")?.let { ch.child(it, "chap") } ?: return@flatMap emptyList()
            val b = ch.payload(chap)
            List(b.remaining() / 4) { b.int.toLong() and 0xFFFFFFFFL }
        }.toSet()
        if (refs.isEmpty()) return emptyList()
        val trak = traks.firstOrNull { t ->
            ch.child(t, "tkhd")?.let { versionedInt(ch.payload(it)) } in refs
        } ?: return emptyList()
        val mdia = ch.child(trak, "mdia") ?: return emptyList()
        val timescale = ch.child(mdia, "mdhd")?.let { versionedInt(ch.payload(it)) } ?: return emptyList()
        if (timescale == 0L) return emptyList()
        val stbl = ch.child(mdia, "minf")?.let { ch.child(it, "stbl") } ?: return emptyList()
        val tables = ch.boxes(stbl.start, stbl.end).associateBy { it.type }

        val stts = ch.payload(tables["stts"] ?: return emptyList()).apply { position(4) }
        val starts = ArrayList<Long>()
        var time = 0L
        repeat(stts.int) {
            val n = stts.int
            val delta = stts.int.toLong() and 0xFFFFFFFFL
            repeat(n.coerceAtMost(MAX_CHAPTERS - starts.size)) {
                starts += time
                time += delta
            }
        }

        val stsz = ch.payload(tables["stsz"] ?: return emptyList()).apply { position(4) }
        val uniform = stsz.int
        val sampleCount = minOf(stsz.int, starts.size)
        val sizes = IntArray(sampleCount) { if (uniform != 0) uniform else stsz.int }

        val stsc = ch.payload(tables["stsc"] ?: return emptyList()).apply { position(4) }
        // (first chunk, samples per chunk); the sample description index is not needed
        val runs = List(stsc.int) { (stsc.int to stsc.int).also { stsc.int } }

        val wide = tables["co64"] != null
        val co = ch.payload(tables["co64"] ?: tables["stco"] ?: return emptyList()).apply { position(4) }
        val chunks = LongArray(co.int) { if (wide) co.long else co.int.toLong() and 0xFFFFFFFFL }

        val out = ArrayList<AudioChapter>()
        var sample = 0
        var run = 0
        for ((index, chunkOffset) in chunks.withIndex()) {
            if (sample >= sampleCount) break
            while (run + 1 < runs.size && runs[run + 1].first <= index + 1) run++
            var offset = chunkOffset
            repeat(runs.getOrNull(run)?.second ?: 0) {
                if (sample < sampleCount) {
                    out += AudioChapter(readSampleText(ch, offset, sizes[sample]), starts[sample] * 1000 / timescale)
                    offset += sizes[sample]
                    sample++
                }
            }
        }
        return out
    }

    private fun readSampleText(ch: SeekableByteChannel, offset: Long, size: Int): String {
        if (size < 2) return ""
        val b = ch.readAt(offset, size.coerceAtMost(MAX_TITLE * 4 + 2))
        val len = minOf(((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF), b.size - 2)
        val bom = len >= 2 && (b[2].toInt() and 0xFF) + (b[3].toInt() and 0xFF) == 0xFE + 0xFF
        return String(b, 2, len, if (bom) Charsets.UTF_16 else Charsets.UTF_8).trim('\u0000', '\uFEFF')
    }

    // ID3v2

    private fun syncsafe(b: ByteArray, at: Int) =
        (b[at].toInt() shl 21) or (b[at + 1].toInt() shl 14) or (b[at + 2].toInt() shl 7) or b[at + 3].toInt()

    private fun int32(b: ByteArray, at: Int) = ByteBuffer.wrap(b, at, 4).int

    private class Frame(val id: String, val start: Int, val end: Int)

    private fun readId3(ch: SeekableByteChannel, head: ByteArray): List<AudioChapter> {
        val version = head[3].toInt()
        if (version !in 3..4) return emptyList()
        val flags = head[5].toInt()
        val size = syncsafe(head, 6)
        if (size <= 0 || size > MAX_ID3) return emptyList()
        var tag = ch.readAt(10, size)
        if (flags and 0x80 != 0) tag = unsynchronise(tag)
        var pos = 0
        if (flags and 0x40 != 0 && tag.size >= 4) {
            pos = if (version == 4) syncsafe(tag, 0) else int32(tag, 0) + 4
        }
        val frames = frames(tag, pos, tag.size, version)
        val chaps = frames.filter { it.id == "CHAP" }.mapNotNull { chapter(tag, it, version) }
        val order = frames.firstOrNull { it.id == "CTOC" }?.let { ctoc(tag, it) }.orEmpty()
        val byId = chaps.associateBy { it.first }
        val ordered = order.mapNotNull { byId[it] }
        return (ordered.takeIf { it.size >= 2 } ?: chaps).map { it.second }
    }

    private fun unsynchronise(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun frames(b: ByteArray, from: Int, to: Int, version: Int): List<Frame> {
        val out = ArrayList<Frame>()
        var p = from
        while (p >= 0 && p + 10 <= to && b[p] != 0.toByte() && out.size < MAX_CHAPTERS * 2) {
            val size = if (version == 4) syncsafe(b, p + 4) else int32(b, p + 4)
            val end = p + 10 + size
            if (size < 0 || end > to) break
            out += Frame(String(b, p, 4, Charsets.ISO_8859_1), p + 10, end)
            p = end
        }
        return out
    }

    private fun cString(b: ByteArray, from: Int, to: Int): Pair<String, Int>? {
        val z = (from until to).firstOrNull { b[it] == 0.toByte() } ?: return null
        return String(b, from, z - from, Charsets.ISO_8859_1) to z + 1
    }

    private fun chapter(b: ByteArray, f: Frame, version: Int): Pair<String, AudioChapter>? {
        val (id, p) = cString(b, f.start, f.end) ?: return null
        if (p + 16 > f.end) return null
        val start = int32(b, p).toLong() and 0xFFFFFFFFL
        val title = frames(b, p + 16, f.end, version).firstOrNull { it.id == "TIT2" }?.let { text(b, it) }.orEmpty()
        return id to AudioChapter(title, start)
    }

    private fun ctoc(b: ByteArray, f: Frame): List<String> {
        var p = cString(b, f.start, f.end)?.second ?: return emptyList()
        if (p + 2 > f.end) return emptyList()
        val count = b[p + 1].toInt() and 0xFF
        p += 2
        val ids = ArrayList<String>()
        repeat(count) {
            val (id, next) = cString(b, p, f.end) ?: return ids
            ids += id
            p = next
        }
        return ids
    }

    private fun text(b: ByteArray, f: Frame): String {
        if (f.end <= f.start) return ""
        val charset: Charset = when (b[f.start].toInt()) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(b, f.start + 1, f.end - f.start - 1, charset).substringBefore('\u0000')
    }
}

package app.yomu.data.audio

import app.yomu.domain.model.AudioChapter
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset

/**
 * Chapters stored inside an audio file: MP4/M4B (a QuickTime chapter text track or a Nero `chpl` box) and MP3
 * (ID3v2 `CHAP` frames). Only headers are read, by seeking, so a large file costs a few small reads.
 */
object EmbeddedChapters {
    data class Info(val durationMs: Long, val chapters: List<AudioChapter>)

    private const val MAX_CHAPTERS = 5_000

    fun read(channel: SeekableByteChannel): Info? = runCatching {
        val head = channel.readAt(0, 10) ?: return null
        when {
            head.startsWith("ID3") -> Id3(channel).read()
            head.size >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) in MP4_FIRST_BOXES -> Mp4(channel).read()
            else -> null
        }
    }.getOrNull()

    private val MP4_FIRST_BOXES = setOf("ftyp", "moov", "mdat", "free", "skip", "wide")

    /** Chapters in time order, the first starting at 0 so the start of the file belongs to it; null below two. */
    internal fun normalize(list: List<AudioChapter>): List<AudioChapter>? {
        val sorted = list.filter { it.startMs >= 0 }.distinctBy { it.startMs }.sortedBy { it.startMs }
        if (sorted.size < 2) return null
        return sorted.mapIndexed { i, c ->
            AudioChapter(c.title.trim().ifEmpty { "${i + 1}" }, if (i == 0) 0 else c.startMs)
        }
    }

    // ---------------------------------------------------------------- MP4

    private class Box(val type: String, val start: Long, val bodyStart: Long, val end: Long)

    private class Mp4(private val ch: SeekableByteChannel) {
        private val size = ch.size()

        fun read(): Info? {
            val moov = children(0, size).firstOrNull { it.type == "moov" } ?: return null
            val boxes = children(moov.bodyStart, moov.end)
            val duration = boxes.firstOrNull { it.type == "mvhd" }?.let(::durationOf) ?: 0L
            val traks = boxes.filter { it.type == "trak" }.mapNotNull(::trak)
            val chapterIds = traks.flatMap { it.chapterRefs }.toSet()
            val qt = traks.firstOrNull { it.id in chapterIds }?.let(::textSamples)
            val nero = boxes.firstOrNull { it.type == "udta" }?.let { u -> children(u.bodyStart, u.end).firstOrNull { it.type == "chpl" } }?.let(::chpl)
            return Info(duration, normalize(qt.orEmpty()) ?: normalize(nero.orEmpty()).orEmpty())
        }

        private class Trak(
            val id: Long, val chapterRefs: List<Long>, val timescale: Long,
            val stts: Box?, val stsz: Box?, val stsc: Box?, val stco: Box?, val co64: Box?,
        )

        private fun trak(box: Box): Trak? {
            val kids = children(box.bodyStart, box.end)
            val tkhd = kids.firstOrNull { it.type == "tkhd" } ?: return null
            val version = byteAt(tkhd.bodyStart)
            val id = u32(tkhd.bodyStart + if (version == 1) 20 else 12)
            val refs = kids.firstOrNull { it.type == "tref" }?.let { t -> children(t.bodyStart, t.end).filter { it.type == "chap" } }.orEmpty()
                .flatMap { c -> (0 until ((c.end - c.bodyStart) / 4).coerceAtMost(64)).map { u32(c.bodyStart + it * 4) } }
            val mdia = kids.firstOrNull { it.type == "mdia" } ?: return Trak(id, refs, 0, null, null, null, null, null)
            val mdiaKids = children(mdia.bodyStart, mdia.end)
            val mdhd = mdiaKids.firstOrNull { it.type == "mdhd" }
            val timescale = mdhd?.let { u32(it.bodyStart + if (byteAt(it.bodyStart) == 1) 20 else 12) } ?: 0
            val stbl = mdiaKids.firstOrNull { it.type == "minf" }?.let { m -> children(m.bodyStart, m.end).firstOrNull { it.type == "stbl" } }
            val s = stbl?.let { children(it.bodyStart, it.end) }.orEmpty()
            return Trak(
                id, refs, timescale,
                s.firstOrNull { it.type == "stts" }, s.firstOrNull { it.type == "stsz" }, s.firstOrNull { it.type == "stsc" },
                s.firstOrNull { it.type == "stco" }, s.firstOrNull { it.type == "co64" },
            )
        }

        /** The samples of a chapter text track: each is a 16-bit length and the title, starting at its decode time. */
        private fun textSamples(t: Trak): List<AudioChapter>? {
            if (t.timescale <= 0 || t.stts == null || t.stsz == null || t.stsc == null) return null
            val starts = ArrayList<Long>()
            var time = 0L
            val sttsCount = u32(t.stts.bodyStart + 4).coerceAtMost(MAX_CHAPTERS.toLong()).toInt()
            for (i in 0 until sttsCount) {
                val count = u32(t.stts.bodyStart + 8 + i * 8L)
                val delta = u32(t.stts.bodyStart + 12 + i * 8L)
                for (k in 0 until count.coerceAtMost(MAX_CHAPTERS.toLong())) {
                    if (starts.size >= MAX_CHAPTERS) break
                    starts += time
                    time += delta
                }
            }
            val fixedSize = u32(t.stsz.bodyStart + 4)
            val sampleCount = u32(t.stsz.bodyStart + 8).coerceAtMost(starts.size.toLong()).toInt()
            val sizes = (0 until sampleCount).map { if (fixedSize != 0L) fixedSize else u32(t.stsz.bodyStart + 12 + it * 4L) }
            val chunks = when {
                t.stco != null -> (0 until u32(t.stco.bodyStart + 4).coerceAtMost(MAX_CHAPTERS.toLong())).map { u32(t.stco.bodyStart + 8 + it * 4) }
                t.co64 != null -> (0 until u32(t.co64.bodyStart + 4).coerceAtMost(MAX_CHAPTERS.toLong())).map { u64(t.co64.bodyStart + 8 + it * 8) }
                else -> return null
            }
            // stsc: runs of chunks with the same number of samples per chunk.
            val runs = (0 until u32(t.stsc.bodyStart + 4).coerceAtMost(MAX_CHAPTERS.toLong())).map {
                u32(t.stsc.bodyStart + 8 + it * 12).toInt() to u32(t.stsc.bodyStart + 12 + it * 12).toInt()
            }
            val offsets = ArrayList<Long>(sampleCount)
            for ((c, chunkOffset) in chunks.withIndex()) {
                val perChunk = runs.lastOrNull { it.first <= c + 1 }?.second ?: break
                var offset = chunkOffset
                repeat(perChunk) {
                    if (offsets.size < sampleCount) {
                        offsets += offset
                        offset += sizes[offsets.size - 1]
                    }
                }
                if (offsets.size >= sampleCount) break
            }
            return offsets.indices.mapNotNull { i ->
                val len = u16(offsets[i])
                if (len <= 0 || len > sizes[i] - 2) return@mapNotNull AudioChapter("", starts[i] * 1000 / t.timescale)
                val bytes = ch.readAt(offsets[i] + 2, len) ?: return@mapNotNull null
                AudioChapter(decodeText(bytes), starts[i] * 1000 / t.timescale)
            }
        }

        /** Nero chapters: version, flags, (reserved), count, then start (100 ns units) and a Pascal string each. */
        private fun chpl(box: Box): List<AudioChapter> {
            val version = byteAt(box.bodyStart)
            var pos = box.bodyStart + 4 + if (version > 0) 4 else 0
            val count = byteAt(pos)
            pos += 1
            val out = mutableListOf<AudioChapter>()
            repeat(count) {
                if (pos + 9 > box.end) return out
                val start = u64(pos)
                val len = byteAt(pos + 8)
                val title = ch.readAt(pos + 9, len)?.toString(Charsets.UTF_8).orEmpty()
                out += AudioChapter(title, start / 10_000)
                pos += 9 + len
            }
            return out
        }

        private fun durationOf(mvhd: Box): Long {
            val v1 = byteAt(mvhd.bodyStart) == 1
            val timescale = u32(mvhd.bodyStart + if (v1) 20 else 12)
            val duration = if (v1) u64(mvhd.bodyStart + 24) else u32(mvhd.bodyStart + 16)
            return if (timescale > 0) duration * 1000 / timescale else 0
        }

        private fun children(from: Long, to: Long): List<Box> {
            val out = mutableListOf<Box>()
            var pos = from
            while (pos + 8 <= to && out.size < 1_000) {
                val header = ch.readAt(pos, 16) ?: break
                val b = ByteBuffer.wrap(header)
                var boxSize = b.getInt(0).toLong() and 0xffffffffL
                val type = String(header, 4, 4, Charsets.ISO_8859_1)
                var body = pos + 8
                when (boxSize) {
                    1L -> { boxSize = b.getLong(8); body = pos + 16 }
                    0L -> boxSize = to - pos
                }
                if (boxSize < body - pos || pos + boxSize > to) break
                out += Box(type, pos, body, pos + boxSize)
                pos += boxSize
            }
            return out
        }

        private fun byteAt(pos: Long): Int = ch.readAt(pos, 1)?.get(0)?.toInt()?.and(0xff) ?: 0
        private fun u16(pos: Long): Int = ch.readAt(pos, 2)?.let { ByteBuffer.wrap(it).short.toInt() and 0xffff } ?: 0
        private fun u32(pos: Long): Long = ch.readAt(pos, 4)?.let { ByteBuffer.wrap(it).int.toLong() and 0xffffffffL } ?: 0
        private fun u64(pos: Long): Long = ch.readAt(pos, 8)?.let { ByteBuffer.wrap(it).long } ?: 0

        private fun decodeText(bytes: ByteArray): String = when {
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            else -> bytes.toString(Charsets.UTF_8)
        }.trimEnd('\u0000')
    }

    // ---------------------------------------------------------------- ID3

    private class Id3(private val ch: SeekableByteChannel) {
        fun read(): Info? {
            val header = ch.readAt(0, 10) ?: return null
            val major = header[3].toInt()
            val flags = header[5].toInt()
            if (major !in 3..4 || flags and 0x80 != 0) return null // unsynchronised tags are rare: skip them
            val tagEnd = 10 + syncsafe(header, 6)
            var pos = 10L
            if (flags and 0x40 != 0) {
                val ext = ch.readAt(pos, 4) ?: return null
                pos += if (major == 4) syncsafe(ext, 0) else ByteBuffer.wrap(ext).int.toLong() + 4
            }
            val chapters = mutableListOf<AudioChapter>()
            frames(pos, tagEnd, major) { id, body, size ->
                if (id == "CHAP" && chapters.size < MAX_CHAPTERS) chap(body, size, major)?.let { chapters += it }
            }
            return Info(0, normalize(chapters).orEmpty())
        }

        private fun frames(from: Long, to: Long, major: Int, onFrame: (String, Long, Int) -> Unit) {
            var pos = from
            while (pos + 10 <= to) {
                val h = ch.readAt(pos, 10) ?: return
                if (h[0] == 0.toByte()) return // padding
                val id = String(h, 0, 4, Charsets.ISO_8859_1)
                val size = if (major == 4) syncsafe(h, 4) else ByteBuffer.wrap(h, 4, 4).int.toLong() and 0xffffffffL
                if (size <= 0 || pos + 10 + size > to) return
                onFrame(id, pos + 10, size.toInt())
                pos += 10 + size
            }
        }

        /** CHAP: element id (null-terminated), start and end ms, byte offsets, then sub-frames (TIT2 is the title). */
        private fun chap(body: Long, size: Int, major: Int): AudioChapter? {
            val bytes = ch.readAt(body, size.coerceAtMost(64 * 1024)) ?: return null
            val idEnd = bytes.indexOf(0).takeIf { it >= 0 } ?: return null
            if (idEnd + 17 > bytes.size) return null
            val start = ByteBuffer.wrap(bytes, idEnd + 1, 4).int.toLong() and 0xffffffffL
            var title = ""
            var p = idEnd + 17
            while (p + 10 <= bytes.size) {
                val id = String(bytes, p, 4, Charsets.ISO_8859_1)
                val len = if (major == 4) syncsafe(bytes, p + 4).toInt() else ByteBuffer.wrap(bytes, p + 4, 4).int
                if (len <= 0 || p + 10 + len > bytes.size) break
                if (id == "TIT2") title = text(bytes.copyOfRange(p + 10, p + 10 + len))
                p += 10 + len
            }
            return AudioChapter(title, start)
        }

        private fun text(frame: ByteArray): String {
            if (frame.isEmpty()) return ""
            val charset: Charset = when (frame[0].toInt()) {
                1 -> Charsets.UTF_16
                2 -> Charsets.UTF_16BE
                3 -> Charsets.UTF_8
                else -> Charsets.ISO_8859_1
            }
            return String(frame, 1, frame.size - 1, charset).trimEnd('\u0000').substringBefore('\u0000').trim()
        }

        private fun syncsafe(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0x7f shl 21) or (b[at + 1].toLong() and 0x7f shl 14) or (b[at + 2].toLong() and 0x7f shl 7) or (b[at + 3].toLong() and 0x7f)
    }

    // ---------------------------------------------------------------- io

    private fun SeekableByteChannel.readAt(pos: Long, length: Int): ByteArray? {
        if (pos < 0 || length < 0 || pos + length > size()) return null
        val buffer = ByteBuffer.allocate(length)
        position(pos)
        while (buffer.hasRemaining()) if (read(buffer) < 0) return null
        return buffer.array()
    }

    private fun ByteArray.startsWith(prefix: String) = size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }
}

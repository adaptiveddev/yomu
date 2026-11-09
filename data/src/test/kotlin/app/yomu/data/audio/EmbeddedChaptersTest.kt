package app.yomu.data.audio

import app.yomu.domain.model.AudioChapter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EmbeddedChaptersTest {
    private class MemChannel(private val data: ByteArray) : SeekableByteChannel {
        private var pos = 0L
        override fun read(dst: ByteBuffer): Int {
            if (pos >= data.size) return -1
            val n = minOf(dst.remaining(), (data.size - pos).toInt())
            dst.put(data, pos.toInt(), n)
            pos += n
            return n
        }
        override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()
        override fun position() = pos
        override fun position(newPosition: Long) = apply { pos = newPosition }
        override fun size() = data.size.toLong()
        override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()
        override fun isOpen() = true
        override fun close() {}
    }

    private fun read(b: ByteArray) = EmbeddedChapters.read(MemChannel(b))

    private fun bytes(vararg parts: Any): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) when (p) {
            is ByteArray -> out.write(p)
            is String -> out.write(p.toByteArray(Charsets.ISO_8859_1))
            is Int -> out.write(ByteBuffer.allocate(4).putInt(p).array())
            is Long -> out.write(ByteBuffer.allocate(8).putLong(p).array())
            is Byte -> out.write(p.toInt())
            else -> error("bad part")
        }
        return out.toByteArray()
    }

    private fun b(v: Int): Byte = v.toByte()

    private fun box(type: String, vararg parts: Any): ByteArray {
        val body = bytes(*parts)
        return bytes(body.size + 8, type, body)
    }

    private fun box64(type: String, vararg parts: Any): ByteArray {
        val body = bytes(*parts)
        return bytes(1, type, (body.size + 16).toLong(), body)
    }

    private val ftyp get() = box("ftyp", "M4A ", 0, "M4A ")

    private fun chpl(version: Int, vararg marks: Pair<String, Long>): ByteArray {
        val entries = marks.map { (t, ms) ->
            val tb = t.toByteArray()
            bytes(ms * 10_000, b(tb.size), tb)
        }
        val reserved = if (version == 1) ByteArray(4) else ByteArray(0)
        return box("udta", box("chpl", b(version), ByteArray(3), reserved, b(marks.size), *entries.toTypedArray()))
    }

    private fun sample(text: String): ByteArray {
        val tb = text.toByteArray()
        return bytes(byteArrayOf(b(tb.size shr 8), b(tb.size)), tb)
    }

    private fun textTrak(stbl: ByteArray) =
        box("trak", box("tkhd", 0, 0, 0, 2, ByteArray(60)), box("mdia", box("mdhd", 0, 0, 0, 1000, 0, 0), box("minf", stbl)))

    private val audioTrak get() = box("trak", box("tkhd", 0, 0, 0, 1, ByteArray(60)), box("tref", box("chap", 2)))

    /** Audio trak (id 1) referencing a text trak (id 2); samples sit in an mdat before the moov, in two chunks. */
    private fun qtFile(titles: List<String>, durations: List<Int>, wide: Boolean, uniform: Boolean, extra: ByteArray = ByteArray(0)): ByteArray {
        val samples = titles.map { sample(it) }
        val sizes = samples.map { it.size }
        val sizeTable = if (uniform) bytes(0, sizes[0], sizes.size) else bytes(0, 0, sizes.size, *sizes.toTypedArray())
        val c1 = ftyp.size + 8L
        val c2 = c1 + sizes[0]
        val offsets = if (wide) box("co64", 0, 2, c1, c2) else box("stco", 0, 2, c1.toInt(), c2.toInt())
        val stbl = box(
            "stbl",
            box("stts", 0, durations.size, *durations.flatMap { listOf(1, it) }.toTypedArray()),
            box("stsz", sizeTable),
            box("stsc", 0, 2, 1, 1, 1, 2, titles.size - 1, 1),
            offsets,
        )
        return bytes(ftyp, box("mdat", *samples.toTypedArray()), box("moov", audioTrak, textTrak(stbl), extra))
    }

    private val expectedQt = listOf(AudioChapter("Intro", 0), AudioChapter("One", 5000), AudioChapter("Two", 12_500))
    private val durations = listOf(5000, 7500, 1000)

    @Test
    fun quickTimeTrackWithStco() {
        assertEquals(expectedQt, read(qtFile(listOf("Intro", "One", "Two"), durations, wide = false, uniform = false)))
    }

    @Test
    fun quickTimeTrackWithCo64AndUniformSampleSize() {
        // uniform size: every title is 5 bytes long
        val expected = listOf(AudioChapter("Intro", 0), AudioChapter("Part1", 5000), AudioChapter("Part2", 12_500))
        assertEquals(expected, read(qtFile(listOf("Intro", "Part1", "Part2"), durations, wide = true, uniform = true)))
    }

    @Test
    fun quickTimeUtf16Title() {
        val title = "Gl\u00e4ser"
        val utf16 = byteArrayOf(b(0xFE), b(0xFF)) + title.toByteArray(Charsets.UTF_16BE)
        val first = byteArrayOf(b(utf16.size shr 8), b(utf16.size)) + utf16
        val second = sample("B")
        val stbl = box(
            "stbl", box("stts", 0, 2, 1, 1000, 1, 1000), box("stsz", 0, 0, 2, first.size, second.size),
            box("stsc", 0, 1, 1, 2, 1), box("stco", 0, 1, ftyp.size + 8),
        )
        val chapters = read(bytes(ftyp, box("mdat", first, second), box("moov", audioTrak, textTrak(stbl))))
        assertEquals(listOf(AudioChapter(title, 0), AudioChapter("B", 1000)), chapters)
    }

    @Test
    fun neroChplVersions() {
        val expected = listOf(AudioChapter("A", 0), AudioChapter("B", 61_000))
        for (v in 0..1) {
            assertEquals(expected, read(bytes(ftyp, box("moov", chpl(v, "A" to 0L, "B" to 61_000L)))), "version $v")
        }
    }

    @Test
    fun trackWinsOverChpl() {
        val file = qtFile(listOf("Intro", "One", "Two"), durations, wide = false, uniform = false, extra = chpl(0, "X" to 0L, "Y" to 1L))
        assertEquals(expectedQt, read(file))
    }

    @Test
    fun sixtyFourBitBoxSizesAndMoovAfterMdat() {
        val file = bytes(ftyp, box64("mdat", ByteArray(100)), box64("moov", chpl(0, "A" to 0L, "B" to 5L)))
        assertEquals(listOf(AudioChapter("A", 0), AudioChapter("B", 5)), read(file))
    }

    private fun syncsafe(n: Int) = byteArrayOf(b((n shr 21) and 0x7F), b((n shr 14) and 0x7F), b((n shr 7) and 0x7F), b(n and 0x7F))

    private fun frame(id: String, body: ByteArray, syncsafe: Boolean) =
        bytes(id, if (syncsafe) syncsafe(body.size) else bytes(body.size), ByteArray(2), body)

    private fun tag(version: Int, vararg frames: ByteArray): ByteArray {
        val body = frames.fold(ByteArray(0)) { a, f -> a + f }
        return bytes("ID3", b(version), ByteArray(2), syncsafe(body.size), body, ByteArray(20))
    }

    private fun chap(id: String, startMs: Int, title: String?, enc: Int, charset: Charset, syncsafe: Boolean): ByteArray {
        val sub = title?.let { frame("TIT2", bytes(b(enc), it.toByteArray(charset)), syncsafe) } ?: ByteArray(0)
        return frame("CHAP", bytes(id, b(0), startMs, startMs + 1000, -1, -1, sub), syncsafe)
    }

    @Test
    fun id3v23Chapters() {
        val iso = Charsets.ISO_8859_1
        val file = tag(
            3,
            chap("c2", 60_000, "Second", 0, iso, false),
            chap("c1", 0, "First", 0, iso, false),
            chap("c3", 90_000, null, 0, iso, false),
        )
        assertEquals(listOf(AudioChapter("First", 0), AudioChapter("Second", 60_000), AudioChapter("Chapter 3", 90_000)), read(file))
    }

    @Test
    fun id3v24Utf8WithCtocAndSyncsafeSizes() {
        val long = "Ж".repeat(70) // 140 bytes: sizes need a second syncsafe byte
        val ctoc = frame("CTOC", bytes("toc", b(0), b(2), b(2), "b", b(0), "a", b(0)), true)
        val file = tag(
            4,
            ctoc,
            chap("a", 0, long, 3, Charsets.UTF_8, true),
            chap("b", 5000, "Глава", 3, Charsets.UTF_8, true),
            chap("zz", 9, "Ignored", 3, Charsets.UTF_8, true),
        )
        assertEquals(listOf(AudioChapter(long, 0), AudioChapter("Глава", 5000)), read(file))
    }

    @Test
    fun garbageAndNonAudioReturnEmpty() {
        val full = qtFile(listOf("Intro", "One", "Two"), durations, wide = false, uniform = false)
        assertTrue(read(full.copyOf(full.size - 40)).isEmpty())
        assertTrue(read(bytes(ftyp, "moov", ByteArray(5))).isEmpty())
        assertTrue(read(bytes("ID3", b(4), b(0), b(0), b(127), b(127), b(127), b(127))).isEmpty())
        assertTrue(read(ByteArray(3)).isEmpty())
        assertTrue(read(ByteArray(2000) { (it * 31).toByte() }).isEmpty())
        assertTrue(read("%PDF-1.4 hello world".toByteArray()).isEmpty())
    }
}

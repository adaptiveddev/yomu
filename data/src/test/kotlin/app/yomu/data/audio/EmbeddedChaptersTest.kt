package app.yomu.data.audio

import app.yomu.domain.model.AudioChapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files

class EmbeddedChaptersTest {

    @Test
    fun neroChaptersOfM4b() {
        val chpl = fullBox("chpl", 1, u32(0), byteArrayOf(2), neroEntry(0, "Пролог"), neroEntry(65_000, "Chapter 1"))
        val file = box("ftyp", "M4B ".toByteArray(), u32(0)) + box("moov", mvhd(timescale = 1000, duration = 3_600_000), box("udta", chpl))
        val info = read(file)!!
        assertEquals(3_600_000L, info.durationMs)
        assertEquals(listOf(AudioChapter("Пролог", 0), AudioChapter("Chapter 1", 65_000)), info.chapters)
    }

    @Test
    fun quickTimeChapterTrackWinsOverNero() {
        val titles = listOf("Opening", "Глава первая", "The end")
        val samples = titles.map { val b = it.toByteArray(); u16(b.size) + b }
        val ftyp = box("ftyp", "M4A ".toByteArray(), u32(0))
        val mdat = box("mdat", *samples.toTypedArray())
        val chunkOffset = ftyp.size + 8
        val audio = box("trak", fullBox("tkhd", 0, u32(0), u32(0), u32(1), ByteArray(60)), box("tref", box("chap", u32(2))))
        val stbl = box(
            "stbl",
            fullBox("stts", 0, u32(2), u32(1), u32(60_000), u32(2), u32(30_000)),
            fullBox("stsz", 0, u32(0), u32(3), *samples.map { u32(it.size) }.toTypedArray()),
            fullBox("stsc", 0, u32(1), u32(1), u32(3), u32(1)),
            fullBox("stco", 0, u32(1), u32(chunkOffset)),
        )
        val text = box(
            "trak",
            fullBox("tkhd", 0, u32(0), u32(0), u32(2), ByteArray(60)),
            box("mdia", fullBox("mdhd", 0, u32(0), u32(0), u32(1000), u32(120_000), ByteArray(4)), box("minf", stbl)),
        )
        val nero = box("udta", fullBox("chpl", 1, u32(0), byteArrayOf(2), neroEntry(0, "A"), neroEntry(1000, "B")))
        val file = ftyp + mdat + box("moov", mvhd(timescale = 44_100, duration = 44_100L * 120), audio, text, nero)
        val info = read(file)!!
        assertEquals(120_000L, info.durationMs)
        assertEquals(listOf(AudioChapter("Opening", 0), AudioChapter("Глава первая", 60_000), AudioChapter("The end", 90_000)), info.chapters)
    }

    @Test
    fun id3ChaptersAreSortedAndStartAtZero() {
        val frames = id3Frame("TIT2", byteArrayOf(3) + "Book".toByteArray()) +
            id3Frame("APIC", ByteArray(5000)) +
            chap("c2", 600_000, id3Frame("TIT2", byteArrayOf(3) + "Вторая".toByteArray())) +
            chap("c1", 1_000, id3Frame("TIT2", byteArrayOf(1) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "First".toByteArray(Charsets.UTF_16LE))) +
            chap("c3", 1_200_000, ByteArray(0))
        val tag = "ID3".toByteArray() + byteArrayOf(3, 0, 0) + syncsafe(frames.size + 100) + frames + ByteArray(100)
        val info = read(tag + ByteArray(2000))!!
        assertEquals(listOf(AudioChapter("First", 0), AudioChapter("Вторая", 600_000), AudioChapter("3", 1_200_000)), info.chapters)
    }

    @Test
    fun otherFilesHaveNoChapters() {
        assertNull(read("OggS".toByteArray() + ByteArray(100)))
        val noChapters = read(box("ftyp", "M4A ".toByteArray(), u32(0)) + box("moov", mvhd(1000, 5000)))!!
        assertTrue(noChapters.chapters.isEmpty())
        assertEquals(5000L, noChapters.durationMs)
        assertNull(EmbeddedChapters.normalize(listOf(AudioChapter("Only", 0))))
    }

    // ---------------------------------------------------------------- builders

    private fun read(bytes: ByteArray): EmbeddedChapters.Info? {
        val path = Files.createTempFile("chapters", ".bin")
        try {
            Files.write(path, bytes)
            return FileChannel.open(path).use { EmbeddedChapters.read(it) }
        } finally {
            Files.delete(path)
        }
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = cat(*parts)
        return u32(body.size + 8) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private fun fullBox(type: String, version: Int, vararg parts: ByteArray) = box(type, byteArrayOf(version.toByte(), 0, 0, 0), *parts)

    private fun mvhd(timescale: Int, duration: Long) = fullBox("mvhd", 0, u32(0), u32(0), u32(timescale), u32(duration.toInt()), ByteArray(80))

    private fun neroEntry(startMs: Long, title: String): ByteArray {
        val t = title.toByteArray()
        return ByteBuffer.allocate(8).putLong(startMs * 10_000).array() + byteArrayOf(t.size.toByte()) + t
    }

    private fun id3Frame(id: String, body: ByteArray) = id.toByteArray() + u32(body.size) + byteArrayOf(0, 0) + body

    private fun chap(id: String, startMs: Int, sub: ByteArray) =
        id3Frame("CHAP", id.toByteArray() + byteArrayOf(0) + u32(startMs) + u32(startMs + 1000) + u32(-1) + u32(-1) + sub)

    private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7f).toByte(), (n shr 14 and 0x7f).toByte(), (n shr 7 and 0x7f).toByte(), (n and 0x7f).toByte())

    private fun u32(n: Int): ByteArray = ByteBuffer.allocate(4).putInt(n).array()
    private fun u16(n: Int): ByteArray = ByteBuffer.allocate(2).putShort(n.toShort()).array()

    private fun cat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
}

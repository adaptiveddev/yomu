package app.yomu.data.audio

import app.yomu.domain.model.AudioChapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files

class EmbeddedChaptersTest {
    @TempDir
    lateinit var dir: File

    private fun read(bytes: ByteArray): List<AudioChapter> {
        val f = File(dir, "a.bin").apply { writeBytes(bytes) }
        return Files.newByteChannel(f.toPath()).use(EmbeddedChapters::read)
    }

    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).block() }.toByteArray()

    private fun box(type: String, vararg content: ByteArray): ByteArray {
        val body = content.fold(ByteArray(0)) { a, b -> a + b }
        return bytes { writeInt(body.size + 8); writeBytes(type); write(body) }
    }

    private val ftyp = box("ftyp", "M4B ".toByteArray(), bytes { writeInt(0) })

    @Test
    fun neroChapters() {
        val chpl = box(
            "chpl",
            bytes {
                writeInt(0x01000000) // version 1, flags
                writeInt(0)
                writeByte(3)
                listOf(0L to "Intro", 61_500L to "Глава 1", 3_600_000L to "Chapter 2").forEach { (ms, title) ->
                    writeLong(ms * 10_000)
                    val t = title.toByteArray()
                    writeByte(t.size)
                    write(t)
                }
            },
        )
        val file = ftyp + box("moov", box("udta", chpl)) + box("mdat", ByteArray(16))
        assertEquals(
            listOf(AudioChapter("Intro", 0), AudioChapter("Глава 1", 61_500), AudioChapter("Chapter 2", 3_600_000)),
            read(file),
        )
    }

    @Test
    fun quickTimeChapterTrack() {
        val titles = listOf("Opening", "Part One", "Part Two")
        val samples = titles.map { t -> bytes { val b = t.toByteArray(); writeShort(b.size); write(b) } }

        fun moov(mdatStart: Int): ByteArray {
            val audio = box("trak", box("tkhd", bytes { writeInt(0); writeInt(0); writeInt(0); writeInt(1) }), box("tref", box("chap", bytes { writeInt(2) })))
            // Two chunks: the first holds two samples, the second one.
            val stbl = box(
                "stbl",
                box("stts", bytes { writeInt(0); writeInt(2); writeInt(1); writeInt(90_000); writeInt(2); writeInt(120_000) }),
                box("stsz", bytes { writeInt(0); writeInt(0); writeInt(3); samples.forEach { writeInt(it.size) } }),
                box("stsc", bytes { writeInt(0); writeInt(2); writeInt(1); writeInt(2); writeInt(1); writeInt(2); writeInt(1); writeInt(1) }),
                box("stco", bytes { writeInt(0); writeInt(2); writeInt(mdatStart); writeInt(mdatStart + samples[0].size + samples[1].size) }),
            )
            val text = box(
                "trak",
                box("tkhd", bytes { writeInt(0); writeInt(0); writeInt(0); writeInt(2) }),
                box("mdia", box("mdhd", bytes { writeInt(0); writeInt(0); writeInt(0); writeInt(1000); writeInt(0) }), box("minf", stbl)),
            )
            return box("moov", audio, text)
        }

        val size = moov(0).size
        val mdatStart = ftyp.size + size + 8
        val file = ftyp + moov(mdatStart) + box("mdat", *samples.toTypedArray())
        assertEquals(
            listOf(AudioChapter("Opening", 0), AudioChapter("Part One", 90_000), AudioChapter("Part Two", 210_000)),
            read(file),
        )
    }

    private fun frame(id: String, body: ByteArray) = bytes { writeBytes(id); writeInt(body.size); writeShort(0); write(body) }

    private fun chap(element: String, startMs: Int, title: String, utf16: Boolean) = frame(
        "CHAP",
        bytes {
            writeBytes(element); writeByte(0)
            writeInt(startMs); writeInt(-1); writeInt(-1); writeInt(-1)
            write(frame("TIT2", if (utf16) byteArrayOf(1) + title.toByteArray(Charsets.UTF_16) else byteArrayOf(3) + title.toByteArray()))
        },
    )

    @Test
    fun id3ChaptersFollowTheTableOfContents() {
        val frames = chap("c2", 30_000, "Second", utf16 = false) +
            chap("c1", 0, "Первая", utf16 = true) +
            chap("c3", 20_000, "Third", utf16 = false) +
            frame("CTOC", bytes { writeBytes("toc"); writeByte(0); writeByte(0x03); writeByte(3); listOf("c1", "c3", "c2").forEach { writeBytes(it); writeByte(0) } })
        val size = frames.size
        val header = bytes {
            writeBytes("ID3"); writeByte(3); writeByte(0); writeByte(0)
            (3 downTo 0).forEach { writeByte(size shr (it * 7) and 0x7F) }
        }
        val chapters = read(header + frames + ByteArray(32))
        // Chapters come in time order whatever the order of the frames.
        assertEquals(listOf("Первая", "Third", "Second"), chapters.map { it.title })
        assertEquals(listOf(0L, 20_000L, 30_000L), chapters.map { it.startMs })
    }

    @Test
    fun id3ChaptersAfterALargePictureAndBeforeABrokenFrame() {
        // Only frame headers are read past the picture; a frame running past the tag ends the walk without failing.
        val frames = frame("APIC", ByteArray(3 * 1024 * 1024)) +
            chap("a", 0, "One", utf16 = false) + chap("b", 60_000, "Two", utf16 = false) +
            bytes { writeBytes("TXXX"); writeInt(1 shl 30); writeShort(0) }
        val size = frames.size
        val header = bytes {
            writeBytes("ID3"); writeByte(3); writeByte(0); writeByte(0)
            (3 downTo 0).forEach { writeByte(size shr (it * 7) and 0x7F) }
        }
        assertEquals(listOf(AudioChapter("One", 0), AudioChapter("Two", 60_000)), read(header + frames))
    }

    @Test
    fun noChaptersInPlainFiles() {
        assertTrue(read(ByteArray(64)).isEmpty())
        assertTrue(read(ftyp + box("moov", box("udta"))).isEmpty())
        assertTrue(read(ftyp.copyOf(10)).isEmpty())
    }

    @Test
    fun normalizeStartsAtZero() {
        assertEquals(
            listOf(AudioChapter("A", 0), AudioChapter("B", 9_000)),
            EmbeddedChapters.normalize(listOf(AudioChapter("B", 9_000), AudioChapter("A", 1_200), AudioChapter("dup", 9_000))),
        )
        assertTrue(EmbeddedChapters.normalize(listOf(AudioChapter("only", 0))).isEmpty())
    }
}

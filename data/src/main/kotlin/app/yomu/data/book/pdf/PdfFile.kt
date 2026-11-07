package app.yomu.data.book.pdf

import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Random access to the bytes of a PDF (a file, a file descriptor of a document provider, bytes in memory). */
interface PdfBytes {
    val length: Long

    /** Reads up to [len] bytes at [position]; returns how many were read (-1 at the end). */
    fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int
}

/** The first [limit] bytes of [source]: the PDF as it was before later updates were appended. */
class PdfBytesView(private val source: PdfBytes, private val limit: Long) : PdfBytes {
    override val length: Long get() = minOf(limit, source.length)
    override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
        if (position >= length) return -1
        return source.read(position, buffer, offset, minOf(len.toLong(), length - position).toInt())
    }
}

class ByteArrayPdfBytes(private val bytes: ByteArray) : PdfBytes {
    override val length: Long get() = bytes.size.toLong()
    override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(len.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }
}

class RandomAccessPdfBytes(private val file: RandomAccessFile) : PdfBytes {
    override val length: Long get() = file.length()
    override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
        file.seek(position)
        return file.read(buffer, offset, len)
    }
}

/** Where an object lives: at a file offset, or inside an object stream. */
sealed interface XrefEntry {
    data class Offset(val offset: Long, val generation: Int) : XrefEntry
    data class Compressed(val stream: Int, val index: Int) : XrefEntry
    data object Free : XrefEntry
}

/**
 * Read access to the objects of a PDF through its cross-reference sections (tables, streams and hybrid files,
 * object streams, Flate with PNG predictors). Enough to find the pages and their annotations; fails with
 * [PdfFormatException] on files it cannot follow (the caller then leaves the file alone).
 */
class PdfFile(val bytes: PdfBytes) {
    val entries = HashMap<Int, XrefEntry>()
    lateinit var trailer: PdfDictionary
        private set

    /** Offset of the newest cross-reference section (the /Prev of an update). */
    var startXref: Long = -1
        private set

    /** The newest section is a cross-reference stream (an update then writes one too). */
    var usesXrefStream = false
        private set

    private val objectStreams = HashMap<Int, Pair<IntArray, ByteArray>>()
    private val cache = HashMap<Int, PdfObject?>()

    fun load(): PdfFile {
        startXref = findStartXref()
        var offset: Long? = startXref
        val seen = HashSet<Long>()
        var first = true
        while (offset != null && offset >= 0 && seen.add(offset)) {
            val section = readSection(offset)
            if (first) {
                trailer = section.trailer
                usesXrefStream = section.stream
                first = false
            }
            section.entries.forEach { (k, v) -> entries.putIfAbsent(k, v) }
            // Hybrid files: objects in object streams are listed by the stream the table points at.
            (section.trailer["XRefStm"] as? PdfNumber)?.long?.let { xs ->
                if (seen.add(xs)) readSection(xs).entries.forEach { (k, v) -> entries.putIfAbsent(k, v) }
            }
            offset = (section.trailer["Prev"] as? PdfNumber)?.long
        }
        if (first) throw PdfFormatException("No cross-reference section")
        return this
    }

    val encrypted: Boolean get() = trailer["Encrypt"] != null

    /** Size of the newest section: the first free object number. */
    val size: Int get() = maxOf((trailer["Size"] as? PdfNumber)?.int ?: 0, (entries.keys.maxOrNull() ?: 0) + 1)

    fun resolve(obj: PdfObject?): PdfObject? = if (obj is PdfReference) getObject(obj.number) else obj

    fun getObject(number: Int): PdfObject? {
        if (cache.containsKey(number)) return cache[number]
        val value = when (val e = entries[number]) {
            is XrefEntry.Offset -> readIndirect(e.offset, number)
            is XrefEntry.Compressed -> readCompressed(e.stream, e.index, number)
            else -> null
        }
        cache[number] = value
        return value
    }

    fun generation(number: Int): Int = (entries[number] as? XrefEntry.Offset)?.generation ?: 0

    /** References of the pages in reading order. */
    fun pages(): List<PdfReference> {
        val root = resolve(trailer["Root"]) as? PdfDictionary ?: throw PdfFormatException("No document catalog")
        val top = root["Pages"] as? PdfReference ?: throw PdfFormatException("No page tree")
        val out = ArrayList<PdfReference>()
        val seen = HashSet<Int>()
        fun walk(ref: PdfReference, depth: Int) {
            if (depth > MAX_TREE_DEPTH || !seen.add(ref.number)) return
            val node = getObject(ref.number) as? PdfDictionary ?: return
            val kids = resolve(node["Kids"]) as? PdfArray
            if (node.name("Type") == "Pages" || (node.name("Type") != "Page" && kids != null)) {
                kids?.items?.forEach { k -> if (k is PdfReference) walk(k, depth + 1) }
            } else {
                out += ref
            }
        }
        walk(top, 0)
        return out
    }

    /** The decoded data of a stream (Flate, optionally with a PNG or TIFF predictor; no other filters). */
    fun streamData(stream: PdfStream): ByteArray {
        val raw = rawStreamData(stream)
        val filters = when (val f = resolve(stream.dictionary["Filter"])) {
            null -> emptyList()
            is PdfName -> listOf(f.name)
            is PdfArray -> f.items.mapNotNull { (resolve(it) as? PdfName)?.name }
            else -> throw PdfFormatException("Bad filter")
        }
        val params = when (val p = resolve(stream.dictionary["DecodeParms"])) {
            is PdfDictionary -> listOf(p)
            is PdfArray -> p.items.map { resolve(it) as? PdfDictionary }
            else -> emptyList()
        }
        var data = raw
        filters.forEachIndexed { i, f ->
            data = when (f) {
                "FlateDecode", "Fl" -> unpredict(inflate(data), params.getOrNull(i))
                else -> throw PdfFormatException("Unsupported filter $f")
            }
        }
        return data
    }

    private fun rawStreamData(stream: PdfStream): ByteArray {
        val declared = (resolve(stream.dictionary["Length"]) as? PdfNumber)?.long ?: -1
        val total = bytes.length
        if (declared >= 0 && stream.dataStart + declared <= total) {
            val data = readAt(stream.dataStart, declared.toInt())
            // The declared length is right when "endstream" follows (after an end of line).
            val after = readAt(stream.dataStart + declared, minOf(32L, total - stream.dataStart - declared).toInt())
            val text = String(after, Charsets.ISO_8859_1).trimStart()
            if (text.startsWith("endstream")) return data
        }
        // A wrong /Length: the data ends before "endstream".
        val end = find("endstream".toByteArray(Charsets.ISO_8859_1), stream.dataStart, MAX_STREAM_SCAN) ?: throw PdfFormatException("Unterminated stream")
        var len = (end - stream.dataStart).toInt()
        val data = readAt(stream.dataStart, len)
        while (len > 0 && (data[len - 1].toInt() == '\n'.code || data[len - 1].toInt() == '\r'.code)) len--
        return data.copyOf(len)
    }

    // ---- cross-reference sections ----

    private class Section(val entries: Map<Int, XrefEntry>, val trailer: PdfDictionary, val stream: Boolean)

    private fun findStartXref(): Long {
        val total = bytes.length
        val tailSize = minOf(total, TAIL).toInt()
        val tail = String(readAt(total - tailSize, tailSize), Charsets.ISO_8859_1)
        val at = tail.lastIndexOf("startxref")
        if (at < 0) throw PdfFormatException("No startxref")
        val number = tail.substring(at + "startxref".length).trimStart().takeWhile { it.isDigit() }
        return number.toLongOrNull() ?: throw PdfFormatException("Bad startxref")
    }

    private fun readSection(offset: Long): Section = withWindow(offset) { lx ->
        lx.skipSpace()
        if (lx.peekWord() == "xref") readTable(lx) else readXrefStream(offset)
    }

    private fun readTable(lx: PdfLexer): Section {
        lx.expectWord("xref")
        val out = HashMap<Int, XrefEntry>()
        while (true) {
            val w = lx.peekWord() ?: throw PdfFormatException("Bad cross-reference table")
            if (w == "trailer") break
            val start = lx.readWord().toIntOrNull() ?: throw PdfFormatException("Bad subsection")
            val count = lx.readWord().toIntOrNull() ?: throw PdfFormatException("Bad subsection")
            for (i in 0 until count) {
                val off = lx.readWord().toLongOrNull() ?: throw PdfFormatException("Bad entry")
                val gen = lx.readWord().toIntOrNull() ?: throw PdfFormatException("Bad entry")
                val type = lx.readWord()
                val num = start + i
                if (!out.containsKey(num)) out[num] = if (type == "n" && off > 0) XrefEntry.Offset(off, gen) else XrefEntry.Free
            }
        }
        lx.expectWord("trailer")
        val trailer = lx.readObject() as? PdfDictionary ?: throw PdfFormatException("Bad trailer")
        return Section(out, trailer, stream = false)
    }

    private fun readXrefStream(offset: Long): Section {
        val stream = readIndirectRaw(offset).third as? PdfStream ?: throw PdfFormatException("No cross-reference at $offset")
        val d = stream.dictionary
        if (d.name("Type") != "XRef") throw PdfFormatException("No cross-reference stream at $offset")
        val w = (d["W"] as? PdfArray)?.items?.map { (it as PdfNumber).int } ?: throw PdfFormatException("No /W")
        if (w.size < 3) throw PdfFormatException("Bad /W")
        val size = (d["Size"] as? PdfNumber)?.int ?: 0
        val index = (d["Index"] as? PdfArray)?.items?.map { (it as PdfNumber).int } ?: listOf(0, size)
        val data = streamData(stream)
        val row = w.sum()
        val out = HashMap<Int, XrefEntry>()
        var p = 0
        fun field(width: Int, default: Long): Long {
            if (width == 0) return default
            var v = 0L
            repeat(width) { v = (v shl 8) or (data[p++].toLong() and 0xFF) }
            return v
        }
        var i = 0
        while (i + 1 < index.size) {
            val start = index[i]
            val count = index[i + 1]
            for (k in 0 until count) {
                if (p + row > data.size) break
                val type = field(w[0], 1)
                val f2 = field(w[1], 0)
                val f3 = field(w[2], 0)
                val num = start + k
                if (!out.containsKey(num)) {
                    out[num] = when (type) {
                        1L -> XrefEntry.Offset(f2, f3.toInt())
                        2L -> XrefEntry.Compressed(f2.toInt(), f3.toInt())
                        else -> XrefEntry.Free
                    }
                }
            }
            i += 2
        }
        return Section(out, d, stream = true)
    }

    // ---- objects ----

    /** "n g obj …" at [offset]. */
    private fun readIndirectRaw(offset: Long): Triple<Int, Int, PdfObject> = withWindow(offset) { lx ->
        val num = lx.readWord().toIntOrNull() ?: throw PdfFormatException("No object at $offset")
        val gen = lx.readWord().toIntOrNull() ?: throw PdfFormatException("No object at $offset")
        lx.expectWord("obj")
        Triple(num, gen, lx.readObject())
    }

    private fun readIndirect(offset: Long, number: Int): PdfObject? {
        val (num, _, obj) = readIndirectRaw(offset)
        if (num != number) throw PdfFormatException("Object $number expected at $offset, found $num")
        return obj
    }

    private fun readCompressed(streamNumber: Int, index: Int, number: Int): PdfObject? {
        val (offsets, data) = objectStreams.getOrPut(streamNumber) {
            val s = getObject(streamNumber) as? PdfStream ?: throw PdfFormatException("No object stream $streamNumber")
            val n = (resolve(s.dictionary["N"]) as? PdfNumber)?.int ?: 0
            val first = (resolve(s.dictionary["First"]) as? PdfNumber)?.int ?: 0
            val decoded = streamData(s)
            val lx = PdfLexer(decoded)
            val header = IntArray(n * 2)
            for (k in 0 until n * 2) header[k] = lx.readWord().toInt()
            // Offsets are relative to /First.
            for (k in 0 until n) header[k * 2 + 1] += first
            header to decoded
        }
        if (index * 2 + 1 >= offsets.size) return null
        if (offsets[index * 2] != number) {
            // The index of the cross-reference is wrong: look the object up by number.
            val k = (0 until offsets.size / 2).firstOrNull { offsets[it * 2] == number } ?: return null
            return PdfLexer(data, pos = offsets[k * 2 + 1]).readObject()
        }
        return PdfLexer(data, pos = offsets[index * 2 + 1]).readObject()
    }

    /** Runs [block] on a window of the file at [offset], growing the window while an object does not fit. */
    private fun <T> withWindow(offset: Long, block: (PdfLexer) -> T): T {
        val total = bytes.length
        if (offset !in 0 until total) throw PdfFormatException("Offset $offset outside the file")
        var size = WINDOW
        while (true) {
            val n = minOf(size.toLong(), total - offset).toInt()
            val window = readAt(offset, n)
            val atEof = offset + n >= total
            try {
                return block(PdfLexer(window, offset, atEof))
            } catch (e: PdfNeedMoreInput) {
                if (atEof || size >= MAX_WINDOW) throw PdfFormatException("Object at $offset too large")
                size *= 4
            }
        }
    }

    fun readAt(position: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        var done = 0
        while (done < len) {
            val n = bytes.read(position + done, out, done, len - done)
            if (n <= 0) break
            done += n
        }
        return if (done == len) out else out.copyOf(done)
    }

    private fun find(pattern: ByteArray, from: Long, maxScan: Long): Long? {
        val total = bytes.length
        var pos = from
        val chunk = 1 shl 16
        while (pos < total && pos - from < maxScan) {
            val buf = readAt(pos, minOf(chunk.toLong() + pattern.size, total - pos).toInt())
            outer@ for (i in 0..buf.size - pattern.size) {
                for (j in pattern.indices) if (buf[i + j] != pattern[j]) continue@outer
                return pos + i
            }
            pos += chunk
        }
        return null
    }

    private fun inflate(data: ByteArray): ByteArray {
        fun run(nowrap: Boolean): ByteArray {
            val inf = Inflater(nowrap)
            try {
                inf.setInput(data)
                val out = ByteArrayOutputStream(data.size * 3)
                val buf = ByteArray(1 shl 14)
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0) {
                        if (inf.needsInput() || inf.needsDictionary()) break
                    }
                    out.write(buf, 0, n)
                    if (out.size() > MAX_DECODED) throw PdfFormatException("Stream too large")
                }
                return out.toByteArray()
            } finally {
                inf.end()
            }
        }
        return try {
            run(false)
        } catch (e: DataFormatException) {
            try {
                run(true)
            } catch (e2: DataFormatException) {
                throw PdfFormatException("Bad Flate data: ${e2.message}")
            }
        }
    }

    private fun unpredict(data: ByteArray, params: PdfDictionary?): ByteArray {
        val predictor = (params?.get("Predictor") as? PdfNumber)?.int ?: 1
        if (predictor < 2) return data
        val colors = (params?.get("Colors") as? PdfNumber)?.int ?: 1
        val bpc = (params?.get("BitsPerComponent") as? PdfNumber)?.int ?: 8
        val columns = (params?.get("Columns") as? PdfNumber)?.int ?: 1
        val bpp = maxOf(1, colors * bpc / 8)
        val rowLen = (colors * bpc * columns + 7) / 8
        if (predictor == 2) {
            if (bpc != 8) throw PdfFormatException("Unsupported TIFF predictor")
            val out = data.copyOf()
            var r = 0
            while (r + rowLen <= out.size) {
                for (i in bpp until rowLen) out[r + i] = (out[r + i] + out[r + i - bpp]).toByte()
                r += rowLen
            }
            return out
        }
        // PNG predictors: every row starts with its filter type.
        val out = ByteArrayOutputStream(data.size)
        var prev = ByteArray(rowLen)
        var p = 0
        while (p < data.size) {
            val type = data[p].toInt() and 0xFF
            p++
            val row = ByteArray(rowLen)
            val n = minOf(rowLen, data.size - p)
            System.arraycopy(data, p, row, 0, n)
            p += rowLen
            for (i in 0 until rowLen) {
                val a = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val x = row[i].toInt() and 0xFF
                row[i] = when (type) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + (a + b) / 2
                    4 -> {
                        val pa = Math.abs(b - c)
                        val pb = Math.abs(a - c)
                        val pc = Math.abs(a + b - 2 * c)
                        x + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                    else -> throw PdfFormatException("Bad PNG predictor $type")
                }.toByte()
            }
            out.write(row, 0, n)
            prev = row
        }
        return out.toByteArray()
    }

    private companion object {
        const val TAIL = 2048L
        const val WINDOW = 4096
        const val MAX_WINDOW = 64 * 1024 * 1024
        const val MAX_STREAM_SCAN = 256L * 1024 * 1024
        const val MAX_DECODED = 256 * 1024 * 1024
        const val MAX_TREE_DEPTH = 64
    }
}

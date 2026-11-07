package app.yomu.data.book.pdf

import java.io.ByteArrayOutputStream

/**
 * PDF objects (ISO 32000-1, 7.3) as far as the annotation writer needs them. Numbers keep the text they were
 * written with, names and strings their bytes, so that what is read is written back unchanged.
 */
sealed interface PdfObject

data object PdfNull : PdfObject

data class PdfBoolean(val value: Boolean) : PdfObject

data class PdfNumber(val text: String) : PdfObject {
    val double: Double get() = text.toDoubleOrNull() ?: 0.0
    val long: Long get() = text.toLongOrNull() ?: double.toLong()
    val int: Int get() = long.toInt()

    companion object {
        fun of(value: Long) = PdfNumber(value.toString())
        fun of(value: Int) = PdfNumber(value.toString())

        /** A real number with at most 3 decimals and no exponent (PDF has no exponent notation). */
        fun of(value: Float): PdfNumber = PdfNumber(real(value.toDouble()))

        fun real(v: Double): String {
            if (v.isNaN() || v.isInfinite()) return "0"
            val s = String.format(java.util.Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
            return if (s == "-0" || s.isEmpty()) "0" else s
        }
    }
}

/** A name without the slash; every char stands for one byte (Latin-1). */
data class PdfName(val name: String) : PdfObject

class PdfString(val bytes: ByteArray) : PdfObject {
    /** The text of a text string: UTF-16BE with a byte order mark, else PDFDocEncoding (read as Latin-1). */
    fun text(): String = when {
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        else -> String(bytes, Charsets.ISO_8859_1)
    }

    override fun equals(other: Any?): Boolean = other is PdfString && other.bytes.contentEquals(bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = "PdfString(${text()})"

    companion object {
        /** A text string: plain ASCII as it is, anything else as UTF-16BE with a byte order mark. */
        fun text(value: String): PdfString =
            if (value.all { it.code in 0x20..0x7E || it == '\n' || it == '\r' || it == '\t' }) {
                PdfString(value.toByteArray(Charsets.ISO_8859_1))
            } else {
                PdfString(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + value.toByteArray(Charsets.UTF_16BE))
            }
    }
}

class PdfArray(val items: MutableList<PdfObject> = mutableListOf()) : PdfObject {
    operator fun get(i: Int): PdfObject = items[i]
    val size: Int get() = items.size
    override fun toString(): String = items.toString()
}

class PdfDictionary(val entries: LinkedHashMap<String, PdfObject> = LinkedHashMap()) : PdfObject {
    operator fun get(key: String): PdfObject? = entries[key]
    operator fun set(key: String, value: PdfObject) {
        entries[key] = value
    }

    fun name(key: String): String? = (entries[key] as? PdfName)?.name
    fun copy(): PdfDictionary = PdfDictionary(LinkedHashMap(entries))
    override fun toString(): String = entries.toString()
}

data class PdfReference(val number: Int, val generation: Int) : PdfObject

/** A stream: its dictionary and the file offset its data starts at. */
class PdfStream(val dictionary: PdfDictionary, val dataStart: Long) : PdfObject

class PdfFormatException(message: String) : Exception(message)

/** The input ran out in the middle of an object: the caller reads a larger window. */
internal class PdfNeedMoreInput : Exception() {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Reads objects from [buf] (a window of the file starting at file offset [base]). Running past the end of the
 * window throws [PdfNeedMoreInput] unless the window already reaches the end of the file ([atEof]).
 */
internal class PdfLexer(private val buf: ByteArray, private val base: Long = 0, private val atEof: Boolean = true, var pos: Int = 0) {
    val filePosition: Long get() = base + pos

    private fun eof(): Boolean = pos >= buf.size

    private fun need() {
        if (!atEof) throw PdfNeedMoreInput()
        throw PdfFormatException("Unexpected end of the file")
    }

    private fun peek(): Int {
        if (eof()) need()
        return buf[pos].toInt() and 0xFF
    }

    fun skipSpace() {
        while (true) {
            if (eof()) return
            val c = buf[pos].toInt() and 0xFF
            when {
                isWhite(c) -> pos++
                c == '%'.code -> while (!eof() && buf[pos].toInt() != '\n'.code && buf[pos].toInt() != '\r'.code) pos++
                else -> return
            }
        }
    }

    /** The next keyword or number without consuming it (null at a delimiter). */
    fun peekWord(): String? {
        val save = pos
        skipSpace()
        if (eof()) {
            pos = save
            if (!atEof) throw PdfNeedMoreInput()
            return null
        }
        val c = peek()
        if (isDelimiter(c)) {
            pos = save
            return null
        }
        val w = readWord()
        pos = save
        return w
    }

    fun readWord(): String {
        skipSpace()
        val start = pos
        while (true) {
            if (eof()) {
                if (!atEof) throw PdfNeedMoreInput()
                break
            }
            val c = buf[pos].toInt() and 0xFF
            if (isWhite(c) || isDelimiter(c)) break
            pos++
        }
        if (pos == start) throw PdfFormatException("Expected a keyword at ${base + pos}")
        return String(buf, start, pos - start, Charsets.ISO_8859_1)
    }

    fun expectWord(word: String) {
        val w = readWord()
        if (w != word) throw PdfFormatException("Expected '$word', found '$w' at ${base + pos}")
    }

    /** One object; "n g R" becomes a [PdfReference]. A dictionary followed by "stream" becomes a [PdfStream]. */
    fun readObject(depth: Int = 0): PdfObject {
        if (depth > MAX_DEPTH) throw PdfFormatException("Objects nested too deep")
        skipSpace()
        if (eof()) need()
        val c = peek()
        return when {
            c == '/'.code -> readName()
            c == '('.code -> readLiteral()
            c == '['.code -> {
                pos++
                val items = mutableListOf<PdfObject>()
                while (true) {
                    skipSpace()
                    if (eof()) need()
                    if (peek() == ']'.code) {
                        pos++
                        break
                    }
                    items += readObject(depth + 1)
                }
                PdfArray(items)
            }
            c == '<'.code -> {
                if (pos + 1 >= buf.size) need()
                if (buf[pos + 1].toInt() == '<'.code) readDictionaryOrStream(depth) else readHex()
            }
            c == ')'.code || c == ']'.code || c == '>'.code || c == '{'.code || c == '}'.code -> throw PdfFormatException("Unexpected '${c.toChar()}' at ${base + pos}")
            else -> {
                val w = readWord()
                when {
                    w == "true" -> PdfBoolean(true)
                    w == "false" -> PdfBoolean(false)
                    w == "null" -> PdfNull
                    isNumber(w) -> referenceOrNumber(w)
                    else -> throw PdfFormatException("Unexpected '$w' at ${base + pos}")
                }
            }
        }
    }

    private fun referenceOrNumber(first: String): PdfObject {
        if (first.any { !it.isDigit() }) return PdfNumber(first)
        val save = pos
        val second = runCatching { peekWord() }.getOrElse { if (it is PdfNeedMoreInput) throw it else null }
        if (second != null && second.all { it.isDigit() }) {
            readWord()
            val third = peekWord()
            if (third == "R") {
                readWord()
                return PdfReference(first.toInt(), second.toInt())
            }
        }
        pos = save
        return PdfNumber(first)
    }

    private fun readDictionaryOrStream(depth: Int): PdfObject {
        pos += 2
        val map = LinkedHashMap<String, PdfObject>()
        while (true) {
            skipSpace()
            if (eof()) need()
            if (peek() == '>'.code) {
                if (pos + 1 >= buf.size) need()
                pos += 2
                break
            }
            val key = readObject(depth + 1) as? PdfName ?: throw PdfFormatException("Dictionary key expected at ${base + pos}")
            skipSpace()
            if (eof()) need()
            // A key without a value (seen in damaged files) right before the end of the dictionary.
            if (peek() == '>'.code) {
                map[key.name] = PdfNull
                continue
            }
            map[key.name] = readObject(depth + 1)
        }
        val dict = PdfDictionary(map)
        val save = pos
        val next = peekWord()
        if (next == "stream") {
            skipSpace()
            pos += "stream".length
            // "stream" is followed by CRLF or LF (a lone CR is tolerated).
            if (pos < buf.size && buf[pos].toInt() == '\r'.code) pos++
            if (pos < buf.size && buf[pos].toInt() == '\n'.code) pos++
            return PdfStream(dict, base + pos)
        }
        pos = save
        return dict
    }

    private fun readName(): PdfName {
        pos++
        val out = StringBuilder()
        while (true) {
            if (eof()) {
                if (!atEof) throw PdfNeedMoreInput()
                break
            }
            val c = buf[pos].toInt() and 0xFF
            if (isWhite(c) || isDelimiter(c)) break
            if (c == '#'.code && pos + 2 < buf.size && hex(buf[pos + 1]) >= 0 && hex(buf[pos + 2]) >= 0) {
                out.append(((hex(buf[pos + 1]) shl 4) or hex(buf[pos + 2])).toChar())
                pos += 3
            } else {
                out.append(c.toChar())
                pos++
            }
        }
        return PdfName(out.toString())
    }

    private fun readLiteral(): PdfString {
        pos++
        val out = ByteArrayOutputStream()
        var nesting = 1
        while (true) {
            if (eof()) need()
            val c = buf[pos++].toInt() and 0xFF
            when (c) {
                '('.code -> {
                    nesting++
                    out.write(c)
                }
                ')'.code -> {
                    nesting--
                    if (nesting == 0) return PdfString(out.toByteArray())
                    out.write(c)
                }
                '\\'.code -> {
                    if (eof()) need()
                    val e = buf[pos++].toInt() and 0xFF
                    when (e) {
                        'n'.code -> out.write('\n'.code)
                        'r'.code -> out.write('\r'.code)
                        't'.code -> out.write('\t'.code)
                        'b'.code -> out.write(8)
                        'f'.code -> out.write(12)
                        '\r'.code -> if (pos < buf.size && buf[pos].toInt() == '\n'.code) pos++
                        '\n'.code -> Unit
                        in '0'.code..'7'.code -> {
                            var v = e - '0'.code
                            var n = 1
                            while (n < 3 && pos < buf.size && buf[pos].toInt() in '0'.code..'7'.code) {
                                v = v * 8 + (buf[pos].toInt() - '0'.code)
                                pos++
                                n++
                            }
                            out.write(v and 0xFF)
                        }
                        else -> out.write(e)
                    }
                }
                else -> out.write(c)
            }
        }
    }

    private fun readHex(): PdfString {
        pos++
        val out = ByteArrayOutputStream()
        var hi = -1
        while (true) {
            if (eof()) need()
            val b = buf[pos++]
            if (b.toInt() == '>'.code) break
            val v = hex(b)
            if (v < 0) continue
            if (hi < 0) {
                hi = v
            } else {
                out.write((hi shl 4) or v)
                hi = -1
            }
        }
        if (hi >= 0) out.write(hi shl 4)
        return PdfString(out.toByteArray())
    }

    companion object {
        private const val MAX_DEPTH = 64

        fun isWhite(c: Int): Boolean = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
        fun isDelimiter(c: Int): Boolean = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code || c == '['.code ||
            c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

        fun isNumber(w: String): Boolean {
            if (w.isEmpty()) return false
            var digits = 0
            var dots = 0
            w.forEachIndexed { i, ch ->
                when {
                    ch.isDigit() -> digits++
                    ch == '.' -> dots++
                    (ch == '-' || ch == '+') && i == 0 -> Unit
                    else -> return false
                }
            }
            return digits > 0 && dots <= 1
        }

        fun hex(b: Byte): Int = when (val c = b.toInt()) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }
    }
}

/** Writes objects in the PDF syntax. */
internal object PdfWriter {
    fun write(obj: PdfObject, out: ByteArrayOutputStream) {
        when (obj) {
            PdfNull -> out.ascii("null")
            is PdfBoolean -> out.ascii(if (obj.value) "true" else "false")
            is PdfNumber -> out.ascii(obj.text)
            is PdfName -> name(obj.name, out)
            is PdfString -> string(obj.bytes, out)
            is PdfReference -> out.ascii("${obj.number} ${obj.generation} R")
            is PdfArray -> {
                out.write('['.code)
                obj.items.forEachIndexed { i, item ->
                    if (i > 0) out.write(' '.code)
                    write(item, out)
                }
                out.write(']'.code)
            }
            is PdfDictionary -> {
                out.ascii("<<")
                obj.entries.forEach { (k, v) ->
                    name(k, out)
                    out.write(' '.code)
                    write(v, out)
                    out.write(' '.code)
                }
                out.ascii(">>")
            }
            is PdfStream -> throw IllegalArgumentException("Streams are written with their data")
        }
    }

    fun bytes(obj: PdfObject): ByteArray = ByteArrayOutputStream().also { write(obj, it) }.toByteArray()

    private fun name(name: String, out: ByteArrayOutputStream) {
        out.write('/'.code)
        name.forEach { ch ->
            val c = ch.code and 0xFF
            if (c < 0x21 || c > 0x7E || c == '#'.code || PdfLexer.isDelimiter(c)) out.ascii("#%02X".format(c)) else out.write(c)
        }
    }

    private fun string(bytes: ByteArray, out: ByteArrayOutputStream) {
        val printable = bytes.all { val c = it.toInt() and 0xFF; c in 0x20..0x7E || c == 10 || c == 13 || c == 9 }
        if (!printable) {
            out.write('<'.code)
            bytes.forEach { out.ascii("%02X".format(it.toInt() and 0xFF)) }
            out.write('>'.code)
            return
        }
        out.write('('.code)
        bytes.forEach { b ->
            when (val c = b.toInt() and 0xFF) {
                '('.code, ')'.code, '\\'.code -> {
                    out.write('\\'.code)
                    out.write(c)
                }
                10 -> out.ascii("\\n")
                13 -> out.ascii("\\r")
                9 -> out.ascii("\\t")
                else -> out.write(c)
            }
        }
        out.write(')'.code)
    }
}

internal fun ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))

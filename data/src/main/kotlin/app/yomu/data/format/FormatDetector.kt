package app.yomu.data.format

import app.yomu.domain.model.BookFormat

/** Detects a document format by magic bytes, falling back to the file extension. */
object FormatDetector {
    const val HEADER_SIZE = 4096

    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif", "jxl")

    fun isImage(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in imageExtensions &&
        !name.substringAfterLast('/').startsWith(".")

    fun extension(name: String?): String {
        val n = name?.lowercase() ?: return ""
        if (n.endsWith(".fb2.zip")) return "fb2.zip"
        return n.substringAfterLast('.', "")
    }

    fun detect(header: ByteArray, name: String?): BookFormat {
        val ext = extension(name)
        fun at(offset: Int, text: String): Boolean {
            if (header.size < offset + text.length) return false
            return text.indices.all { header[offset + it] == text[it].code.toByte() }
        }
        val isZip = header.size >= 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
            header[2] == 3.toByte() && header[3] == 4.toByte()
        if (isZip) {
            return when {
                at(30, "mimetype") && at(38, "application/epub+zip") -> BookFormat.EPUB
                ext == "epub" -> BookFormat.EPUB
                ext == "docx" -> BookFormat.DOCX
                ext == "fb2.zip" -> BookFormat.FB2
                at(30, "[Content_Types].xml") -> BookFormat.DOCX
                else -> BookFormat.CBZ
            }
        }
        if (at(0, "Rar!")) return BookFormat.CBR
        if (header.size >= 6 && header[0] == 0x37.toByte() && header[1] == 0x7A.toByte() && header[2] == 0xBC.toByte() &&
            header[3] == 0xAF.toByte() && header[4] == 0x27.toByte() && header[5] == 0x1C.toByte()
        ) {
            return BookFormat.CB7
        }
        if (at(60, "BOOKMOBI") || at(60, "TEXtREAd")) return BookFormat.MOBI
        val head = String(header, 0, minOf(header.size, 1024), Charsets.ISO_8859_1)
        if (head.contains("%PDF")) return BookFormat.PDF
        val lower = head.lowercase()
        if (lower.contains("<fictionbook")) return BookFormat.FB2
        if (lower.contains("<html") || lower.contains("<!doctype html")) return BookFormat.HTML
        BookFormat.entries.firstOrNull { ext in it.extensions && it != BookFormat.CBZ }?.let { return it }
        return if (looksLikeText(header)) BookFormat.TXT else BookFormat.UNKNOWN
    }

    fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))) return true
        val sample = bytes.take(2048)
        val control = sample.count { val b = it.toInt() and 0xFF; b < 0x09 || (b in 0x0E..0x1F) }
        return control * 100 / sample.size < 2
    }
}

/** Natural ordering: "page2" < "page10". */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

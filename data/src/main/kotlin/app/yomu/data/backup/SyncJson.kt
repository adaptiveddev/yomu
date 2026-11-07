package app.yomu.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/** Reject duplicate object members before a parser can silently keep only the last value. */
internal object SyncJson {
    private val parser = Json
    fun text(bytes: ByteArray): String = try {
        bytes.decodeToString(throwOnInvalidSequence = true).also(::rejectDuplicateKeys)
    } catch (e: Exception) { throw IOException("Invalid or ambiguous JSON; no data was applied", e) }

    private fun rejectDuplicateKeys(text: String) {
        // Full syntax remains the responsibility of kotlinx.serialization. This scan skips
        // string contents, tracks each object separately and decodes escaped property names.
        val stack = ArrayList<MutableSet<String>?>()
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '{' -> { stack.add(HashSet()); require(stack.size <= 128); i++ }
                '[' -> { stack.add(null); require(stack.size <= 128); i++ }
                '}', ']' -> { require(stack.isNotEmpty()); stack.removeAt(stack.lastIndex); i++ }
                '"' -> {
                    val start = i++
                    var closed = false
                    while (i < text.length) {
                        when (text[i]) {
                            '\\' -> { require(i + 1 < text.length); i += 2 }
                            '"' -> { i++; closed = true; break }
                            else -> i++
                        }
                    }
                    require(closed)
                    var next = i
                    while (next < text.length && text[next] in " \t\r\n") next++
                    if (next < text.length && text[next] == ':') {
                        val keys = stack.lastOrNull() ?: throw IllegalArgumentException("Object key outside an object")
                        val name = parser.parseToJsonElement(text.substring(start, i)).jsonPrimitive.content
                        require(keys.add(name)) { "Duplicate JSON object key" }
                    }
                }
                else -> i++
            }
        }
        require(stack.isEmpty())
    }
}

package app.yomu.sources.novels

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Entities

/**
 * HTML from a ProseMirror (TipTap) document, the format of chapter texts and descriptions on the Lib sites.
 * [image] turns the name of an attached image into its address (null drops the image).
 */
object ProseMirror {
    fun toHtml(doc: JsonElement, image: (String) -> String? = { null }): String = StringBuilder().also { node(doc, it, image) }.toString()

    /** Plain text of a document (for descriptions). */
    fun toText(doc: JsonElement): String = StringBuilder().also { text(doc, it) }.toString().trim()

    private fun node(e: JsonElement, out: StringBuilder, image: (String) -> String?) {
        if (e is JsonArray) return e.forEach { node(it, out, image) }
        val o = e as? JsonObject ?: return
        val type = o["type"]?.jsonPrimitive?.contentOrNull
        val children = o["content"] as? JsonArray
        fun wrap(tag: String) {
            out.append('<').append(tag).append('>')
            children?.forEach { node(it, out, image) }
            out.append("</").append(tag).append('>')
        }
        when (type) {
            "doc" -> children?.forEach { node(it, out, image) }
            "paragraph" -> if (children.isNullOrEmpty()) out.append("<p>&nbsp;</p>") else wrap("p")
            "heading" -> {
                val level = (o["attrs"] as? JsonObject)?.get("level")?.jsonPrimitive?.intOrNull?.coerceIn(1, 6) ?: 3
                wrap("h$level")
            }
            "blockquote" -> wrap("blockquote")
            "bulletList" -> wrap("ul")
            "orderedList" -> wrap("ol")
            "listItem" -> wrap("li")
            "horizontalRule" -> out.append("<hr/>")
            "hardBreak" -> out.append("<br/>")
            "image" -> {
                val attrs = o["attrs"] as? JsonObject
                val names = (attrs?.get("images") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("image")?.jsonPrimitive?.contentOrNull }
                    ?: listOfNotNull(attrs?.get("src")?.jsonPrimitive?.contentOrNull)
                names.mapNotNull(image).forEach { src -> out.append("<p><img src=\"").append(Entities.escape(src)).append("\" alt=\"\"/></p>") }
            }
            "text" -> {
                val marks = (o["marks"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull }.orEmpty()
                val tags = marks.mapNotNull { MARKS[it] }
                tags.forEach { out.append('<').append(it).append('>') }
                out.append(Entities.escape(o["text"]?.jsonPrimitive?.contentOrNull.orEmpty()))
                tags.asReversed().forEach { out.append("</").append(it).append('>') }
            }
            else -> children?.forEach { node(it, out, image) }
        }
    }

    private fun text(e: JsonElement, out: StringBuilder) {
        when (e) {
            is JsonArray -> e.forEach { text(it, out) }
            is JsonPrimitive -> out.append(e.contentOrNull.orEmpty())
            is JsonObject -> {
                when (e["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> out.append(e["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
                    "hardBreak" -> out.append('\n')
                    else -> e["content"]?.let { text(it, out) }
                }
                if (e["type"]?.jsonPrimitive?.contentOrNull in BLOCK_TYPES) out.append("\n\n")
            }
        }
    }

    private val MARKS = mapOf("bold" to "b", "italic" to "i", "underline" to "u", "strike" to "s", "code" to "code", "superscript" to "sup", "subscript" to "sub")
    private val BLOCK_TYPES = setOf("paragraph", "heading", "blockquote", "listItem")

    /** A JSON value that is either a document or an HTML/plain string. */
    fun isDocument(e: JsonElement?): Boolean = e is JsonObject && e.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "doc"

    internal fun JsonElement?.arrayOrEmpty(): List<JsonElement> = (this as? JsonArray)?.jsonArray.orEmpty()
}

package app.yomu.domain.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextToolsTest {
    @Test
    fun `splits sentences`() {
        assertEquals(listOf("Hello world.", "How are you?", "«Fine!»", "She left..."), TextTools.sentences("Hello world. How are you? «Fine!» She left..."))
        assertEquals(listOf("今日は。", "「元気？」", "はい。"), TextTools.sentences("今日は。「元気？」はい。"))
    }

    @Test
    fun `finds the sentence around an index`() {
        val text = "First one. Second one! Third"
        assertEquals("Second one!", TextTools.sentenceAt(text, text.indexOf("cond")))
        assertEquals("Third", TextTools.sentenceAt(text, text.length + 10))
        assertEquals("“Really?!”", TextTools.sentenceAt("He paused. “Really?!” Yes.", 14))
        assertEquals("\"Really?\"", TextTools.sentenceAt("He paused.\" \"Really?\" Yes.", 15))
        assertEquals("", TextTools.sentenceAt("", 3))
    }

    @Test
    fun `word helpers`() {
        assertTrue(TextTools.isSingleWord(" word "))
        assertFalse(TextTools.isSingleWord("two words"))
        assertEquals("a b c", TextTools.cleanSelection(" a\n b\t\tc "))
        assertTrue(TextTools.isCjk('漢'))
        assertFalse(TextTools.isCjk('a'))
        assertEquals("https://x.test/?q=a+b%26c&l=ru", TextTools.expandTemplate("https://x.test/?q={text}&l={lang}", "a b&c", "ru"))
    }

    @Test
    fun `plain search escapes regex characters`() {
        val p = SearchReplace.pattern("a.b", regex = false, caseSensitive = false)
        assertEquals(2, SearchReplace.find("a.b axb A.B", p).size)
    }

    @Test
    fun `whole word matching works for cyrillic`() {
        val p = SearchReplace.pattern("кот", regex = false, caseSensitive = false, wholeWord = true)
        assertEquals(listOf(0, 15), SearchReplace.find("Кот и котёнок, кот.", p).map { it.start })
    }

    @Test
    fun `replace counts and supports groups`() {
        val p = SearchReplace.pattern("(\\d+)-(\\d+)", regex = true, caseSensitive = true)
        val (text, n) = SearchReplace.replaceAll("1-2 and 30-40", p, "$2–$1", regex = true)
        assertEquals("2–1 and 40–30", text)
        assertEquals(2, n)
        val (plain, m) = SearchReplace.replaceAll("x $1 x", SearchReplace.pattern("x", false, true), "$1", regex = false)
        assertEquals("$1 $1 $1", plain)
        assertEquals(2, m)
    }

    @Test
    fun `typography fixes`() {
        assertEquals("a b", Typography.collapseSpaces("a  \u00A0 b"))
        assertEquals("— Hello — he said", Typography.fixDashes("- Hello -- he said"))
        assertEquals("«Привет», — сказал он", Typography.fixQuotes("\"Привет\", — сказал он", "ru"))
        assertEquals("“Hi”", Typography.fixQuotes("\"Hi\"", "en"))
        assertEquals(listOf("This line was broken in the middle.", "New paragraph"), Typography.unwrapLines(listOf("This line was", "broken in the middle.", "New paragraph")))
    }

    @Test
    fun `hashtags are extracted from notes`() {
        assertEquals(listOf("idea", "грамматика", "to_read"), TextTools.hashtags("# Heading\nNice #idea, see #Грамматика and #to_read. Again #idea"))
        assertEquals(emptyList<String>(), TextTools.hashtags("Colour &#35; and url http://x.org/#anchor"))
        assertEquals(emptyList<String>(), TextTools.hashtags(null))
    }
}

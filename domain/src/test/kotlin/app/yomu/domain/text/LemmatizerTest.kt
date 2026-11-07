package app.yomu.domain.text

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LemmatizerTest {
    private fun assertCandidate(word: String, lang: String?, expected: String) {
        val c = Lemmatizer.candidates(word, lang)
        assertTrue(expected in c, "$expected not in $c")
    }

    @Test
    fun `normalize lowercases and strips accents and punctuation`() {
        assertEquals("елка", Lemmatizer.normalize("Ёлка́!"))
        assertEquals("don't", Lemmatizer.normalize("“Don’t”"))
        assertEquals("食べる", Lemmatizer.normalize("食べる"))
        assertEquals("が", Lemmatizer.normalize("が"))
    }

    @Test
    fun `original word comes first`() {
        assertEquals("cities", Lemmatizer.candidates("Cities", "en").first())
    }

    @Test
    fun `english inflections`() {
        assertCandidate("cities", "en", "city")
        assertCandidate("running", "en", "run")
        assertCandidate("making", "en", "make")
        assertCandidate("stopped", "en", "stop")
        assertCandidate("wolves", "en", "wolf")
        assertCandidate("went", "en", "go")
        assertCandidate("children", "en", "child")
        assertCandidate("happier", "en", "happy")
        assertCandidate("John's", "en", "john")
    }

    @Test
    fun `russian inflections`() {
        assertCandidate("книгами", "ru", "книга")
        assertCandidate("читаешь", "ru", "читать")
        assertCandidate("красивого", "ru", "красивый")
        assertCandidate("улыбнулась", null, "улыбнуться")
        assertEquals("ru", Lemmatizer.guessLang("слово"))
    }

    @Test
    fun `japanese deinflection`() {
        assertCandidate("食べました", "ja", "食べる")
        assertCandidate("書いた", "ja", "書く")
        assertCandidate("高くない", "ja", "高い")
        assertCandidate("読んで", null, "読む")
        assertEquals(listOf("abc", "ab", "a"), JapaneseDeinflector.prefixes("abc"))
    }

    @Test
    fun `empty input gives no candidates`() {
        assertTrue(Lemmatizer.candidates("  ...  ", "en").isEmpty())
    }
}

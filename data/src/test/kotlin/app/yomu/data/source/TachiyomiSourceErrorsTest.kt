package app.yomu.data.source

import app.yomu.core.common.AppError
import app.yomu.data.source.tachiyomi.TachiyomiSource
import app.yomu.source.SourceException
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.MangasPage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Failures of extension code must reach the screens as explained errors, never as a crash of the app. */
class TachiyomiSourceErrorsTest {
    private class Failing(private val error: Throwable) : CatalogueSource {
        override val id: Long = 42L
        override val name: String = "Failing"
        override val lang: String = "en"
        override val supportsLatest: Boolean = true

        override suspend fun getPopularManga(page: Int): MangasPage = throw error
    }

    private fun failure(error: Throwable): SourceException {
        val source = TachiyomiSource(Failing(error), "test.failing")
        return assertThrows(SourceException::class.java) { runBlocking { source.getPopular(1) } }
    }

    @Test
    fun `missing classes or methods become an outdated source error`() {
        val e = failure(NoSuchMethodError("okhttp3.Headers.get"))
        assertTrue(e.cause is NoSuchMethodError)
        assertTrue(AppError.from(e) is AppError.Incompatible)
        assertTrue(AppError.from(failure(NoClassDefFoundError("eu/kanade/tachiyomi/Foo"))) is AppError.Incompatible)
        assertTrue(AppError.from(failure(AbstractMethodError("getMangaUrl"))) is AppError.Incompatible)
    }

    @Test
    fun `TODO in an extension is reported as not supported`() {
        val e = failure(NotImplementedError())
        assertEquals("Not supported by Failing", e.message)
    }

    @Test
    fun `site errors keep the message of the extension`() {
        assertEquals("Login required", failure(IllegalStateException("Login required")).message)
    }

    @Test
    fun `deep recursion of a parser does not escape`() {
        assertTrue(failure(StackOverflowError()).cause is StackOverflowError)
    }
}

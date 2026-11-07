package app.yomu.data.settings

import android.content.Context
import app.yomu.core.datastore.AppSettings
import app.yomu.domain.model.MangaDirection
import app.yomu.domain.model.MangaSettings
import app.yomu.domain.model.ScaleType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 1.2.0 reads manga left to right by default; the right-to-left default stored by 1.1.0 is switched once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsMigrationTest {
    @Test
    fun mangaDirectionMigratesOnceAndAfterOldBackups() = runBlocking {
        val settings = AppSettings(RuntimeEnvironment.getApplication() as Context)
        settings.resetAll()

        // Fresh install: nothing stored, the default is left to right.
        settings.migrate()
        assertEquals(MangaDirection.LTR, settings.mangaSettings.get().direction)

        // Settings written by 1.1.0 (the old default saved together with another change).
        settings.resetAll()
        settings.mangaSettings.set(MangaSettings(direction = MangaDirection.RTL, scale = ScaleType.FIT_WIDTH))
        settings.migrate()
        assertEquals(MangaDirection.LTR, settings.mangaSettings.get().direction)
        assertEquals(ScaleType.FIT_WIDTH, settings.mangaSettings.get().scale)

        // Choosing right to left afterwards is respected.
        settings.mangaSettings.update { it.copy(direction = MangaDirection.RTL) }
        settings.migrate()
        assertEquals(MangaDirection.RTL, settings.mangaSettings.get().direction)

        // Restoring a 1.1.0 backup (no migration level inside) upgrades its values too.
        val old = JsonObject(
            mapOf("manga_settings" to JsonObject(mapOf("t" to JsonPrimitive("s"), "v" to JsonPrimitive("""{"direction":"RTL","preload":5}""")))),
        )
        settings.importJson(old)
        assertEquals(MangaDirection.LTR, settings.mangaSettings.get().direction)
        assertEquals(5, settings.mangaSettings.get().preload)
        settings.resetAll()
    }
}

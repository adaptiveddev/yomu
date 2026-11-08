package app.yomu

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.yomu.core.datastore.AppSettings
import app.yomu.feature.settings.AppUpdates
import app.yomu.feature.settings.NoteBlock
import app.yomu.feature.settings.ReleaseInfo
import app.yomu.feature.settings.ReleaseNotes
import app.yomu.feature.settings.UpdateChecker
import app.yomu.feature.settings.UpdateChecksumException
import app.yomu.feature.settings.UpdateDownloader
import app.yomu.feature.settings.UpdateState
import app.yomu.ui.WHATS_NEW
import app.yomu.ui.WhatsNew
import app.yomu.ui.whatsNewSince
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** 1.7: launcher icons, the in-app updater (check, download, checksum, installer) and "What's new". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Release170LogicTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val settings: AppSettings get() = GlobalContext.get().get()

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun checksumsAreReadLikeSha256sumWritesThem() {
        val a = "a".repeat(64)
        val b = "B".repeat(64)
        val sums = UpdateChecker.parseChecksums(
            """
            $a  yomu-v1.7.0-arm64-v8a.apk
            $b *dist/yomu-v1.7.0-x86_64.apk
            not a checksum line
            ${"c".repeat(63)}  too-short.apk
            """.trimIndent(),
        )
        assertEquals(mapOf("yomu-v1.7.0-arm64-v8a.apk" to a, "yomu-v1.7.0-x86_64.apk" to b.lowercase(Locale.ROOT)), sums)
    }

    @Test
    fun releaseCarriesSizeChecksumsAndNotes() {
        val base = "https://github.com/adxptived/yomu/releases/download/v1.7.0"
        val json = """
            {"tag_name":"v1.7.0","html_url":"https://github.com/adxptived/yomu/releases/tag/v1.7.0","body":"### Значки\n\n- Семь значков",
             "assets":[
              {"name":"SHA256SUMS.txt","size":180,"browser_download_url":"$base/SHA256SUMS.txt"},
              {"name":"yomu-v1.7.0-x86_64.apk","size":71000000,"browser_download_url":"$base/yomu-v1.7.0-x86_64.apk"},
              {"name":"yomu-v1.7.0-arm64-v8a.apk","size":69000000,"browser_download_url":"$base/yomu-v1.7.0-arm64-v8a.apk"},
              {"name":"mapping.txt","size":5,"browser_download_url":"http://example.com/mapping.txt"}
            ]}
        """.trimIndent()
        val arm = UpdateChecker.parse(json, abis = listOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("1.7.0", arm.version)
        assertEquals("$base/yomu-v1.7.0-arm64-v8a.apk", arm.apkUrl)
        assertEquals("yomu-v1.7.0-arm64-v8a.apk", arm.apkName)
        assertEquals(69_000_000L, arm.apkSize)
        assertEquals("$base/SHA256SUMS.txt", arm.checksumsUrl)
        assertEquals("### Значки\n\n- Семь значков", arm.notes)
        // An x86 phone must not get the x86_64 file just because its name contains "x86".
        assertNull(UpdateChecker.parse(json, abis = listOf("x86")).apkUrl)
        assertEquals(71_000_000L, UpdateChecker.parse(json, abis = listOf("x86_64", "x86")).apkSize)
    }

    @Test
    fun releaseNotesBecomePlainParagraphs() {
        val md = """
            > **Установка.** Ставится поверх 1.6.0, если подписана тем же ключом
            > (см. [docs/RELEASING.md](docs/RELEASING.md)).

            ### Значок приложения

            - **Семь значков** на выбор: «Настройки → Оформление».
              Значок меняется, когда вы выходите из приложения.
            - Тематические значки на `Android 13`.
              - вложенный пункт

            ---

            ### Установка / Install

            - `yomu-v1.7.0-arm64-v8a.apk` — телефоны и планшеты
        """.trimIndent()
        assertEquals(
            listOf(
                NoteBlock("Установка. Ставится поверх 1.6.0, если подписана тем же ключом (см. docs/RELEASING.md).", NoteBlock.Kind.NOTE),
                NoteBlock("Значок приложения", NoteBlock.Kind.HEADING),
                NoteBlock("Семь значков на выбор: «Настройки → Оформление». Значок меняется, когда вы выходите из приложения.", NoteBlock.Kind.BULLET),
                NoteBlock("Тематические значки на Android 13.", NoteBlock.Kind.BULLET),
                NoteBlock("вложенный пункт", NoteBlock.Kind.BULLET),
            ),
            ReleaseNotes.blocks(md),
        )
        assertTrue(ReleaseNotes.blocks("").isEmpty())
        // A line without ">" right after a quote still belongs to it; a blank line ends it.
        assertEquals(
            listOf(NoteBlock("Важно. Сначала резервная копия.", NoteBlock.Kind.NOTE), NoteBlock("Текст.", NoteBlock.Kind.TEXT)),
            ReleaseNotes.blocks("> **Важно.** Сначала\nрезервная копия.\n\nТекст."),
        )
    }

    @Test
    fun whatsNewShowsTheReleasesSinceTheLastSeen() {
        val all = listOf(WhatsNew(11, "1.9.0", 0), WhatsNew(10, "1.8.0", 0), WhatsNew(9, "1.7.0", 0))
        assertEquals(listOf("1.9.0", "1.8.0"), whatsNewSince(9, 11, all).map { it.version })
        assertTrue(whatsNewSince(11, 11, all).isEmpty())
        // From 1.6.0 or older (nothing remembered): only this release, not a history the user may already know.
        assertEquals(listOf("1.9.0"), whatsNewSince(0, 11, all).map { it.version })
        assertTrue(whatsNewSince(0, 12, all).isEmpty())
    }

    /** A release that forgets its entry (docs/RELEASING.md) fails here rather than showing an old list. */
    @Test
    fun whatsNewHasThisVersionInBothLanguages() {
        val newest = WHATS_NEW.first()
        assertEquals(BuildConfig.VERSION_CODE.toLong(), newest.versionCode)
        assertEquals(BuildConfig.VERSION_NAME.substringBefore('-'), newest.version)
        assertEquals(WHATS_NEW.sortedByDescending { it.versionCode }, WHATS_NEW)
        for (tag in listOf("en", "ru")) {
            val res = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }).resources
            WHATS_NEW.forEach { assertTrue("${it.version} ($tag)", res.getStringArray(it.items).isNotEmpty()) }
        }
    }

    @Test
    fun launcherIconSwitchesWhenTheAppIsLeft() {
        val pm = context.packageManager
        fun state(alias: String) = pm.getComponentEnabledSetting(ComponentName(context, alias))
        val icons = AppLauncherIcons(context)
        assertEquals(listOf("classic", "spark", "ribbon", "fox", "hanko", "gaze", "manga"), icons.icons.map { it.id })
        assertEquals("classic", icons.current.value)

        icons.select("fox")
        assertEquals("fox", icons.current.value)
        // Nothing changes while the app is on screen.
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state("app.yomu.icon.Fox"))
        icons.apply()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state("app.yomu.icon.Fox"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state("app.yomu.MainActivity"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state("app.yomu.icon.Spark"))
        // The next start reads the choice back from the package manager.
        assertEquals("fox", AppLauncherIcons(context).current.value)

        icons.select("unknown")
        assertEquals("fox", icons.current.value)
        icons.select("classic")
        icons.apply()
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state("app.yomu.MainActivity"))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state("app.yomu.icon.Fox"))
        assertEquals("classic", AppLauncherIcons(context).current.value)
    }

    @Test
    fun dailyCheckOnlyWhenTurnedOnAndDue() = runBlocking {
        val calls = AtomicInteger()
        val release = ReleaseInfo("9.9.0", "https://example.com/r", "https://example.com/yomu-v9.9.0-arm64-v8a.apk")
        val updates = AppUpdates(context, settings, fetch = { calls.incrementAndGet(); release })

        // Off by default: the app only goes online when asked to.
        assertFalse(settings.updateAutoCheck.get())
        updates.checkIfDue()
        delay(300)
        assertEquals(0, calls.get())

        settings.updateAutoCheck.set(true)
        settings.updateCheckedAt.set(System.currentTimeMillis() - 60 * 60 * 1000L)
        updates.checkIfDue()
        delay(300)
        assertEquals(0, calls.get())

        settings.updateCheckedAt.set(0)
        updates.checkIfDue()
        withTimeout(5_000) { updates.state.first { it is UpdateState.Available } }
        assertEquals(1, calls.get())
        assertEquals("9.9.0", withTimeout(5_000) { updates.available.first { it != null } })
        // The quiet check does not open the dialog by itself; a tap on the card does.
        assertNull(updates.dialog.value)
        updates.openDetails()
        assertEquals(release, updates.dialog.value)
        settings.updateAutoCheck.set(false)
        settings.updateLatest.set("")
    }

    @Test
    fun downloadVerifiesAndOpensTheInstaller() = runBlocking {
        val release = ReleaseInfo("9.9.0", "https://example.com/r", "https://example.com/yomu-v9.9.0-arm64-v8a.apk", apkSize = 10)
        val installed = mutableListOf<File>()
        var fail = true
        val updates = AppUpdates(
            context,
            settings,
            fetch = { release },
            downloader = { r, dir, progress ->
                progress(0.5f)
                if (fail) throw UpdateChecksumException(r.apkName!!)
                File(dir.apply { mkdirs() }, r.apkName!!).apply { writeText("apk") }
            },
            installer = { installed += it },
        )
        updates.download(release)
        val failed = withTimeout(5_000) { updates.state.first { it is UpdateState.Failed } } as UpdateState.Failed
        assertTrue(failed.error is UpdateChecksumException)
        assertEquals(release, failed.release)
        // A tap on the failed row offers the same release again.
        updates.openDetails()
        assertEquals(release, updates.dialog.value)

        fail = false
        updates.download(release)
        assertNull(updates.dialog.value)
        val ready = withTimeout(5_000) { updates.state.first { it is UpdateState.ReadyToInstall } } as UpdateState.ReadyToInstall
        assertEquals(File(UpdateDownloader.dir(context), "yomu-v9.9.0-arm64-v8a.apk"), ready.file)
        // The installer opens on the main thread, right after the state changes.
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (installed.isNotEmpty()) return@repeat
            Thread.sleep(20)
        }
        assertEquals(listOf(ready.file), installed)
    }

    @Test
    fun installIntentHandsTheApkToThePackageInstaller() {
        val file = File(UpdateDownloader.dir(context).apply { mkdirs() }, "yomu-v9.9.0-arm64-v8a.apk").apply { writeText("apk") }
        assertEquals("dd37c2d7274f7ea982cb83390c36918fee9ce8889073c44b68cdc00bdb8c3e04", UpdateDownloader.sha256(file))
        // androidx FileProvider fails to resolve canonical paths against Robolectric's temp dirs on Windows hosts.
        org.junit.Assume.assumeFalse(System.getProperty("os.name").orEmpty().contains("Windows"))
        val intent = UpdateDownloader.installIntent(context, file)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data?.scheme)
        assertEquals("${context.packageName}.files", intent.data?.authority)
    }
}

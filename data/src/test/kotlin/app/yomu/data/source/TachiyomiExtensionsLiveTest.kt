package app.yomu.data.source

import android.app.Application
import android.os.Looper
import app.yomu.compat.tachiyomi.TachiyomiCompat
import app.yomu.data.source.tachiyomi.TachiyomiSource
import app.yomu.source.CatalogSource
import app.yomu.source.SChapter
import app.yomu.source.SItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.net.URLClassLoader
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import java.util.zip.ZipFile
import eu.kanade.tachiyomi.source.CatalogueSource as TCatalogueSource
import eu.kanade.tachiyomi.source.Source as TSource
import eu.kanade.tachiyomi.source.SourceFactory as TSourceFactory

/**
 * Runs real Mihon/Tachiyomi extensions (their JVM builds, the same classes as in the APK) against the live sites through
 * Yomu's compatibility layer and [TachiyomiSource]: popular → details+chapters → pages → first image → search.
 *
 * Network access is needed, so it only runs on request:
 * `./gradlew :data:testDebugUnitTest --tests '*TachiyomiExtensionsLiveTest*' -Pyomu.liveTests=true -Pyomu.extJars=a.jar:b.jar`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TachiyomiExtensionsLiveTest {

    @Test
    fun realExtensionsWork() {
        assumeTrue(System.getProperty("yomu.liveTests") == "true")
        val jars = System.getProperty("yomu.extJars").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map(::File)
        assumeTrue(jars.isNotEmpty())
        val app = RuntimeEnvironment.getApplication()
        // In the app androidx.startup does this (OkHttp reads its public-suffix list from assets).
        okhttp3.OkHttp.initialize(app)
        TachiyomiCompat.init(app, 2, "1.1.0")
        val report = StringBuilder()
        val failures = mutableListOf<String>()
        val out = File("build/reports/live-extensions.txt").also { it.parentFile.mkdirs(); it.writeText("") }
        progressFile.writeText("")
        for (jar in jars) {
            val manifest = ZipFile(jar).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes().decodeToString() }
            val classes = Regex("""android:name="tachiyomi\.extension\.class"\s+android:value="([^"]+)"""").find(manifest)!!.groupValues[1]
            val pkg = Regex("""package="([^"]+)"""").find(manifest)!!.groupValues[1]
            val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), javaClass.classLoader)
            val sources = classes.split(';').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { cls ->
                when (val o = Class.forName(if (cls.startsWith(".")) pkg + cls else cls, false, loader).getDeclaredConstructor().newInstance()) {
                    is TSourceFactory -> o.createSources()
                    is TSource -> listOf(o)
                    else -> emptyList()
                }
            }.filterIsInstance<TCatalogueSource>().map { TachiyomiSource(it, pkg) }
            report.appendLine("## ${jar.name}: ${sources.size} sources (${sources.map { it.lang }.distinct().joinToString(",")})")
            val picked = listOfNotNull(
                sources.firstOrNull { it.lang == "ru" },
                sources.firstOrNull { it.lang == "en" },
                sources.firstOrNull(),
            ).distinctBy { it.id }.take(2)
            var anyOk = false
            for (s in picked) {
                // A second language is only tried when the first one failed.
                if (anyOk) break
                current = "${s.name} (${s.lang})"
                progress("start")
                val r = runCatching { onWorker(HARD_TIMEOUT_MS) { check(s) } }
                r.onSuccess { line -> anyOk = true; report.appendLine("  OK   ${s.name} (${s.lang}): $line") }
                r.onFailure { e -> report.appendLine("  FAIL ${s.name} (${s.lang}) at [$stage]: ${e.describe()}") }
                progress(if (r.isSuccess) "OK" else "FAIL ${r.exceptionOrNull()?.message?.take(120)}")
                out.writeText(report.toString())
            }
            if (!anyOk) failures += jar.name
        }
        out.writeText(report.toString())
        println(report)
        if (failures.isNotEmpty()) throw AssertionError("Extensions failed: $failures\n$report")
    }

    private val progressFile = File("build/reports/live-progress.txt")
    private val t0 = System.nanoTime()
    @Volatile private var current = ""

    private fun progress(msg: String) {
        progressFile.appendText("%6.1fs %s: %s\n".format((System.nanoTime() - t0) / 1e9, current, msg))
    }

    /**
     * Runs [block] on a worker thread while this (main) thread plays the role of Android's main thread: it keeps the
     * main looper running and lets SystemClock follow real time (Robolectric freezes both, while extensions post to the
     * main thread and their rate limiters wait for SystemClock to move).
     */
    private fun <T> onWorker(timeoutMs: Long, block: () -> T): T {
        val result = AtomicReference<Result<T>?>(null)
        val worker = thread(name = "live-check") { result.set(runCatching(block)) }
        val looper = shadowOf(Looper.getMainLooper())
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var last = System.nanoTime()
        while (worker.isAlive && System.nanoTime() < deadline) {
            Thread.sleep(10)
            val now = System.nanoTime()
            ShadowSystemClock.advanceBy(Duration.ofNanos(now - last))
            last = now
            looper.idle()
        }
        if (worker.isAlive) {
            val trace = worker.stackTrace.take(25).joinToString("\n        at ")
            worker.interrupt()
            throw TimeoutException("hard timeout after ${timeoutMs / 1000}s, worker stack:\n        at $trace")
        }
        return result.get()!!.getOrThrow()
    }

    @Volatile private var stage = ""
        set(value) { field = value; progress(value) }

    private fun Throwable.describe(): String = buildString {
        var t: Throwable? = this@describe
        var depth = 0
        while (t != null && depth < 4) {
            if (depth > 0) append("\n      caused by ")
            append(t.javaClass.name).append(": ").append(t.message?.take(300))
            t.stackTrace.filter { f -> listOf("app.yomu", "eu.kanade", "keiyoushi", "okhttp3", "okio").any { f.className.startsWith(it) } }
                .take(14).forEach { append("\n        at ").append(it) }
            t = t.cause
            depth++
        }
    }

    private fun check(s: CatalogSource): String = runBlocking {
        stage = "popular"
        withTimeout(150_000) {
            val popular = s.getPopular(1)
            require(popular.items.isNotEmpty()) { "popular is empty" }
            var steps = "popular=${popular.items.size}"
            stage = "latest"
            if (s.supportsLatest) steps += " latest=" + runCatching { s.getLatest(1).items.size }.getOrElse { "ERR(${it.message?.take(60)})" }
            // Find a title that has at least one readable chapter (official sites lock some chapters).
            var tried = 0
            for (item in popular.items.take(4)) {
                tried++
                stage = "details #$tried ${item.url}"
                val (details, chapters) = s.getDetailsAndChapters(item)
                if (chapters.isEmpty()) continue
                val candidates = (chapters.take(2) + chapters.takeLast(2)).distinctBy { it.url }
                for (ch in candidates) {
                    stage = "pages ${ch.url}"
                    val pages = runCatching { s.getPages(ch) }.getOrNull().orEmpty()
                    if (pages.isEmpty()) continue
                    stage = "image ${pages.first().imageUrl ?: pages.first().url}"
                    val bytes = firstImage(s, pages.first())
                    stage = "search"
                    val search = runCatching { s.search(details.title.take(12), s.getFilterList(), 1).items.size }.getOrElse { -1 }
                    return@withTimeout "$steps details='${details.title.take(40)}' (author=${details.author?.take(20)}, cover=${details.thumbnailUrl != null}) " +
                        "chapters=${chapters.size} '${ch.name.take(30)}' pages=${pages.size} image=${bytes}B search=$search filters=${s.getFilterList().size} tried=$tried"
                }
            }
            error("$steps: no readable chapter in the first $tried titles")
        }
    }

    private suspend fun firstImage(s: CatalogSource, page: app.yomu.source.Page): Int {
        val url = s.getImageUrl(page)
        val response = s.fetchImage(page, url) ?: app.yomu.source.SourceNetwork.client.newCall(
            Request.Builder().url(url).apply { s.imageHeaders().forEach { (k, v) -> header(k, v) } }.build(),
        ).execute()
        return response.use { r ->
            require(r.isSuccessful) { "image HTTP ${r.code}" }
            val bytes = r.body.bytes()
            require(bytes.size > 500) { "image too small (${bytes.size} B)" }
            val magic = bytes.take(4).map { it.toInt() and 0xff }
            val isImage = magic.take(2) == listOf(0xff, 0xd8) || magic.take(4) == listOf(0x89, 0x50, 0x4e, 0x47) ||
                bytes.copyOfRange(0, 4).decodeToString() == "RIFF" || bytes.copyOfRange(0, 3).decodeToString() == "GIF"
            require(isImage) { "not an image: ${bytes.copyOfRange(0, 16).decodeToString()}" }
            bytes.size
        }
    }

    private companion object {
        const val HARD_TIMEOUT_MS = 175_000L
    }

    @Suppress("unused")
    private fun SItem.short() = "$title ($url)"

    @Suppress("unused")
    private fun SChapter.short() = "$name ($url)"
}

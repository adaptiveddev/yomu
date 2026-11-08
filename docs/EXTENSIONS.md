# Extension sources

Yomu works with two kinds of extensions:

1. **Mihon/Tachiyomi extensions** — ready APKs from Mihon repositories. The main way to add manga and comics sites: nothing to write.
2. **Extensions on the Yomu API** (`source-api`) — for books and novels or when you need something custom (guide below).

## Mihon/Tachiyomi extensions

### Built-in

On first launch Yomu copies five extensions from the [Keiyoushi](https://github.com/keiyoushi/extensions) repository into its private folder (without a system install) — unchanged builds, Apache-2.0, signing key `9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2`. Official platforms and open catalogs were chosen:

| Extension | Package | Version | Languages |
|---|---|---|---|
| MangaDex | `eu.kanade.tachiyomi.extension.all.mangadex` | 1.6.1 | 61 |
| MANGA Plus by SHUEISHA | `eu.kanade.tachiyomi.extension.all.mangaplus` | 1.6.68 | 9 (incl. ru, en) |
| Webtoons.com | `eu.kanade.tachiyomi.extension.all.webtoons` | 1.6.3 | 7 |
| GlobalComix | `eu.kanade.tachiyomi.extension.all.globalcomix` | 1.6.2 | 37 |
| AComics | `eu.kanade.tachiyomi.extension.ru.acomics` | 1.6.10 | ru |

Each was verified against the live sites through Yomu's compatibility layer (`TachiyomiExtensionsLiveTest`): popular and latest → card and chapter list → chapter pages → first image download → search. Comikey failed the check (chapter pages did not load in time) and is not included.

- The built-in MangaDex from version 1.0 is replaced by an extension with the same id — favorites and progress survive.
- A built-in extension can be removed — it will not come back on the next launch. Updates come from any added repository that signs extensions with the same key.

### Repositories

"Catalog → ⋮ → Extensions → Repositories → Add repository". Any of these addresses works:

- a repository's `index.min.json` or `repo.json`, its folder or GitHub page
  (`https://github.com/owner/repo/tree/branch`) — Yomu finds the index itself;
- the "Add to Mihon/Tachiyomi" button on a repository site: the `tachiyomi://add-repo?url=…` and
  `mihon://extension-store?url=…` links open Yomu with a confirmation.

The legacy formats (`index.min.json` + `repo.json` with a name and `signingKeyFingerprint`) and index v2
(protobuf or JSON, including gzip) are supported. Like Mihon, Yomu ships no repository lists — add only the ones you trust.

The "Available" tab has search, a language filter ("Languages"; the device language and English by default), 18+ badges and "Install"; the "Installed" tab has updates ("Update all"), removal, source settings and "Install from file".

### Trust and signatures

- A repository's signing key is remembered when it is added: its extensions signed with that key are trusted. If the repository's key changes, updates are blocked until the repository is added again.
- An extension with a different signature (an APK from a file or a system-installed package) does not load until the user taps "Trust" (the `package:signature SHA-256` pair is remembered).
- 18+ extensions are hidden until the "Show 18+ extensions" switch is on.

### Compatibility

- The extension API is **lib 1.4 and 1.6** — the same set Mihon accepts (1.5 is an incompatible fork, not supported).
- The `compat/tachiyomi` layer implements the public extension API: `eu.kanade.tachiyomi.source.*` (`Source`,
  `CatalogueSource`, `HttpSource`, `ParsedHttpSource`, `ConfigurableSource`, `SourceFactory`, `ResolvableSource`,
  `UnmeteredSource`), models and filters, `NetworkHelper` (a shared OkHttp, WebView cookies, Cloudflare through the built-in
  browser, rate limiting), `JavaScriptEngine` (QuickJS), Injekt (`Application`, `Json`, `ProtoBuf`, `NetworkHelper`) and a
  source's `SharedPreferences` (the "Source settings" screen). The libraries extensions take from the app are the same as in Mihon: OkHttp (with brotli and zstd), Jsoup, kotlinx.serialization (JSON, protobuf), coroutines, RxJava 1,
  AndroidX Preference.
- Extensions needing Mihon's own internals outside the public API will not load — Yomu shows them as broken, without affecting the others.
- The catalog and global search use sources in the device language and English by default (otherwise the built-in extensions alone offer over a hundred language variants); "All languages" and individual languages are chips on the "Catalog" screen.

## Custom extensions on the Yomu API

A source is a class implementing `app.yomu.source.CatalogSource`. The built-in sources (`sources/opds`, `sources/mangadex`, `sources/local`) are written on the same API and make good examples. The API version is **1** (`EXTENSION_API_VERSION`).

### What to implement

```kotlin
interface CatalogSource : Source {           // Source: id, name, lang, content (MANGA/NOVEL/BOOK), supportsLatest
    suspend fun getPopular(page: Int): ItemsPage
    suspend fun getLatest(page: Int): ItemsPage
    suspend fun search(query: String, filters: FilterList, page: Int): ItemsPage
    fun getFilterList(): FilterList                     // optional
    suspend fun getDetails(item: SItem): SItem          // description, genres, status, cover
    suspend fun getChapters(item: SItem): List<SChapter>
    suspend fun getPages(chapter: SChapter): List<Page> // manga: the chapter's images
    suspend fun getContent(chapter: SChapter): String   // novels: the chapter's HTML
    suspend fun getDownloadLinks(book: SItem): List<SBookLink> // books: EPUB/PDF/FB2… files
    suspend fun browse(url: String, page: Int): ItemsPage      // nested catalogs (SItem.isNavigation)
    fun imageHeaders(): Map<String, String>             // e.g. a Referer for images
}
```

- For HTTP sites inherit from `HttpSource`: the app's shared `OkHttpClient` (cache, WebView cookies, User-Agent), `get(url)`/`getText(url)`, `json` (kotlinx.serialization). `id` is generated from `name`/`lang`/`versionId` (`SourceIds.generate`) — **do not change the name or language** after publishing or favorites disappear; to change the scheme, bump `versionId` and give users a migration.
- Several languages/sites in one APK — `SourceFactory.createSources()`.
- Configurable sources (an address, a login) — `ConfigurableSource` (`configKeys` + `configure(values)`).
- Errors: `SourceException(message)` — a clear message for the user; `HttpStatusException(code, url)`; `WebViewRequiredException(url)` — the app opens a WebView, the user passes Cloudflare/a captcha, and the cookies reach OkHttp.
- Filters are declarative: `Header`, `Separator`, `Text`, `CheckBox`, `TriState`, `Select`, `Sort`, `Group`. The app saves filter state itself (`FilterList.saveState`).
- Models: `SItem` (a title/book; `url` is the stable key inside the source), `SChapter` (`url`, `name`, `number`, `dateUpload`, `scanlator`), `Page` (`index`, `url`, `imageUrl`), `ItemsPage(items, hasNextPage)`, `SBookLink(url, mimeType, title, size)`.

### Example

```kotlin
package com.example.yomu.ext

import app.yomu.source.*
import org.jsoup.Jsoup

class ExampleSource : HttpSource() {
    override val name = "Example"
    override val lang = "en"
    override val baseUrl = "https://example.org"
    override val content = SourceContent.MANGA
    override val supportsLatest = true

    override suspend fun getPopular(page: Int) = parseList("$baseUrl/popular?page=$page")
    override suspend fun getLatest(page: Int) = parseList("$baseUrl/latest?page=$page")
    override suspend fun search(query: String, filters: FilterList, page: Int) =
        parseList("$baseUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page")

    private suspend fun parseList(url: String): ItemsPage {
        val doc = Jsoup.parse(getText(url), url)
        val items = doc.select(".item").map {
            SItem(url = it.select("a").attr("abs:href"), title = it.select(".title").text(), thumbnailUrl = it.select("img").attr("abs:src"))
        }
        return ItemsPage(items, hasNextPage = doc.selectFirst(".next") != null)
    }

    override suspend fun getChapters(item: SItem): List<SChapter> =
        Jsoup.parse(getText(item.url), item.url).select(".chapter a").map { SChapter(url = it.attr("abs:href"), name = it.text()) }

    override suspend fun getPages(chapter: SChapter): List<Page> =
        Jsoup.parse(getText(chapter.url), chapter.url).select(".page img").mapIndexed { i, img -> Page(i, imageUrl = img.attr("abs:src")) }

    override fun imageHeaders() = mapOf("Referer" to baseUrl)
}
```

### Packaging into an APK

1. A separate Android (application) project without activities. `source-api` is connected as **`compileOnly`** (copy the module or build a jar: `./gradlew :source-api:jar`). Kotlin stdlib, coroutines, kotlinx.serialization, OkHttp, Okio and Jsoup are also `compileOnly` — at runtime the classes come from the app (their public API is kept by the app's R8 rules).
2. In `AndroidManifest.xml`:

```xml
<application android:label="Yomu: Example" android:icon="@mipmap/ic_launcher" android:hasCode="true">
    <!-- Classes separated by ";", a leading dot = the app's package -->
    <meta-data android:name="yomu.extension.class" android:value=".ExampleSource" />
    <meta-data android:name="yomu.extension.api" android:value="1" />
    <!-- 1 = 18+ content, hidden until the user enables it in settings -->
    <meta-data android:name="yomu.extension.nsfw" android:value="0" />
</application>
```

3. The class must have a public parameterless constructor and implement `CatalogSource` or `SourceFactory`. Do not enable R8 obfuscation for the source classes (or add `-keep class com.example.yomu.ext.** { *; }`).

### Installation and trust

- **Installed APKs**: Yomu finds packages with the `yomu.extension.class` meta-data (hence `QUERY_ALL_PACKAGES`) and loads them with a `PathClassLoader`.
- **Repositories**: "Catalog → ⋮ → Extensions → Repositories" — a URL to an `index.min.json`. The APK is downloaded into a private folder and loaded with a `DexClassLoader` without a system install.
- An extension with an unknown signature does not load until the user taps "Trust" (the `package:signature SHA-256` pair is remembered). An extension with an API newer than supported is rejected.

#### Repository format (the Yomu API)

`https://example.org/repo/index.min.json`, APKs sit next to it in `apk/`:

```json
[
  {
    "name": "Yomu: Example", "pkg": "com.example.yomu.ext", "apk": "example-v1.apk",
    "lang": "en", "code": 1, "version": "1.0", "nsfw": 0,
    "sources": [{ "name": "Example", "lang": "en", "id": "1234567890", "baseUrl": "https://example.org" }]
  }
]
```

### Debugging

- The built-in sources are a live example: `sources/opds/OpdsSource.kt` (books, navigation, OpenSearch), `sources/mangadex/MangaDexSource.kt` (manga, filters, languages).
- Extension and repository loading errors go to logcat (Timber: "Extension … failed to load"). In a release build enable verbose logging in Settings → Advanced.

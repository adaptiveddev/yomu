# Расширения-источники

Yomu работает с двумя видами расширений:

1. **Расширения Mihon/Tachiyomi** — готовые APK из репозиториев для Mihon. Основной способ добавить сайты с мангой и
   комиксами: ничего писать не нужно.
2. **Расширения на API Yomu** (`source-api`) — для книг и ранобэ или если нужно что-то своё (гайд ниже).

## Расширения Mihon/Tachiyomi

### Встроенные

При первом запуске Yomu копирует в свою приватную папку (без системной установки) пять расширений из репозитория
[Keiyoushi](https://github.com/keiyoushi/extensions) — неизменённые сборки, Apache-2.0, ключ подписи
`9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2`. Выбраны официальные платформы и открытые каталоги:

| Расширение | Пакет | Версия | Языки |
|---|---|---|---|
| MangaDex | `eu.kanade.tachiyomi.extension.all.mangadex` | 1.6.1 | 61 |
| MANGA Plus by SHUEISHA | `eu.kanade.tachiyomi.extension.all.mangaplus` | 1.6.68 | 9 (в том числе ru, en) |
| Webtoons.com | `eu.kanade.tachiyomi.extension.all.webtoons` | 1.6.3 | 7 |
| GlobalComix | `eu.kanade.tachiyomi.extension.all.globalcomix` | 1.6.2 | 37 |
| AComics | `eu.kanade.tachiyomi.extension.ru.acomics` | 1.6.10 | ru |

Каждое проверено на живых сайтах через слой совместимости Yomu (`TachiyomiExtensionsLiveTest`): популярное и новое →
карточка и список глав → страницы главы → загрузка первой картинки → поиск. Comikey проверку не прошёл (страницы глав
не загружались за отведённое время) и не включён.

- Встроенный MangaDex из версии 1.0 заменяется расширением с тем же id — избранное и прогресс сохраняются.
- Встроенное расширение можно удалить — при следующем запуске оно не вернётся. Обновления приходят из любого
  добавленного репозитория, который подписывает расширения тем же ключом.

### Репозитории

«Каталог → ⋮ → Расширения → Репозитории → Добавить репозиторий». Подходит любой из адресов:

- `index.min.json` или `repo.json` репозитория, его папка или страница на GitHub
  (`https://github.com/owner/repo/tree/branch`) — индекс Yomu найдёт сам;
- кнопка «Add to Mihon/Tachiyomi» на сайте репозитория: ссылки `tachiyomi://add-repo?url=…` и
  `mihon://extension-store?url=…` открывают Yomu с подтверждением.

Поддерживаются форматы legacy (`index.min.json` + `repo.json` с именем и `signingKeyFingerprint`) и index v2
(protobuf или JSON, в том числе gzip). Как и Mihon, Yomu не поставляет списков репозиториев — добавляйте только те,
которым доверяете.

На вкладке «Доступные» — поиск, фильтр по языкам («Языки»; по умолчанию язык устройства и английский), бейджи 18+,
«Установить»; на вкладке «Установленные» — обновления («Обновить все»), удаление, настройки источников, «Установить из
файла».

### Доверие и подпись

- Ключ подписи репозитория запоминается при добавлении: его расширения, подписанные этим ключом, считаются доверенными.
  Если ключ у репозитория сменился, обновления блокируются, пока репозиторий не добавить заново.
- Расширение с другой подписью (APK из файла или установленный в систему пакет) не загружается, пока пользователь не
  нажмёт «Доверять» (запоминается пара `пакет:SHA-256 подписи`).
- Расширения 18+ скрыты, пока не включён переключатель «Показывать расширения 18+».

### Совместимость

- API расширений **lib 1.4 и 1.6** — тот же набор, что принимает Mihon (1.5 — несовместимый форк, не поддерживается).
- Слой `compat/tachiyomi` реализует публичный API расширений: `eu.kanade.tachiyomi.source.*` (`Source`,
  `CatalogueSource`, `HttpSource`, `ParsedHttpSource`, `ConfigurableSource`, `SourceFactory`, `ResolvableSource`,
  `UnmeteredSource`), модели и фильтры, `NetworkHelper` (общий OkHttp, cookie из WebView, Cloudflare через встроенный
  браузер, rate-limit), `JavaScriptEngine` (QuickJS), Injekt (`Application`, `Json`, `ProtoBuf`, `NetworkHelper`) и
  `SharedPreferences` источника (экран «Настройки источника»). Библиотеки, которые расширения берут у приложения, — те
  же, что в Mihon: OkHttp (с brotli и zstd), Jsoup, kotlinx.serialization (JSON, protobuf), coroutines, RxJava 1,
  AndroidX Preference.
- Расширения, которым нужны внутренние классы самого Mihon вне публичного API, не загрузятся — Yomu покажет их как
  неработающие, остальные это не затронет.
- Каталог и глобальный поиск по умолчанию используют источники на языке устройства и английском (иначе одни встроенные
  расширения дают больше сотни языковых вариантов); «Все языки» и отдельные языки — чипами на экране «Каталог».

## Свои расширения на API Yomu

Источник — класс, реализующий `app.yomu.source.CatalogSource`. Встроенные источники (`sources/opds`, `sources/mangadex`, `sources/local`) написаны на том же API, их удобно брать за образец. API версии **1** (`EXTENSION_API_VERSION`).

### Что реализовать

```kotlin
interface CatalogSource : Source {           // Source: id, name, lang, content (MANGA/NOVEL/BOOK), supportsLatest
    suspend fun getPopular(page: Int): ItemsPage
    suspend fun getLatest(page: Int): ItemsPage
    suspend fun search(query: String, filters: FilterList, page: Int): ItemsPage
    fun getFilterList(): FilterList                     // необязательно
    suspend fun getDetails(item: SItem): SItem          // описание, жанры, статус, обложка
    suspend fun getChapters(item: SItem): List<SChapter>
    suspend fun getPages(chapter: SChapter): List<Page> // манга: картинки главы
    suspend fun getContent(chapter: SChapter): String   // ранобэ: HTML главы
    suspend fun getDownloadLinks(book: SItem): List<SBookLink> // книги: файлы EPUB/PDF/FB2…
    suspend fun browse(url: String, page: Int): ItemsPage      // вложенные каталоги (SItem.isNavigation)
    fun imageHeaders(): Map<String, String>             // например Referer для картинок
}
```

- Для HTTP-сайтов наследуйтесь от `HttpSource`: общий `OkHttpClient` приложения (кеш, cookie из WebView, User-Agent), `get(url)`/`getText(url)`, `json` (kotlinx.serialization). `id` генерируется из `name`/`lang`/`versionId` (`SourceIds.generate`) — **не меняйте имя и язык** после публикации, иначе пропадёт избранное; для смены схемы увеличьте `versionId` и дайте пользователю миграцию.
- Несколько языков/сайтов в одном APK — `SourceFactory.createSources()`.
- Настраиваемые источники (адрес, логин) — `ConfigurableSource` (`configKeys` + `configure(values)`).
- Ошибки: `SourceException(message)` — понятное сообщение пользователю; `HttpStatusException(code, url)`; `WebViewRequiredException(url)` — приложение откроет WebView, пользователь пройдёт Cloudflare/капчу, cookie попадут в OkHttp.
- Фильтры декларативные: `Header`, `Separator`, `Text`, `CheckBox`, `TriState`, `Select`, `Sort`, `Group`. Состояние фильтров приложение сохраняет само (`FilterList.saveState`).
- Модели: `SItem` (тайтл/книга; `url` — стабильный ключ внутри источника), `SChapter` (`url`, `name`, `number`, `dateUpload`, `scanlator`), `Page` (`index`, `url`, `imageUrl`), `ItemsPage(items, hasNextPage)`, `SBookLink(url, mimeType, title, size)`.

### Пример

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

### Упаковка в APK

1. Отдельный Android-проект (application) без активностей. `source-api` подключается как **`compileOnly`** (скопируйте модуль или соберите jar: `./gradlew :source-api:jar`). Kotlin stdlib, coroutines, kotlinx.serialization, OkHttp, Okio и Jsoup тоже `compileOnly` — во время работы классы берутся из приложения (их публичный API сохранён правилами R8 приложения).
2. В `AndroidManifest.xml`:

```xml
<application android:label="Yomu: Example" android:icon="@mipmap/ic_launcher" android:hasCode="true">
    <!-- Классы через «;», точка в начале = пакет приложения -->
    <meta-data android:name="yomu.extension.class" android:value=".ExampleSource" />
    <meta-data android:name="yomu.extension.api" android:value="1" />
    <!-- 1 = контент 18+, скрывается пока пользователь не включит его в настройках -->
    <meta-data android:name="yomu.extension.nsfw" android:value="0" />
</application>
```

3. Класс должен иметь публичный конструктор без параметров и реализовывать `CatalogSource` или `SourceFactory`. Не включайте R8-обфускацию для классов источника (или добавьте `-keep class com.example.yomu.ext.** { *; }`).

### Установка и доверие

- **Установленные APK**: Yomu находит пакеты с meta-data `yomu.extension.class` (поэтому нужен `QUERY_ALL_PACKAGES`) и загружает их `PathClassLoader`.
- **Репозитории**: «Каталог → ⋮ → Расширения → Репозитории» — URL на `index.min.json`. APK скачивается в приватную папку и загружается `DexClassLoader` без системной установки.
- Расширение с неизвестной подписью не загружается, пока пользователь не нажмёт «Доверять» (запоминается пара `пакет:SHA-256 подписи`). Расширение с API новее поддерживаемого отклоняется.

#### Формат репозитория (Yomu API)

`https://example.org/repo/index.min.json`, APK лежат рядом в `apk/`:

```json
[
  {
    "name": "Yomu: Example", "pkg": "com.example.yomu.ext", "apk": "example-v1.apk",
    "lang": "en", "code": 1, "version": "1.0", "nsfw": 0,
    "sources": [{ "name": "Example", "lang": "en", "id": "1234567890", "baseUrl": "https://example.org" }]
  }
]
```

### Отладка

- Встроенные источники — живой пример: `sources/opds/OpdsSource.kt` (книги, навигация, OpenSearch), `sources/mangadex/MangaDexSource.kt` (манга, фильтры, языки).
- Ошибки загрузки расширений и репозиториев пишутся в logcat (Timber: «Extension … failed to load»). В release-сборке включите подробное логирование в разделе настроек «Дополнительно».

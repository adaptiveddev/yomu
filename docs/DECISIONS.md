# Decisions

One line per decision. Format: **what was chosen** — why / how it differs from the plan.

## Stack and architecture

- **Koin 4 instead of Hilt** — no kapt/ksp DI generation, faster builds of a multi-module project; ViewModels with parameters via `parametersOf`. No Hilt R8 rules needed.
- **A custom EPUB engine (WebView + CSS columns + a JS bridge) instead of Readium** — Readium Kotlin pulls heavy dependencies and a complicated navigator license; a custom engine is easier to integrate with the floating selection panel, tap-dictionary and paragraph translation. The project license stays Apache-2.0.
- **FB2, TXT, HTML, DOCX, MOBI/AZW3 are converted to EPUB at import** — one text engine instead of five; the original file is kept, the converted copy lives in `files/converted/`.
- **PDF — `io.legere:pdfiumandroid` (PDFium)** — faster and more stable than `PdfRenderer`, with a text layer for search/selection and password support.
- **Manga: CBZ/ZIP and CB7 — Apache Commons Compress, CBR — junrar; an image folder — via SAF.**
- **Offline translation — ML Kit Translation, language ID — ML Kit Language ID, OCR — ML Kit Text Recognition (Latin, Japanese, Chinese, Korean).** Models are downloaded on the user's request.
- **Online translation: Google (the public translate.googleapis.com endpoint), DeepL (own key), LibreTranslate (own server), any OpenAI-compatible LLM (own URL/key/model).** Keys are stored only by the user.
- **Online dictionary — dictionaryapi.dev (en) and Wiktionary REST**; offline — StarDict, DSL, JMdict, Wiktionary (kaikki JSONL) imports into Room FTS.
- **Room + FTS4** for the library, annotations, words, dictionaries and the catalog; **Paging 3** for large lists (library, catalog).
- **DataStore (Preferences)** for settings; complex values (reader settings, selection panels) are JSON via kotlinx.serialization.
- **WorkManager** for folder scans, downloads (foreground dataSync), the chapter-update check, auto-backup, sync and reminders.
- **Coil 3 + OkHttp 5** for covers and pages; **telephoto** for manga page and image zoom.
- **Navigation Compose with type-safe routes (`@Serializable`) + Material 3 `NavigationSuiteScaffold`** — a bottom bar on phones, a rail on tablets.
- **One `MainActivity` (singleTask)**, all screens are Compose; VIEW/SEND/PROCESS_TEXT intents are handled by `MainViewModel`.

## Catalog and extensions

- **A custom `source-api` + a Mihon/Tachiyomi extension compatibility layer (`compat/tachiyomi`, since 1.1.0)** — the public extension API (`eu.kanade.tachiyomi.*`, lib 1.4/1.6) is ported from Mihon (Apache-2.0), so unchanged extension APKs load as is; each source is wrapped into a `CatalogSource` (`TachiyomiSource`) and the rest of the catalog works with one API. Custom extensions can still be written against `app.yomu.source.*`.
- **Built-in extensions — unchanged Keiyoushi APKs in `assets/extensions`**: copied to a private folder at startup and trusted only for their own package and signing key. Official platforms and open catalogs were chosen (MangaDex, MANGA Plus, Webtoons, GlobalComix, AComics); each was verified with a live test, a failing one (Comikey) is not included. Yomu ships no third-party repository lists — like Mihon.
- **A source with the same id from an extension replaces the built-in one** (MangaDex) — favorites and progress survive the transition.
- **User-Agent: `Yomu/<version>` for the MangaDex API** (the API answers a protection HTML page to a browser UA), a browser UA for extensions, as in Mihon. HTML instead of JSON → `WebViewRequiredException`.
- **Default catalog languages — the device language and English** (as in Mihon): built-in extensions offer over a hundred language variants; global search uses the same filter.
- **Extensions: installed APKs with the `yomu.extension.class` meta-data + APKs from repositories in a private folder (DexClassLoader).** Every new signature requires explicit user trust.
- **`QUERY_ALL_PACKAGES`** — needed to find installed APK extensions on Android 11+. For a Google Play release this permission must be justified or replaced with `<queries>` for specific packages.
- **Built-in sources: OPDS (Project Gutenberg, Standard Ebooks, Feedbooks, ManyBooks + custom catalogs), Gutendex, Open Library/Internet Archive, MangaDex, a local folder.**
- **Cleartext traffic is allowed (`network_security_config`)** — home OPDS servers (Calibre, Kavita, Komga) often run plain `http://` on the LAN.
- **Cloudflare/captcha — through the built-in WebView**: a source throws `WebViewRequiredException`, the WebView screen saves cookies for OkHttp.

## The DOCX page layout

- **A custom DOCX → HTML converter with page geometry + `paginate.js` in a WebView** — Chromium breaks the lines itself, the script lays blocks into pages (headers and footers, footnotes, repeated table headers, keep-with-next, breaks). LibreOffice/PDF rendering on-device is too heavy. The result is cached in `cacheDir/docx-layout/<id>`.
- **Liberation Serif/Sans and Carlito fonts (SIL OFL)** — metrically compatible with Times New Roman, Arial and Calibri, so line breaks are close to Word's.
- **Paragraph spacing follows the Word rules for DOCX** (the larger of "after" and "before", as in LibreOffice's DOCX import). Verified against a LibreOffice render of a test document.
- **The mode is supplementary**: the main mode for DOCX remains the EPUB reflow (dictionary, selection, notes, TTS).

## History and statistics

- **History is built from reading sessions (`reading_sessions`) and the catalog's chapter history**; "remove from history" only hides sessions (`hidden`), statistics keep them. Computations are the pure `StatsCalculator` function (domain, covered by tests).

## Editor

- **The editor works on a working copy (an unpacked EPUB / manga pages) with a disk undo/redo history** (`History`: before/after snapshots per step, surviving restarts). The original file changes only on "Save".
- **Non-EPUB books are saved as EPUB, manga from CBR/CB7/a folder as CBZ** (writing RAR is impossible with free libraries).
- **Manga cleaning and typesetting are not implemented** — instead there are margin crop, rotation, brightness/contrast, dual-page splitting, merging and recompression. See KNOWN_LIMITATIONS.

## Security and privacy

- **The PIN is stored as PBKDF2-HMAC-SHA256 (20 000 iterations, a 16-byte salt)**, biometrics via AndroidX Biometric; `FLAG_SECURE` — optional.
- **No analytics, no ads, no own server.** The network is used only by user action or a schedule the user enabled (chapter updates, sync).
- **Sync — a file in a user-chosen folder (SAF: Syncthing, Google Drive, a WebDAV documents provider)**, merging by record modification time.
- **Sync 2.0 (2.0.0) — a three-way merge against the last sync's base.** The device keeps `files/sync/base.json`: which records existed after the previous sync. A record that existed and disappeared is a deletion here (a mark in the file); a mark in the file deletes a record only if it did not change after the deletion. Without a mark nothing is deleted: an old-version file, a fresh install or a missing book erase no data. Version-2 fields have defaults, so 1.x reads the file and 2.0 reads 1.x files. WebDAV — direct OkHttp requests with `If-Match` by ETag instead of a SAF provider: WebDAV SAF providers are rare and do not allow safe overwrites.
- **Trackers — OAuth in a browser with a `yomu://…-auth` return, tokens only in private SharedPreferences** (not in backups or the sync file). App keys are build properties, not code: an open repository does not hand out other people's keys, and a user can enter their own.
- **PDF highlights in the file — a custom JVM PDF parser and incremental writer (`data/book/pdf`), not PDFium:** the in-app PDFium wrapper can only read, and an incremental update (ISO 32000-1, 7.5.6) leaves the original bytes untouched — signatures and foreign changes do not break, and Yomu's own update can be cut off and replaced. The reader opens the file without Yomu's update and draws the highlights itself so they do not double. The database stays the source of truth.
- **A backup is a ZIP: a JSON dump of the DB tables + settings + (optionally) book files**; auto-backups rotate the last N.

## Build and quality

- **Release: R8 + shrinkResources; signing from the `YOMU_KEYSTORE_*` environment variables, without them — the debug key** (so `assembleRelease` works locally and in CI without secrets). Since 1.1.0 published APKs are signed with a permanent key (stored outside the repository); 1.0.0 was signed with a temporary debug key, so moving to 1.1.0 requires a reinstall.
- **ABI splits arm64-v8a and x86_64** — native libraries (PDFium, ML Kit) inflate a universal APK; armeabi-v7a is not built.
- **`lint.checkReleaseBuilds = false`** — lint runs as a separate CI task, not on every release build (time and memory savings).
- **Tests: JUnit 5 for logic (domain, data, editor, OPDS), Robolectric + Roborazzi (JUnit 4/vintage) for the app smoke test with screenshots.**
- **Shared module settings are convention plugins in `build-logic`**, versions live in `gradle/libs.versions.toml`.

## Audiobooks

- **Media3 (ExoPlayer + MediaSessionService) for audiobooks** — background playback, notification, lock-screen and Bluetooth controls, HLS; the service owns the playlist, saves positions and resolves streams, so playback survives leaving the screen.
- **One `AUDIO` format for files, folders and `.yaudio` manifests** — a book is an audio file, a folder of tracks, or a small JSON manifest of online tracks (YouTube, feeds, catalog streams). Positions use the common `Locator` (chapter = track, offset = milliseconds), so history, statistics and progress sync work unchanged.
- **YouTube without NewPipeExtractor or yt-dlp** — they are GPL or need Python; a small InnerTube client with the JavaScript-free clients (visionOS HLS, Android VR, iOS) keeps the Apache-2.0 license. The client table lives in one place (`YouTubeClient.CLIENTS`) for updates. Playlists and search use the visionOS client too: its classic renderers page to the end, while the web client’s continuations return nothing after the first page (November 2026).
- **NotebookLM through files** — no public API for personal notebooks, and Google sign-in is blocked in WebViews; downloaded or shared audio overviews import like any audio.
- **Catalog audiobooks stay `SourceContent.BOOK`** — a stream is one more acquisition-link type (`application/rss+xml`, `application/x-yomu-audio-stream`), so OPDS, extensions and the title page need no new content type.
- **Offline copies live next to the manifest** — `x.yaudio` keeps its tracks in `x.files/` and records each in `AudioTrack.file`; the original address stays, so a missing copy streams again and the book stays one row. Downloads run in a per-book WorkManager job, YouTube in 10 MB ranges (its servers slow down long single requests).

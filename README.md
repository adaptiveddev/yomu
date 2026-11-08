# Yomu

[![CI](https://github.com/adxptived/yomu/actions/workflows/ci.yml/badge.svg)](https://github.com/adxptived/yomu/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/adxptived/yomu?label=release)](https://github.com/adxptived/yomu/releases/latest)
[![Nightly](https://img.shields.io/badge/nightly-APK-8f7bd6)](https://github.com/adxptived/yomu/releases/tag/nightly)
[![Downloads](https://img.shields.io/github/downloads/adxptived/yomu/total)](https://github.com/adxptived/yomu/releases)
[![License](https://img.shields.io/github/license/adxptived/yomu)](LICENSE)

**Yomu** (読む — "to read") is an Android reader for books, PDFs, comics and manga, built for people who read in foreign languages: tap-to-dictionary, translation, saving words with context and spaced repetition. It also ships a book & manga editor and an online catalog in the spirit of Mihon.

Author: **Shinoame** ([github.com/adxptived](https://github.com/adxptived)). Free, open source (Apache-2.0), no accounts, no telemetry.

| Library | Catalog | Reader | Dictionary | Profile | Settings |
|---|---|---|---|---|---|
| ![](docs/screenshots/library.png) | ![](docs/screenshots/catalog.png) | ![](docs/screenshots/chapters.png) | ![](docs/screenshots/dictionary.png) | ![](docs/screenshots/stats.png) | ![](docs/screenshots/settings.png) |

## Download

- Grab an APK from the [latest release](https://github.com/adxptived/yomu/releases/latest): `yomu-vX.Y.Z-arm64-v8a.apk` for phones and tablets, `yomu-vX.Y.Z-x86_64.apk` for emulators and x86 devices. Checksums are in `SHA256SUMS.txt`.
- In-app updates: **Settings → About → Check for updates** downloads the APK for your device, verifies its SHA-256 and opens the system installer. An optional daily check adds a dot to the Settings tab. Requires Android 8.0+.
- Nightly builds are published automatically from `main`.

All release APKs are signed with one permanent key, so updates install over each other without uninstalling. If you come from an APK signed with a different key, Android will ask for a clean install once — make a backup first (Settings → Backup and sync).

## Features

### Reading

- Formats: EPUB, PDF, FB2/FB2.ZIP, MOBI/AZW3, DOCX, TXT, HTML, CBZ/ZIP, CBR/RAR, CB7/7Z and image folders.
- Library with covers, metadata, custom tabs (books and online titles together), tags, filter panel, sorting, "Continue reading", search and bulk actions; automatic folder scanning; copy or link import modes.
- Text engine: fonts (including custom), sizes, spacing, margins, themes, pagination or scroll, tap zones, volume keys, read-aloud with highlighting and a sleep timer, search, table of contents.
- Position: "page N of ≈M", jump to page or percent, slider preview, time left in chapter and book, status line with clock and battery.
- DOCX additionally has a **page layout** mode: A4 pages as printed (margins, headers/footers, footnotes, tables, images, numbering).
- **Profile**: reading time, streaks, achievements, shareable card, CSV history; daily history and statistics with per-day/hour/weekday charts.
- Manga: left-to-right (default), right-to-left, vertical, webtoon; zoom, dual pages, preloading, image filters, tappable zones. PDF: zoom, search, highlights saved into the PDF file itself, passwords, night mode, margin crop, OCR of scans for translation.

### Language learning

- Floating selection panel with customizable buttons (copy, note, dictionary, translate, speak, search, share, custom URLs and apps); "Find in Yomu" from other apps.
- Offline dictionaries: StarDict, DSL, JMdict, Wiktionary; online dictionaries; lemmatization (en/ru) and Japanese deinflection.
- Translation offline (ML Kit) and online (Google, DeepL, LibreTranslate, any OpenAI-compatible LLM); whole-book translation or a bilingual edition.
- Saved words with context, spaced-repetition cards (SM-2), daily goal and reminders, CSV/Anki export.

### Notes

Highlights in colors, Markdown notes with #tags (on a selection or a page), bookmarks with progress-bar marks, quote images; search and export to Markdown (Obsidian), JSON, HTML, TXT and Readwise CSV.

### Editor

EPUB: metadata, cover, chapters (order, split, merge), table of contents, visual chapter editing, selected-chapter translation, XHTML/CSS with syntax highlighting, regex find & replace, text/style cleanup, images, validation with auto-fixes. Manga: page order, rotation, margin crop, color correction, spreads, recompression, ComicInfo, OCR page translation with text typeset into bubbles. Bulk metadata editing across books. Undo/redo survives restarts.

### Catalog and downloads

- OPDS (Project Gutenberg, Standard Ebooks, Calibre/Kavita/Komga servers), Gutendex, Open Library / Internet Archive, a local folder.
- **Mihon/Tachiyomi extensions** from repositories (including "Add to Mihon/Tachiyomi" links) plus five built-in ones: MangaDex, MANGA Plus, Webtoons, GlobalComix, AComics; novels — RanobeLib and NovelBin.
- Library import from a Mihon/Tachiyomi backup; tracking with Shikimori, AniList and MyAnimeList; chapter updates, history, global search, source migration, WebView for Cloudflare, timeouts and retries.
- Download queue with pause, priority and retries, parallel page downloads, auto-download of new chapters, "download while reading", offline reading.

### Sync and backup

- Backups (manual and scheduled) and sync through a shared folder or WebDAV: progress, highlights, notes, bookmarks, words, the online library — and, optionally, the book files themselves.
- Sync conflicts are resolved in the app (the "Sync conflicts" screen): changed book files, image folders and their pages, and book deletions all become explicit decisions; changed copies are never overwritten or deleted silently.

| Appearance | Navigation | Updates | Library filters |
|---|---|---|---|
| ![](docs/screenshots/appearance.png) | ![](docs/screenshots/nav-settings.png) | ![](docs/screenshots/update-dialog.png) | ![](docs/screenshots/library-filters.png) |

### Everything else

PIN/biometric lock, incognito mode, error log, dark (default), light and AMOLED themes, 11 accent colors plus Material You, seven app icons (themed on Android 13+), EN/RU interface, phones and tablets.

## Building

Requires JDK 17 or 21 and an Android SDK (platform 36, build-tools 36). Set `sdk.dir` in `local.properties` or `ANDROID_HOME`.

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew assembleRelease        # app/build/outputs/apk/release/ (R8 + shrinkResources)
./gradlew assembleFast           # debug app built like a release
./gradlew test                   # unit tests + the app's Robolectric smoke test
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true   # + screenshots into app/build/outputs/roborazzi
./gradlew detekt lint            # static analysis
```

Tracker OAuth keys are optional: pass `-PYOMU_ANILIST_CLIENT_ID=…` etc. at build time (see [docs/TRACKING.md](docs/TRACKING.md)); without them users enter their own keys in Settings → Tracking.

Release signing: without environment variables a release is signed with the local debug key; published APKs are signed by CI with the repository's secret keystore (`YOMU_KEYSTORE_BASE64`, `YOMU_KEYSTORE_PASSWORD`, `YOMU_KEY_ALIAS`, `YOMU_KEY_PASSWORD`). Tagging `vX.Y.Z` builds and publishes a release automatically; every green commit to `main` gets a `nightly` pre-release. See [docs/RELEASING.md](docs/RELEASING.md).

The build expects up to 4 GB of memory (`-Xmx2400m`, 2 workers).

## Dictionaries

Import from the Dictionary screen: **StarDict** (`.ifo`/`.idx`/`.dict`, zipped is fine), **DSL** (ABBYY Lingvo), **Wiktionary** JSON dumps from [kaikki.org](https://kaikki.org), **JMdict** (`JMdict_e.xml`). Import runs in the background; dictionaries can be toggled and ordered. More in the [user guide](docs/USER_GUIDE.md).

## Extension sources

- **Mihon/Tachiyomi** extensions (lib 1.4 and 1.6): Catalog → ⋮ → Extensions → Repositories — an `index.min.json`/`repo.json` URL, a GitHub page or an "Add to Mihon/Tachiyomi" link.
- **Yomu API**: a `CatalogSource` implementation from the `source-api` module packaged as an APK with the `yomu.extension.class` meta-data.

Details and signing checks: [docs/EXTENSIONS.md](docs/EXTENSIONS.md).

## Documentation

- [User guide](docs/USER_GUIDE.md) · [Changelog](CHANGELOG.md) · [Releasing](docs/RELEASING.md)
- [Architecture](docs/ARCHITECTURE.md) · [Decisions](docs/DECISIONS.md) · [Roadmap](docs/PROGRESS.md) · [Known limitations](docs/KNOWN_LIMITATIONS.md) · [Not done yet (RU)](docs/НЕ_СДЕЛАНО.md)
- [Extensions](docs/EXTENSIONS.md) · [Tracking](docs/TRACKING.md) · [Privacy](docs/PRIVACY.md) · [Contributing](CONTRIBUTING.md)

## License

[Apache License 2.0](LICENSE).

Key dependencies: AndroidX/Jetpack Compose, Kotlin, kotlinx.coroutines/serialization, Koin, OkHttp/Okio, Coil, Timber, Apache Commons Compress, RxJava, quickjs-java — Apache-2.0; Injekt — MIT; the `compat/tachiyomi` compatibility layer and built-in extensions — Mihon/Keiyoushi code, Apache-2.0 (see the NOTICE files); Liberation/Carlito fonts — SIL OFL 1.1; Jsoup — MIT; XZ for Java — 0BSD; PDFium (via `io.legere:pdfiumandroid`) — BSD-3-Clause; **junrar — UnRAR license** (decompression only); **Google ML Kit — proprietary Google terms** (offline translation, language ID, OCR). An ML Kit-free flavor is required for F-Droid (see KNOWN_LIMITATIONS).

<sub>Screenshots are taken by Robolectric + Roborazzi from `AppSmokeTest` (no real device).</sub>

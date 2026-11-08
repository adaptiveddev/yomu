# Plan progress (PROGRESS)

Cross-checked against [plan.md](../plan.md), stages 0–9. Legend:

- `[x]` — done and building;
- `[~]` — done in a simplified or partial form; details in [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) and [DECISIONS.md](DECISIONS.md);
- `[ ]` — not done.

## Version 2.0.4 (partial rework)

- [~] Binding local books and manga to three existing services, manual chapter/status/score editing, a submission queue and the profile list. No automatic chapter detection for files; bindings are not part of the WebDAV data sync.
- [x] The 5 → 6 database migration keeping online-title bindings; a backup with files includes the synced-books catalog.
- [x] 55 selected tests without errors: local and existing tracking, migrations, backup restore, sync, Mihon import and Robolectric screens. Quick edits apply to the latest entry and are not overwritten by the previous request's response.
- [~] Real services and devices are not verified yet.

## Version 2.0.3 (partial rework)

- [~] Optional book file transfer over WebDAV with SHA-256 checks; image folders and shared folders are not supported, deletions and changed files are not spread automatically. The setting must be enabled per device.
- [x] Selected tests: 26 sync tests, 5 update-resume tests and 11 release-interface tests — without errors. That is not a two-real-devices-and-WebDAV verification.
- [ ] Real devices, WebDAV, tracker accounts and the other requested features still need verification or implementation.

## Version 2.0.2 (partial rework)

- [x] An interrupted APK download resumes over HTTP Range when a checksum is available; a server refusing Range leads to a full download. The SHA-256 check is kept.
- [x] The package name and the current certificate of the installed version are checked before opening the installer. Android additionally verifies the APK on install.
- [x] A corrupted sync file and a file of unknown version are not overwritten.
- [x] An EPUB with unknown encryption shows an unsupported-format error; font obfuscation is allowed. DRM decryption is not implemented.
- [x] UpdateResumeTest (5), EpubEncryptionTest (2), SyncReadSafetyTest (2), Release170LogicTest; assembleDebug and :app:assembleFast builds. R8 emits Kotlin metadata compatibility warnings, fast was not run on a device.
- [ ] Book file transfer, local tracking and new services, full DOCX/PDF/fixed-layout EPUB layout — not implemented yet.
- [~] The Android 35 emulator ran in software mode without KVM but did not finish booting; an APK install timed out. The app was not verified on it.
- [ ] End-to-end checks of accounts, WebDAV and two devices were not done.

## Version 2.0.1 (audit: trackers, WebDAV; Profile Tracking and Downloads tabs)

- [x] "Profile → Tracking": service accounts, all bound titles (status, chapters, score, unsent changes), a status filter, "Refresh from services", "Send" — screenshot `210_profile_tracking`, `Release201Test.refreshAllReadsEveryEntryAndKeepsGoingAfterAnError`
- [x] "Profile → Downloads": the download queue with the Downloads screen's actions and a link to settings — screenshot `211_profile_downloads`
- [x] Shikimori: the API on `https://shikimori.io` — the old `shikimori.one` redirects requests to another domain and the authorization header was lost (in 2.0.0 Shikimori sign-in and lists did not work)
- [x] Trackers: one token refresh for all simultaneous requests (Shikimori's refresh token is single-use), a token-refresh server error does not sign out — `TrackersTest`; queued changes are sent right after sign-in, no endless retries for services without sign-in; progress of two chapters finished in a row is submitted in order; entry updates no longer stop at the first error
- [x] WebDAV: reading without compression (`Accept-Encoding: identity`), weak ETags are not sent in `If-Match` — `WebDavSyncBackendTest`
- [x] Bulk metadata editing does not change the books' language when the language field was untouched
- [x] The 2.0.1 build: 319 tests, all green (3 live network tests run with `-Pyomu.liveTests=true`; the RanobeLib and NovelBin live tests passed), `lintDebug` — 0 errors
- [~] 2.0.1 device verification — not done

## Version 2.0.0 (Mihon import, trackers, novels, widget, editor, PDF, sync)

- [x] Library import from a Mihon/Tachiyomi backup (`.tachibk`, `.proto.gz`): titles, chapters, read marks, bookmarks, history, categories → tabs, trackers, extension repositories — `MihonImporterTest`
- [x] Shikimori, AniList, MyAnimeList trackers: OAuth sign-in, title search and binding, status/chapters/score, automatic progress updates, an offline queue — `TrackersTest`, `TrackingTest`; the database — schema 5 (the `tracks` table)
- [x] Online novels: the `sources:novels` module (RanobeLib, NovelBin), chapters in the book reader — `NovelSourcesTest`
- [x] The "Continue reading" home-screen widget — `Release200Test.continueReadingWidget`
- [x] The manga editor: page translation via OCR (recognize/circle → translate → paint → typeset), automatic translation of several pages — `TypesetMathTest`
- [x] Visual (WYSIWYG) EPUB chapter editing — saving valid XHTML: `EpubWorkspaceTest`
- [x] Bulk metadata editing of several books — `BatchMetadataTest`
- [x] Selected-chapter translation (in the editor and from the book card)
- [x] PDF highlights — in the database and in the file itself (an incremental update with standard annotations) — `PdfAnnotationWriterTest`, `PdfAnnotationStoreTest`
- [x] Sync 2.0: a shared folder or WebDAV, deletions, the online library, tabs, auto-sync — `SyncMergerTest`, `WebDavSyncBackendTest`
- [x] Long-press menus: radial/list/panel, customizable page actions
- [x] The 2.0 screens (tracker settings, sync, menus, Mihon import; the long-press menu; a title's tracking) — Robolectric screenshots `Release200Test`
- [x] The 2.0.0 build: 309 tests green, `lintDebug` — 0 errors
- [~] 2.0.0 device verification — not done (see [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md))

## Version 1.5.0 (per feedback: empty library, tabs and filters, new design)

- [x] "Your library is empty" with favorited titles: without on-device books the library shows online titles and a "No on-device books yet" card, the floating "Import" button does not duplicate the empty-screen button — screenshot `50_library_online_only` (checks that the "Your library is empty" text is absent)
- [x] Custom tabs for books and online titles instead of the format chips: create, rename, order, delete; choosing tabs on the heart tap, from the title menu and book selection; 1.0–1.4 catalog categories migrate into tabs (the 3 → 4 migration and old backups) — `DatabaseMigrationTest`, `BackupCompatibilityTest`, screenshot `51_library_user_tab`
- [x] The filter panel: type, status, tags, languages, sorting, view, columns; active filters as chips with "Reset all" — screenshot `52_library_filters`
- [x] The new design: a dark theme by default, a lavender accent on graphite, a floating navigation bar, "pills", cards; Settings → Appearance — a preview, 11 accents and a custom color, navigation labels — `PaletteTest` (WCAG AA contrast for all accents), screenshots `53_settings_appearance`, `54_settings_appearance_light`
- [~] 1.5.0 device verification — not done

## Version 1.4.0 (per feedback: chapters, library, traffic, filters)

- [x] A chapter number in reading order (1 — the first, copies from different groups share one), "Read X of N", "Downloaded N"; "Chapter 12 of 368" in the reader — `ChapterOrderTest` (positions), screenshot `40_title_chapter_numbers`
- [x] Favorited titles in "Library": the "Online (N)" tab and the "From the catalog" shelf — screenshots `41_library_online_shelf`, `42_library_online_tab`
- [x] Settings → Network and traffic: Wi-Fi only, the data saver, covers on mobile data, the ask-before-reading prompt, the traffic counter — `TrafficMeterTest`, screenshot `45_settings_network`
- [x] Speed: parallel downloads of a chapter's pages (1–6 threads), parallel preloading in the reader, 10 connections per site, a separate MangaDex image limit — `ChapterDownloadQueueTest`
- [x] Source filters: group cards with icons and a selection count, chips (include/exclude), search in long groups, chip sorting, active filters above results, search history — `SourceFilterStateTest`, screenshots `43_source_filters`, `44_source_filters_folded`, `47_source_filters_genres`
- [x] Icons in settings, the title menu and metadata; animations of lists, the heart, a chapter download and filter groups (turned off by "Fewer animations") — screenshot `46_settings_reader_icons`
- [~] 1.4.0 device verification — not done

## Version 1.3.1 (stability)

- [x] A screen-action error (buttons, menus, import, saving) does not close the app: a message and an error-log entry (the last 50 reports) — `AppErrorTest`
- [x] Incompatible and crashing extensions — an "Outdated source" error; a source's feed, global search and the extensions screen survive an extension and network-interceptor crash — `TachiyomiSourceErrorsTest`, `SourceCallsTest`
- [x] Without WebView — a "WebView unavailable" screen ("Retry", "Open in store") in the EPUB reader, site checks, the DOCX page layout and the editor
- [x] Out of space pauses the download queue with the reason "Not enough space" — `ChapterDownloadQueueTest.fullStoragePausesTheQueue`
- [x] Download notifications: a transition to Downloads, Pause, Retry and Continue buttons — `NotificationActionsTest`
- [x] A title without an installed source — "Source not installed" and a card with migration; Downloads screen fixes — `CatalogScreensTest`
- [x] PDF reader protection (closing during a render, an unreadable page size), webtoon feeds, the EPUB editor, corrupted settings, "Share" and links; the catalog does not get stuck loading
- [~] 1.3.1 device verification — not done

## Version 1.3.0 (sorting, source stability, downloads)

- [x] One chapter reading order (the list, reader, "Continue", downloads): the source list direction, chapters without a number, per-volume numbering, versions from different groups — `ChapterOrderTest`
- [x] Chapter sorting and filters for a title and "for all titles", group selection, "One chapter version"; selecting unread, a range, inversion
- [x] Catalog-library sorting and filters, source sorting and search — `LibraryDisplayTest`
- [x] Search result and book-link ranking — `SearchRankingTest`; no duplicates while scrolling a source
- [x] Timeouts, paused retries, source state ("not responding"/"slow"), a per-source retry in global search, a parallel update check — `SourceCallsTest`
- [x] Image checks by bytes (a web page is not saved as a manga page) — `ImageSnifferTest`
- [x] Downloads: "next N"/custom count/unread/all (one chapter version), deleting read/all, auto-download of new chapters, "download while reading", auto-retries, "to the top of the queue", per-title groups, clearing the queue, retrying failed, notifications, ≤ 2 downloads per site — `ChapterDownloadQueueTest`, `DownloadSectionsTest`
- [x] Chapter sync keeping marks on a source failure and address changes — `ChapterDownloadQueueTest`
- [x] The 2 → 3 DB migration — `DatabaseMigrationTest`
- [~] 1.3.0 device verification — not done

## Version 1.1.0 (rework per feedback)

- [x] The "No access to the file" error after downloading a book from the catalog — fixed, `DownloadedBookTest`
- [x] The MangaDex error "Unexpected JSON token… <!doctype html>" — fixed, `MangaDexSourceTest`
- [x] Mihon/Tachiyomi extensions: the compatibility layer, repositories (an address, GitHub, "Add to Mihon/Tachiyomi" links), signatures, updates, source settings
- [x] Built-in extensions verified live: MangaDex, MANGA Plus, Webtoons.com, GlobalComix, AComics (`TachiyomiExtensionsLiveTest`)
- [x] The DOCX page layout (A4 as printed) — `DocxLayoutTest`, compared with LibreOffice
- [x] Reader settings (presets, tabs, spacing, a custom theme) and position navigation (page N of M, go-to, a preview, "back")
- [x] Reading history (a tab) and statistics — `StatsCalculatorTest`
- [x] The 1 → 2 DB migration and restoring 1.0.0 backups — `DatabaseMigrationTest`, `BackupCompatibilityTest`
- [x] The 1.1.0 build: tests green (105, 1 skipped — a live network test), `lintDebug` — 0 errors (64 warnings), release signed with a permanent key
- [~] 1.1.0 device verification — not done (1.0.0 was run on a phone)

**General (for stages 0–9 below):** version 1.0.0 was run on the user's phone, the rest — by builds and tests. "Done" means: the code exists, is wired into the UI, the project builds, and the logic is covered by unit tests where noted. The end-to-end scenario is verified by a Robolectric smoke test (`AppSmokeTest`).

## Stage 0. Project skeleton

- [x] A Gradle project on Kotlin DSL, `libs.versions.toml`, convention plugins in `build-logic`, the modules from section 1
- [~] DI, DB, settings, logs, static analysis: **Koin instead of Hilt** (see DECISIONS), Room, DataStore, Coroutines, Timber, detekt + formatting rules (ktlint)
- [x] A Material 3 theme: light, dark, AMOLED, Material You, an accent color picker
- [x] Navigation: Library · Catalog · Dictionary · Settings tabs (a bottom bar / a tablet rail), the reader on top
- [x] Localization: `values/` (en) and `values-ru/`, an app language picker
- [x] CI on GitHub Actions (build, tests, lint), `README.md`, `LICENSE`, `CONTRIBUTING.md`, a `docs/` folder
- [x] `docs/PROGRESS.md`, `docs/DECISIONS.md`, `docs/KNOWN_LIMITATIONS.md`
- [ ] `stage N` commits per stage — the original run's git history was lost, the project is handed over without it

## Stage 1. Library and import

- [x] A Room schema (books, authors, series, collections, tags, progress, bookmarks, highlights, notes, words, dictionaries, sources, catalog, chapters, downloads etc.), FTS, schema export
- [x] SAF import: one file, several, a folder recursively
- [x] Automatic folder scans (WorkManager, scheduled and by button)
- [x] Format detection by content (signatures, archive structure) — `FormatDetectorTest`
- [x] Metadata: title, author, series and number, language, description, cover (EPUB, PDF, CBZ/ComicInfo, FB2)
- [x] Copying into app storage or linking via SAF
- [x] The library: grid and list, covers, progress, statuses
- [x] Sorting and filters (format, status, tag, language)
- [x] Collections, tags, bulk actions
- [x] Quick FTS search (title, author, series)
- [x] "Continue reading"
- [x] "Open with…", "Share" and "Find in Yomu" (VIEW/SEND/PROCESS_TEXT)
- [~] Unit tests: format detection, converters, the EPUB parser/builder, ComicInfo; SAF import and Room — only in the smoke test

## Stage 2. Format engines

- [x] EPUB: paged mode and scrolling, vertical text, RTL
- [x] Table of contents, jumps, internal links, pop-up footnotes
- [x] Full-screen illustrations with zoom
- [x] Position saving (a locator) into the DB periodically and on exit
- [x] PDF: rendering (PDFium), zoom, "page", "scroll", "dual page" modes
- [x] PDF: selection and text-layer search, the outline, passwords
- [~] PDF: margin crop (auto and manual), night inversion; reflow — text only, without images
- [x] Manga: left-to-right, right-to-left, vertical, webtoon
- [x] Dual pages in landscape, wide-page auto-detection
- [x] Zoom, double-tap, panning, preloading and page cache
- [x] Scale: by width, height, screen, original; white-margin crop
- [x] Controls: tap zones (customizable), swipes, volume keys
- [x] Filters: brightness, contrast, grayscale, invert
- [x] Large images: subsampling/tiling (telephoto + Coil)
- [~] FB2, TXT, HTML, DOCX, MOBI/AZW3 — EPUB conversion at import (DOCX/MOBI simplifications — in KNOWN_LIMITATIONS)

## Stage 3. Shared reader features

- [x] Text settings: fonts (own .ttf/.otf), size, spacing, margins, alignment, hyphenation, boldness, publisher styles
- [x] Themes: light, dark, sepia, black, gray, custom
- [x] In-reader brightness, a warm filter, a screen timeout
- [x] Fullscreen mode, system bars, the display cutout
- [x] A tap menu: contents, bookmarks, search, settings, translation, the editor
- [x] In-book search (EPUB, PDF, TXT) with highlighting and a list
- [x] A progress bar with percent and chapter, slider jumps, bookmark marks
- [x] Reading statistics: time, pages, speed, day streaks
- [x] TTS: the system synthesizer, voice, speed, sentence highlighting, a sleep timer
- [x] Auto-scroll with an adjustable speed
- [x] Screen orientation
- [x] An adaptive layout for tablets and foldables
- [x] Per-book settings and global defaults
- [~] A UI test: opening an EPUB in the smoke test; page turning, PDF and CBZ are not covered by automated tests

## Stage 4. Selection, notes, bookmarks

- [x] A floating selection panel (EPUB via a JS bridge, PDF by its own coordinates) with a customizable button set, custom buttons (URLs/apps)
- [x] A word tap: a mini card (definition, translation, "Save")
- [x] Highlights: locator + text + color + style + note + time; restored by text after a font change
- [x] A book "Annotations" screen: color and type filters, jumping to the place
- [x] A global quotes screen: search, tags, favorites, a quote image
- [x] Notes: Markdown with a preview, tags, on a selection or a page, editing, deleting
- [x] Bookmarks: by position, with a name, quick jumps, marks on the progress bar
- [x] Export: Markdown (Obsidian, YAML), JSON, HTML, TXT; "Share"
- [x] Readwise CSV: export and import
- [x] Manga and PDF: a page bookmark, a page note
- [~] Tests: export/import (`ExportersTest`); saving and restoring highlights in the engine are not covered

## Stage 5. Dictionary, translation, words

- [x] `TranslationEngine` and the engine manager
- [x] Offline: ML Kit + a language-pack screen (size, status, Wi-Fi only)
- [x] Online: Google (no key), DeepL, LibreTranslate, an OpenAI-compatible LLM
- [x] Language auto-detection, the default translation language from the system
- [x] The translation sheet: original/translation, engine and language switching, copy, speak, "Save"
- [x] Fragment and page translation with a DB cache
- [x] A bilingual EPUB mode
- [~] Manga translation: OCR (ML Kit) → translation → labels over bubbles (on page/bubble tap, cached); OCR quality depends on the model
- [x] Rate limiting, retries, clear network errors
- [x] Offline dictionaries StarDict, DSL, Wiktionary (kaikki), JMdict → Room FTS; a dictionary manager
- [x] Online dictionaries (Free Dictionary API, Wiktionary) as a fallback
- [~] en/ru lemmatization by rules, others — normalization and suffix stripping
- [~] Japanese: rule-based deinflection + JMdict (no Kuromoji tokenizer)
- [x] A definition mini-card: word, transcription, part of speech, meanings, examples, pronunciation, "Save"
- [x] A "Dictionary" screen: quick search and history
- [x] `Word` with context, book and position, tags, a level
- [x] A word list: search, filters, sorting, bulk actions, jumping to the place in the book
- [x] Saved-word highlighting in the text (a setting)
- [x] SM-2 cards: "word → translation", "translation → word", "fill in the word in context"
- [x] CSV export, Anki TSV, sending to AnkiDroid; CSV import
- [x] Tests: SM-2, lemmatization, dictionary imports (StarDict/DSL/Wiktionary/JMdict), the online engine on a fake server (MockWebServer)

## Stage 6. Editor

- [x] Book metadata (title, authors, series, language, description, tags, publisher), a cover from the gallery
- [x] Structure: the table of contents, chapter order, splitting and merging chapters
- [x] Chapter editing: source XHTML with highlighting and validation, and a visual (WYSIWYG) mode (2.0.0)
- [x] Find & replace per chapter and book, regex, a counter, a preview
- [x] Images: replace, remove, add
- [x] CSS: view and edit, cleanup presets
- [x] Cleanup: empty paragraphs, spaces, quotes and dashes
- [x] EPUB validation (structure, broken links, resources) with auto-fixes
- [x] Conversion: TXT/FB2/HTML → EPUB, EPUB → TXT/PDF
- [x] Translating a book into a new or bilingual EPUB in the background — whole book or selected chapters (2.0.0)
- [x] Bulk metadata editing of several books (2.0.0)
- [x] Manga: a page grid, reorder, delete, insert, rotation, dual-page split and merge
- [x] ComicInfo.xml and a cover
- [x] Margin crop (auto and manual), brightness, contrast, gamma, grayscale
- [x] Resizing and recompression (WebP/JPEG, quality, width)
- [~] Cleaning: painting a block area with the bubble color, white or black (2.0.0); no brush
- [x] Typesetting: text blocks over the page, size matched to the bubble (2.0.0)
- [x] Translation in the manga editor (page or area OCR → translation → a text block) (2.0.0)
- [~] Manga export: CBZ, PDF, EPUB (without an "image folder")
- [ ] Merging several chapters into a volume and splitting volumes
- [x] A separate toolbar, large buttons, page zoom
- [x] An unsaved-changes warning; undo/redo survives restarts
- [x] Tests: history, EPUB operations (saving, validation), manga (order, ComicInfo, CBZ export)

## Stage 7. Sources and catalog

- [~] `source-api` (SItem/SChapter/Page/FilterList) — based on Mihon, not binary-compatible
- [x] External extensions: installed APKs and repositories (DexClassLoader), signature trust, an "Extensions" screen
- [x] Built-ins: OPDS 1.2/2.0 (Gutenberg, Standard Ebooks, Feedbooks, ManyBooks, custom servers), Gutendex, Open Library/Internet Archive, Wikisource, MangaDex, a local folder
- [x] The "Catalog" tab: sources, pinned, languages; a source screen (popular/latest/search)
- [x] Filters (genres, status, sorting), saving the last filter
- [x] Global search across all sources in parallel, in groups
- [x] A title page: cover, description, genres, status, chapters with marks, "Add to library"
- [x] Favorites, categories, a scheduled chapter-update check (WorkManager)
- [x] History with returning to the place
- [x] Migration between sources carrying the progress
- [x] Online manga reading with the same engine (streaming, preloading, cache)
- [x] Online novel reading in the text engine
- [x] Network errors, retries, Cloudflare via WebView with cookies
- [x] Source tests on a fake server (MockWebServer): OPDS, MangaDex; an OPDS parser on samples

## Stage 8. Search and downloads

- [x] Books from OPDS and open libraries: a card, formats, downloading
- [x] One-button download into the library with metadata and a cover
- [x] A download queue (WorkManager + a notification): pause, resume, cancel, retry, a parallelism limit, Wi-Fi only
- [x] Chapters for offline: a range, "next N", "unread", new-chapter auto-download
- [x] Storage: a download folder, images or CBZ, space accounting, clearing read chapters
- [x] The "Downloads" screen
- [x] Resuming after a restart and a connection drop (HTTP Range)
- [x] One search: from the library — continue into the catalog
- [~] Download queue, resume and space tests — the chapter queue is covered by `ChapterDownloadQueueTest` (1.3.0), books by `DownloadedBookTest`; HTTP Range resumption and space accounting are not covered

## Stage 9. Settings, backup, polish, release

- [x] Settings with every section from the plan
- [x] A backup (DB, settings, annotations, words, optionally files) and restore; auto-backups with rotation
- [x] Sync through a shared folder (SAF) or WebDAV: a three-way merge, deletions as marks, the online library (2.0.0)
- [x] PIN/biometric lock, hiding from recents (FLAG_SECURE)
- [x] Private mode (incognito): reading without history
- [ ] Performance targets (start < 2 s, 1000+ books, large files) — not measured; Paging, indexes and the cover cache exist
- [~] Errors: clear messages, an error log with sending; behavior on damaged files is covered by parser tests, not on a device
- [~] Accessibility: content descriptions, touch targets, font scaling — without TalkBack verification
- [~] Compatibility: minSdk 26, targetSdk 36, phones/tablets, light/dark — without device verification
- [~] Tests: 91 tests (89 unit + 2 Robolectric smoke), all green; coverage below 60%, the UI scenarios "highlight and save a word", "bookmark", "download a chapter" are not automated
- [x] Release: R8 + shrinkResources, signing from environment variables, ProGuard rules
- [x] CI builds APKs and publishes artifacts; a release per tag; a release-notes template
- [x] A README: screenshots, features, building, extensions, dictionaries
- [x] ARCHITECTURE, EXTENSIONS, USER_GUIDE
- [x] Fastlane metadata (en-US, ru-RU), a privacy policy

## Final checklist

- [x] EPUB, PDF, CBZ/ZIP, FB2, TXT and an image folder open (the smoke test — EPUB; the rest — converter/container tests)
- [x] Manga modes: left-to-right, right-to-left, vertical, webtoon
- [x] A selection panel with all of stage 4's buttons, the set is customizable
- [x] Translation online and offline, the mode is chosen in settings
- [x] A word is saved in one tap with context, cards exist
- [x] Bookmarks, notes, quotes are created, searched and exported
- [~] The editor: EPUB metadata and chapters, manga pages, saving — yes; **cleaning and typesetting — no**
- [x] The catalog: OPDS and MangaDex, global search, favorites, updates, extensions
- [x] Downloading books and chapters, offline reading, the queue
- [x] Backup and restore
- [x] Builds: debug and release APKs, tests green; `lintDebug` — 0 errors (52 warnings in 1.0.0, 64 in 1.1.0, see `KNOWN_LIMITATIONS.md`)
- [x] `PROGRESS.md` is filled honestly, `KNOWN_LIMITATIONS.md` describes the simplifications

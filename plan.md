# Plan: an Android reader-editor in Kotlin (EPUB, PDF, manga, novels)

Nov 3, 2026 · @shinoame

## 0. Main instruction to the agent (read first)

You are building an open-source Android app in Kotlin: a reader and editor for books, novels and manga. **Complete ALL stages 0–9 in a row in one continuous run.** Do not stop between stages, do not wait for confirmation, do not ask "should I do the next stage". When a stage is finished and verified, start the next one immediately.

### Working rules

1. **Do not ask questions.** If something is unclear, pick the most reasonable option from this plan, record the decision in one line in `docs/DECISIONS.md` and move on.
2. **Do not stop on errors.** A build failed: read the log, fix it, rebuild. Return to the plan only after a green build.
3. **After each stage:** `./gradlew assembleDebug` and `./gradlew test` must pass. Then commit `stage N: <name>` and start the next stage immediately.
4. **No stubs instead of features.** Do not leave `TODO()`, empty screens or buttons without action. If a feature is complex, make a working simplified version and record the limitation in `docs/KNOWN_LIMITATIONS.md`.
5. **A blocking problem** (a library does not work, an API is unavailable): use the fallback from the plan, record it in `docs/DECISIONS.md` and continue.
6. **Keep the log:** mark `[x]` in `docs/PROGRESS.md` as items complete. In the end this file should show everything is done.
7. **Interface language:** Russian and English (strings only in `strings.xml`, no hardcoding).
8. **Code:** Kotlin, idiomatic, without deprecated APIs, dependencies only through `libs.versions.toml`.

### Completion criteria for the whole run

The project builds debug and release APKs, tests are green, every item of the section-9 checklist is checked, `README.md` describes installation and features. Only then write the final report.

## 1. Product and stack

**Product:** one app with three modes: a reader (books, novels, PDF, manga), an editor (books and manga) and a source catalog (like Mihon). License: **Apache-2.0** (if Readium/other libraries require otherwise, pick a compatible one, e.g. GPLv3, and record it in `DECISIONS.md`).

### Technologies

| Area | Choice | Fallback |
| --- | --- | --- |
| Language, UI | Kotlin, Jetpack Compose, Material 3 | none |
| Architecture | Clean + MVVM, unidirectional state flow (StateFlow) | none |
| DI | Hilt | Koin |
| DB | Room (+ FTS4 for search) | none |
| Settings | DataStore | none |
| Async | Coroutines, Flow | none |
| Background | WorkManager | none |
| Network | OkHttp + Retrofit, kotlinx.serialization | Ktor |
| Images | Coil 3 | none |
| EPUB/novels | Readium Kotlin Toolkit | a custom ZIP + Jsoup + WebView parser |
| PDF | a PdfiumAndroid fork (text, search) | android.graphics.pdf.PdfRenderer (no text) |
| Manga | a custom Compose pager, CBZ/ZIP/CBR/7z archives | none |
| Offline translation | ML Kit Translation | Bergamot/Firefox models via JNI if ML Kit is unavailable |
| Online translation | Google Translate (unofficial), DeepL API, LibreTranslate, a user LLM endpoint | none |
| Offline dictionary | Wiktionary dumps (kaikki.org) + StarDict/DSL import | none |
| Online dictionary | Free Dictionary API, Wiktionary REST | none |
| OCR (for manga) | ML Kit Text Recognition (Japanese, Chinese, Korean, Latin) | Tesseract |
| Tests | JUnit5, MockK, Turbine, Compose UI test | none |

SDK: `minSdk 26`, `targetSdk` and `compileSdk` — the latest stable at build time.

### Gradle modules

- `app`: the entry point, navigation, theme
- `core:common`, `core:ui`, `core:database`, `core:datastore`, `core:network`
- `data`: repositories, data sources
- `domain`: models and use cases
- `feature:library`, `feature:reader-epub`, `feature:reader-pdf`, `feature:reader-manga`
- `feature:annotations`, `feature:dictionary`, `feature:translator`
- `feature:editor`, `feature:catalog`, `feature:downloads`, `feature:settings`
- `source-api`: source interfaces (a separate module so extensions can depend on it)
- `sources:opds`, `sources:mangadex`, `sources:local`

## UI/UX: principles and screens (applies to all stages)

The agent builds the interface as a thought-out product right away, not "polish later". The design system is created at stage 0 in the `core:ui` module and every new screen is assembled only from its components.

### Principles

1. **Reading first.** Nothing blocks the text in the reader: panels are hidden, appear on a center tap and hide themselves after a few seconds.
2. **One hand.** Main actions in the bottom third of the screen: a bottom navigation bar, bottom sheets instead of centered dialogs, large touch targets (at least 48dp).
3. **Fewest steps.** Saving a word, a bookmark, a quote: no more than two touches.
4. **Predictability.** One Material 3, the same gestures and icons on every screen, familiar patterns of Mihon, Moon+ Reader, Librera.
5. **Instant response.** Skeletons instead of spinners, smooth animations (up to 300 ms), optimistic updates (a "read" mark without waiting).
6. **Everything is configurable, but with good defaults.** A beginner does not need to open settings.

### Design system (`core:ui`)

- [ ] Colors: Material You (dynamic), light, dark, AMOLED black; text contrast at least WCAG AA
- [ ] Typography: the Material 3 scale, a separate scale for book text
- [ ] Shapes, margins, elevations on a 4/8dp grid; one icon set (Material Symbols)
- [ ] Ready components: a book and manga card, a cover tile, a BottomSheet, a search field, filter chips, an empty state, an error state with a "Retry" button, a skeleton, a snackbar with "Undo"
- [ ] Animations: screen transitions (a shared element for covers: from the grid to the title page and the reader), smooth reader panel appearance
- [ ] `@Preview` of every component (light/dark, large font)

### Navigation and screens

| Screen | Key UX elements |
| --- | --- |
| Library | Grid and list (a switch), "Continue reading" on top, a search row, filter chips, long-press enables multi-select, a bulk bar at the bottom |
| Reader | Fullscreen, a top bar (back, bookmark, menu) and a bottom bar (progress slider, chapter, settings, translation); on a center tap |
| Selection | A floating panel over the selection, selection handles, a translation sheet at the bottom (not covering the selected text) |
| Manga | Tap zones at the edges (customizable), a page slider, "Next/previous chapter" buttons at the end, a "chapter read" indicator |
| Catalog | A cover grid, an "in library" badge, filters in a sheet, global search grouped by sources |
| Title page | A large cover with a blurred background, a collapsible description, a chapter list with marks, a floating "Continue" button |
| Dictionary | Search at the very top, history, "Dictionaries" and "My words" tabs |
| Word repetition | A full-screen card, "know/don't know" swipes, a daily counter |
| Editor | Its own toolbar, a dark theme by default, zoom and pan gestures, an unsaved-changes indicator |
| Settings | Settings search, sections with icons, an explanation under each item |

### Tablets, foldables, landscape

- [ ] An adaptive layout (WindowSizeClass): on wide screens a navigation rail instead of the bottom bar, a two-pane "list + content" mode, two pages in the reader
- [ ] Correct rotation without losing position and state

### States and feedback

- [ ] Every screen implements 4 states: loading, data, empty, error (with a clear text and action)
- [ ] First launch: a short onboarding (3 screens: what it does, picking book folders, picking the translation language), skippable
- [ ] First-use hints for gestures (a word tap, manga tap zones), shown once
- [ ] Haptics on key actions (saving a word, a bookmark), disabled in settings
- [ ] Undo for dangerous actions via a snackbar, confirmation only for irreversible ones (deleting a file from the device)

### Accessibility and language

- [ ] TalkBack support, `contentDescription` on all icons, focus order, system font scaling up to 200%
- [ ] RTL interface support
- [ ] Color is never the only carrier of meaning (a color highlight is duplicated with an icon or underline)

### The agent's UX check

- [ ] Every screen checked in light and dark themes, portrait and landscape, with a large font
- [ ] Screenshot tests of key screens (Paparazzi or Roborazzi)
- [ ] Walk the scenarios: "open a book and save a word", "add manga from the catalog and download a chapter", "highlight a quote and export" and make sure each takes no more than the expected number of touches

## 2. Stages 0–1: skeleton, database, library, import

### Stage 0. Project skeleton

- [ ] Create a Gradle project (Kotlin DSL, the `libs.versions.toml` catalog), the modules from section 1
- [ ] Set up Hilt, Room, DataStore, Coroutines, Timber (logs), ktlint/detekt
- [ ] A Material 3 theme: light, dark, AMOLED, dynamic colors (Material You), an accent color picker
- [ ] Navigation (Navigation Compose): a bottom bar of **Library · Catalog · Dictionary · Settings** tabs, the reading screen opens on top
- [ ] Localization: `values/` (en) and `values-ru/`
- [ ] CI: GitHub Actions (build, tests, lint), `README.md`, `LICENSE`, `CONTRIBUTING.md`, a `docs/` folder
- [ ] `docs/PROGRESS.md`, `docs/DECISIONS.md`, `docs/KNOWN_LIMITATIONS.md`

### Stage 1. Library and file import

**DB schema (Room):** `Book`, `Author`, `Series`, `Collection`, `Tag`, `ReadingProgress`, `Bookmark`, `Highlight`, `Note`, `Word`, `DictionaryEntry`, `Source`, `CatalogItem`, `Chapter`, `DownloadTask`. Migrations and schema export enabled.

- [ ] File import via the Storage Access Framework (one file, several, a folder recursively)
- [ ] Auto-scanning of chosen folders (WorkManager, scheduled and by button)
- [ ] Format detection by content, not just the extension
- [ ] Metadata extraction: title, author, series and number, language, description, cover (EPUB, PDF, CBZ, FB2)
- [ ] Copying into app storage or linking via SAF (a setting)
- [ ] The library screen: grid and list, covers, a progress bar on the card, status (new / reading / finished)
- [ ] Sorting (title, author, date added, last read), filters (format, status, tag, language)
- [ ] Collections and tags, bulk actions (delete, add to a collection, mark finished)
- [ ] Quick library search (title, author, series) via FTS
- [ ] A "Continue reading" section on top
- [ ] Opening a book via "Open with…" from other apps (an intent-filter for epub, pdf, cbz, cbr, fb2, mobi, txt)
- [ ] Unit tests of import and metadata

## 3. Stages 2–3: reading engines and all reader features

### Stage 2. Format engines

**EPUB / novels (Readium):**

- [ ] Modes: paged and continuous scroll, vertical text (for Japanese), RTL
- [ ] Table of contents, chapter jumps, internal links, footnotes in a pop-up
- [ ] Novel illustrations: full-screen viewing with zoom
- [ ] Position saving (CFI/locator), progress synced to the DB every few seconds and on exit

**PDF:**

- [ ] Page rendering, pinch zoom, modes: page-by-page, scrolling, dual-page (on tablets)
- [ ] Text layer: selection, search, the outline
- [ ] Margin crop (auto and manual), night mode (inversion), text reflow in a simplified form

**Manga and comics (CBZ, ZIP, CBR, 7z, an image folder, EPUB manga, PDF manga):**

- [ ] Modes: left-to-right, right-to-left (the manga default), vertical, webtoon (a continuous feed)
- [ ] A dual-page spread in landscape, wide-page auto-detection
- [ ] Zoom, double-tap, panning; neighbour preloading, memory and disk cache
- [ ] Scale: by width, height, screen, original; white-margin crop
- [ ] Controls: edge taps, swipes, volume keys; customizable tap zones
- [ ] Image filters: brightness, contrast, grayscale, invert
- [ ] Correct handling of very large images (tile decoding, downsampling)

**Extra formats:** FB2, TXT, HTML, MOBI/AZW3 (via EPUB conversion at import, e.g. with a Kotlin/Java library; if it fails, record in `KNOWN_LIMITATIONS.md`), DOCX (read as text).

### Stage 3. Shared reader features

Shared across formats: a single `ReaderViewModel` and a `ReaderEngine` abstraction.

- [ ] Text settings: font (system + own .ttf/.otf), size, line spacing, margins, alignment, hyphenation, boldness
- [ ] Reading themes: light, dark, sepia, black, gray, custom (background and text colors)
- [ ] In-reader brightness, a warm filter (night mode), a screen auto-lock
- [ ] Fullscreen, hiding system bars, cutout support
- [ ] A center-tap menu: contents, bookmarks, search, settings, translation, the editor
- [ ] In-book text search (EPUB, PDF, TXT) with result highlighting and a list
- [ ] A scrollbar with percent and chapter number, slider jumps
- [ ] Reading statistics: time, pages, speed, day streaks; a statistics screen
- [ ] Read-aloud (TTS): the system synthesizer, voice and speed choice, sentence highlighting, a sleep timer
- [ ] Auto-scroll with an adjustable speed
- [ ] Screen orientation: auto, portrait, landscape, locked
- [ ] Tablet and foldable support (an adaptive layout)
- [ ] Per-book settings and global defaults
- [ ] Tests: a UI test of opening and paging EPUB, PDF, CBZ

## 4. Stage 4: text selection, the action menu, quotes, notes, bookmarks

### The selection context menu (a key feature)

When text is selected (EPUB, PDF, TXT, FB2, OCR-mode manga text) a **custom floating panel** shows over the selection instead of the system menu. The panel scrolls horizontally if the buttons do not fit and has a "⋯" button for the rest. The set and order of buttons are configured in Settings (enable, disable, reorder).

| Button | Action |
| --- | --- |
| Highlight colors | 5 colors in one tap (yellow, green, blue, pink, purple), keeps the selection |
| Underline / strike-through | Alternative highlight styles |
| Note | Opens a note field for the selection |
| Quote | Saves the quote to the quotes collection (with the book title, chapter, position) |
| Definition | A mini window with the word's dictionary entry (offline, online as fallback) |
| Translate | Translates the selected fragment in a bottom sheet (engine and language switchable right there) |
| Save word | Adds the word with its context sentence to the user's dictionary in one tap |
| Copy | To the clipboard |
| Share | The system menu; a "Share quote as image" option (a card with text, title and author) |
| Search in book | Searches the selection across the book |
| Search the web | The chosen search engine, Wikipedia, a custom URL template |
| Read aloud | TTS from this place |
| Select the whole sentence / paragraph | Quickly extends the selection |
| Bookmark | A bookmark at the selection position |
| Custom | Your buttons: a URL template with `{text}` substitution, sending text to a third-party app (e.g. AnkiDroid, Yandex Translate) |

**The word-tap gesture:** a single tap and hold on a word opens a mini panel (definition + translation + "Save") without full selection. Behavior (tap, hold, double-tap) is configurable.

### Task list

- [ ] Implement the panel over Readium (a JS bridge + a native Compose pop-up) and in the PDF engine (its own selection coordinates)
- [ ] Store highlights as locator + text range + color + note + time; restore after font and size changes
- [ ] A per-book "Annotations" screen: highlights, notes, quotes as a list, a color filter, a tap leads to the place in the text
- [ ] A library-wide "Quotes" screen: search, tags, favorites
- [ ] Notes: Markdown text, tags, attached to a selection or a page, editing, deletion
- [ ] Bookmarks: at the current position, with a name, quick jumps, marks on the progress bar
- [ ] Export of a book's and the whole library's annotations: Markdown, JSON, HTML, TXT; via "Share"; an Obsidian-compatible format (a YAML header)
- [ ] Highlight import/export in a format Readwise understands (CSV)
- [ ] Manga bookmarks and notes: a page bookmark, a page note
- [ ] Tests: highlight save and restore, export

## 5. Stage 5: dictionary, translator (online and offline), saved words

### Translator

In settings the user picks a **mode**: "Offline only", "Online only", "Offline first, online on failure" (the default) and a specific engine.

- [ ] A `TranslationEngine` interface (`translate(text, from, to)`, `detectLanguage`, `supportedLanguages`, `isOffline`) and an engine manager
- [ ] Offline: ML Kit Translation with a language-pack download/remove screen (size, status, Wi-Fi-only download)
- [ ] Online: Google (no key), DeepL (user key), LibreTranslate (own address), a custom LLM endpoint in an OpenAI-compatible format (address, key, model, a system prompt in settings)
- [ ] Book-language auto-detection and the default translation language (from the system)
- [ ] The translation sheet: original, translation, engine and language switching, copy, speak both texts, a "Save" button
- [ ] "Paragraph translation" and "whole page translation" modes (results cached in the DB, so nothing is translated twice)
- [ ] A bilingual EPUB mode: translation under each paragraph (enabled by a button)
- [ ] Manga translation: OCR (ML Kit) → translation → text laid over the bubble (a per-bubble-tap mode; the result is cached)
- [ ] Request rate limiting, retries on error, a clear message when there is no network

### Dictionary and quick lookups

- [ ] Offline dictionaries: StarDict (.ifo/.idx/.dict), DSL and Wiktionary (JSON) imports, indexed by Room FTS or a custom binary index for speed; a dictionary manager (enable, order, delete)
- [ ] Online dictionaries: Free Dictionary API, Wiktionary; a fallback when a word is missing offline
- [ ] Lemmatization and word-form search (English, Russian; for others at least lowercasing and suffix stripping)
- [ ] Japanese: tokenization (e.g. Kuromoji or similar), readings and meanings (JMdict import)
- [ ] A definition mini-card: word, transcription, part of speech, meanings, examples, a speak button, a "Save" button
- [ ] A "Dictionary" screen: a quick-search row (an answer in fractions of a second), a query history

### Saved words and repetition

- [ ] A `Word` entity: word, translation, definition, context sentence, book and position, tags, date, knowledge level
- [ ] A word list: search, filters by book, tag and level, sorting, bulk actions
- [ ] Tapping a word in the list opens the book at the place it appeared
- [ ] Highlighting already-saved words in the book text (a setting)
- [ ] Repetition cards: a spaced-repetition algorithm (SM-2 or FSRS), modes "word → translation", "translation → word", "fill in the word in context"; a daily goal and a reminder
- [ ] Word export: CSV, Anki (.apkg or TSV), sending to AnkiDroid via its API
- [ ] Word import from CSV
- [ ] Tests: translation engines (with fake responses), the dictionary index, the repetition algorithm

## 6. Stage 6: the editor mode (a separate mode, for text and manga)

The editor opens from a separate button in the book menu and the reader. It always works **on a copy** of the book (the original does not change until the user taps "Save as…" or "Replace original"). There is a change history (undo/redo, at least 50 steps) and draft autosave.

### Book editor (EPUB, FB2, TXT)

- [ ] Metadata: title, author(s), series, number, language, description, tags, publisher, cover (from the gallery or camera)
- [ ] Structure: the table of contents (add, delete, rename, drag chapters), chapter order, splitting and merging chapters
- [ ] Chapter text editing: visual (WYSIWYG: bold, italic, headings, lists, links) and source mode (HTML/XHTML with syntax highlighting)
- [ ] Find & replace per chapter and the whole book (plain text and regex), a replacement counter, a preview
- [ ] Images: replace, delete, add an illustration
- [ ] Styles (CSS): viewing and editing the style sheet, ready cleanup presets ("remove embedded fonts and colors")
- [ ] Cleanup: removing empty paragraphs, extra spaces, fixing quotes and dashes, hyphenation
- [ ] EPUB validation (structure, broken links, missing resources) with a problem list and auto-fixes
- [ ] Conversion: TXT/FB2/HTML → EPUB, EPUB → TXT/PDF (simple)
- [ ] Book translation: pick chapters → translate with the chosen engine → save as a new EPUB or a bilingual EPUB; progress in the background via WorkManager
- [ ] Bulk metadata editing of several books

### Manga editor (CBZ, ZIP, a folder)

- [ ] A page manager: a thumbnail grid, reorder, delete, insert, rotate, splitting a spread into two pages, merging
- [ ] Metadata: ComicInfo.xml (title, series, number, author, genre, language), cover
- [ ] Margin crop and framing (auto and manual), brightness, contrast, level balance, grayscale correction
- [ ] Resizing and recompression (WebP/JPEG, quality, max width) to save space, batch across a chapter
- [ ] Cleaning (clening): a brush and fill for painting text in bubbles (an eyedropper color, brush size, undo)
- [ ] Typesetting: adding text blocks over the page (font, size, color, outline, alignment), dragging, rotation
- [ ] Translation in the editor: OCR of a selected area → translation → inserting a text block; a batch "translate the whole chapter" mode with manual review
- [ ] Export: CBZ, PDF, an image folder, EPUB (a manga version)
- [ ] Merging several chapters into one volume and splitting a volume into chapters

### Shared for the editor

- [ ] A separate toolbar, a dark theme, large phone-sized buttons, zoom and pan gestures
- [ ] An unsaved-changes warning on exit
- [ ] Tests: saving an EPUB after edits (opens without errors), ComicInfo, CBZ export

## 7. Stages 7–8: Mihon-like sources, catalog, search and downloads

### Stage 7. The source system and catalog

The app **contains no code of specific pirate sites**. It has a source mechanism (as in Mihon): a source is described by an interface and is connected by a built-in module or an external extension the user installs. Built-in sources are legal only.

**The source interface (`source-api`):**

- `id`, `name`, `lang`, `supportsLatest`, `supportsSearch`
- `getPopular(page)`, `getLatest(page)`, `search(query, filters, page)`
- `getDetails(item)`, `getChapters(item)`, `getPages(chapter)` for manga and `getContent(chapter)` for novels
- `getDownloadLinks(book)` for books (OPDS-style)
- A filter list (genres, status, sorting, year) is described declaratively, the UI is built from it automatically

**Tasks:**

- [ ] A `source-api` module with interfaces and models (`SManga`, `SChapter`, `SBook`, `Page`, `FilterList`)
- [ ] Loading external extensions: separate APKs with a shared interface via `PackageManager` and `DexClassLoader`/isolated classloaders; an "Extensions" screen (installed, available, updates); signatures and a source warning; **extension repositories** are added by the user by URL (a JSON index)
- [ ] Built-in sources: **OPDS** (any OPDS 1.2/2.0 catalog, e.g. Project Gutenberg, Standard Ebooks, Internet Archive, Flibusta-like ones by user address), **MangaDex** (the official API), a **Local source** (a phone folder with a "Manga/Chapter" structure)
- [ ] A "Catalog" tab: the source list, pinned, a language filter; a source screen with "Popular", "Latest", "Search" tabs; a cover grid with infinite scrolling (Paging 3)
- [ ] A filter panel (genres, tags, status, sorting), saving the last filter
- [ ] Global search across all sources at once: results grouped by sources, loaded in parallel
- [ ] A title page: cover, description, author, genres, status, a chapter list with read and downloaded marks, "Add to library", "Continue", chapter sorting
- [ ] "Favorites" (the manga library), categories, chapter updates: WorkManager checks new chapters on a schedule (default every 6 hours; Wi-Fi or any network, a choice), a new-chapter notification, an "Updates" tab
- [ ] "History": recently read with returning to the place
- [ ] Title migration between sources (carries progress and marks)
- [ ] Reading manga straight from an online source (page streaming, preloading, cache) with the same engine as a local one
- [ ] Reading novels from an online source: a chapter loads into the same text engine with all features (selection, translation, dictionary)
- [ ] Network error handling, retries, clear messages, Cloudflare/WebView checks: opening the page in a WebView to pass the check and carrying the cookies over
- [ ] Source tests on fake server responses (MockWebServer)

### Stage 8. Book search and downloads

- [ ] A "Books" section in the catalog: search across OPDS sources and open libraries (Gutenberg, Standard Ebooks, Internet Archive, Open Library, Wikisource); a book card with download formats (EPUB, PDF, FB2)
- [ ] One-button book download into the library with automatic metadata and cover import
- [ ] A download queue (WorkManager + a progress notification): pause, resume, cancel, retry, a simultaneous-download limit, Wi-Fi only (a setting)
- [ ] Downloading manga and novel chapters for offline: choosing a chapter range, "download the next N", "download unread", automatic downloads of new chapters for favorites
- [ ] Storage: a download folder (SAF), an "image folder" format or packing into CBZ, space accounting, automatic clearing of read chapters (a setting)
- [ ] A "Downloads" screen: active, queue, completed, management
- [ ] Resuming a download after an app restart and a network loss (HTTP Range)
- [ ] A single search: the Library tab's search field searches local books and can continue into the catalog
- [ ] Tests: the download queue, resumption, space accounting

## 8. Stage 9: settings, backup, polish, release, the final checklist

### Settings, backup, sync

- [ ] A settings screen with sections: Appearance, Library, Reader, Manga, Selection and menu buttons, Translation, Dictionaries, Catalog and extensions, Downloads, Storage, Backups, About
- [ ] A backup: exporting the whole DB, settings, annotations and words into one file (zip/JSON) and restoring; scheduled auto-backups into a chosen folder keeping the last N copies
- [ ] Syncing progress and annotations between devices: through a file in a user's cloud folder (Syncthing/WebDAV/Google Drive via SAF) without an own server
- [ ] App locking (biometrics/PIN), hiding contents from recents
- [ ] A kids/private mode: reading without writing history

### Quality and performance

- [ ] A cold start under 2 seconds on a mid-range phone; smooth scrolling of a 1000+ book library (cover cache, Paging, DB indexes)
- [ ] Large files: a 500+ page PDF, a 2 GB CBZ, a 20 MB EPUB open without crashes or OOM
- [ ] Error handling: damaged files, missing permissions, no space; not a single unhandled exception in user scenarios; an "Error log" screen with report sending
- [ ] Accessibility: content descriptions, large system fonts, TalkBack navigation
- [ ] Compatibility: Android 8–15 and newer, phones and tablets, light and dark themes
- [ ] Tests: unit tests of the data layer and use cases, UI tests of the main scenarios (open a book, highlight and save a word, add a bookmark, download a chapter), at least 60% coverage of critical logic

### Release and documentation

- [ ] A signed release build (a keystore via environment variables, no secrets in the repository), R8/ProGuard rules for Readium, Room, Hilt, extensions
- [ ] CI builds the APK and publishes the artifact; a GitHub release template
- [ ] `README.md`: placeholder screenshots, a feature list, building, how to write extension sources, how to add a dictionary
- [ ] `docs/ARCHITECTURE.md` (a module and data-flow diagram), `docs/EXTENSIONS.md` (a `source-api` guide), `docs/USER_GUIDE.md`
- [ ] F-Droid metadata (a fastlane structure: descriptions, icon), a privacy policy (the app collects nothing, the network is used only by user action)

### The final checklist (the agent checks itself before the report)

- [ ] EPUB, PDF, CBZ/ZIP, FB2, TXT and an image folder open and are readable
- [ ] Manga modes: left-to-right, right-to-left, vertical, webtoon
- [ ] Text selection shows a floating panel with all of section 4's buttons, the set is customizable
- [ ] Translation works online and offline, the mode is chosen in settings
- [ ] A word is saved in one tap with context, repetition cards exist
- [ ] Bookmarks, notes, quotes are created, searched and exported
- [ ] The editor: EPUB metadata and chapter editing, manga page editing, cleaning and typesetting, saving
- [ ] The catalog: OPDS and MangaDex sources, global search, favorites, chapter updates, extensions
- [ ] Downloading books and chapters, offline reading, the download queue
- [ ] Backup and restore
- [ ] Builds: debug and release APKs, tests green, lint without errors
- [ ] `docs/PROGRESS.md` is fully checked, `docs/KNOWN_LIMITATIONS.md` honestly describes the simplifications

### The agent's final report

At the end the agent outputs a short report: what was implemented per stage, what was simplified and why, how to build and run, a list of known limitations. After that the work is considered complete.

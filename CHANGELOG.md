# Changelog

## Unreleased — audiobooks

- **Audiobook stability and speed**: playback recovers by itself after losing the connection (retries with growing pauses, "No connection, trying again…"), streams buffer up to three minutes ahead and keep the last 30 s for rewinding, YouTube addresses still valid are reused when a book reopens, and a stale stream lookup can no longer corrupt the next book. Chapter reading loads only the chapter boxes and frames (no cover art or sample tables), formats without chapters are not opened, and the player screen polls the position only while playing and visible.
- **Audiobook player**: fine speed steps (0.5×–3×, ±0.05), the time left in the book, a sleep timer that fades out, a short rewind when resuming after a pause, and back 10 s / forward 30 s in the notification. Player settings now keep the book's other reader settings.
- **YouTube search and stream refresh**: type words in "Audiobook by link" to search YouTube and pick a video or playlist; playlists get the channel and description; stream addresses are refreshed before they expire instead of failing after a long pause.
- **Audiobooks**: your own audio files, folders of tracks and ZIPs of MP3s; LibriVox in the catalog (listen online or download the ZIP); YouTube videos and playlists, podcast feeds and audio links by URL or "Share → Listen in Yomu"; NotebookLM audio overviews via download/share. A player with chapters, speed, sleep timer, background playback with notification and lock-screen controls, a "now playing" bar, saved positions and listening time in the statistics. Verified with unit tests and builds; not yet on a device or against live YouTube.
- **Audiobook chapters and YouTube**: chapters embedded in M4B/M4A (QuickTime chapter track, Nero) and MP3 (ID3v2 `CHAP`) files appear in the chapter list. YouTube videos take the chapters YouTube shows (also automatic ones) before description timestamps, keep a title when the player asks for a sign-in, and play dubbed videos in their original voice; playlist videos get their lengths. A "Skip silence" switch in the player, kept per book. Covered by unit tests on synthetic files and recorded YouTube answers; not checked on a device or against live YouTube.

## Unreleased — GitHub sync, incognito everywhere, hidden extensions, Anki decks, notifications

- **Detailed sync settings**: Settings → Sync has a switch per category — reading positions, highlights and notes, bookmarks, words, online library, tracker links, book files, deletions — plus "Only over Wi-Fi" for automatic syncs. A category switched off is neither sent nor applied on that device, and the records other devices shared stay untouched.
- **Notifications**: a new Settings → Notifications section — permission status, system settings, a switch per kind (new chapters, downloads, sync problems, backups, finished tasks, review reminders), quiet hours (silent delivery), the list of recent notifications (also the muted ones) and a test notification. Automatic sync that keeps failing now notifies.
- **Hide covers** (Settings → Library): a coloured placeholder with the title instead of covers everywhere, the widget included.
- **Popular extension repositories**: the Repositories tab offers Keiyoushi, Yūzōnō, Zosetsu, Kavita and Suwayomi; adding one still shows its name, size and signing key first.
- **Reader**: a quick display panel in the reader menu (brightness with Auto, warm filter, keep screen on; font size and theme for books), a page thumbnail while dragging the manga slider, and double-tap zoom in webtoon mode.
- **Sync through GitHub**: a new sync method stores the sync files in a private GitHub repository (Contents API; the blob SHA is the compare-and-swap version, like a WebDAV ETag). Enter a personal access token and `owner/name`, or let the app create a private `yomu-sync` repository. Tracker links sync too, and so do book files and image folders (a full sync like WebDAV: `books/yomu-book-<sha256>.bin`, streamed and verified by reading back; GitHub's 100 MB per-file limit applies, a larger book is reported as a problem). The token is never exported to backups.
- **Incognito mode** now covers local books too (no saved position, reading history or statistics) and is visible above every screen except the reader, with a "Turn off" button. It can be switched from the top of the settings root and from the ⋮ menu of the library.
- **Hide extensions and sources**: the eye button in Extensions hides all sources of an extension from the catalog and the global search; the extension sheet can pin, disable or hide each source; a source's menu hides it alone. "Show hidden" in the source filters lists them again, Settings → Catalog shows the counts and restores everything.
- **Export words**: a ready **Anki deck (.apkg)** for Anki/AnkiDroid/AnkiMobile (re-exports update the same notes), the Anki text file, CSV, Quizlet/Memrise (tab-separated), JSON and Markdown. Highlights can be exported as an Anki deck or an Anki text file as well.
- Tested with MockWebServer and Robolectric only: no real GitHub account or Anki import was exercised.
- **UI/UX pass**:
  - Reader: brightness and warm-filter swipes along the left/right screen edge (text and PDF, with a live percent pill; can be switched off in Controls); a page-turn animation choice for books (slide, fade, none); an automatic night theme that follows the app's dark mode; tap-zone layouts for books (L-shape, Kindle, edges, left/right, off) like the manga reader.
  - Lists: swipe actions — delete notes, bookmarks and saved words (with Undo), remove history entries, cancel/remove/retry downloads. Pull down in the library to rescan the scan folders.
  - Book and title pages are tinted with the cover's colour (the placeholder's colour for books without a cover). On wide screens (≥ 840 dp) a book's details open in a side pane next to the library.
  - Predictive back: the screen shrinks away with the gesture. Haptics for switches, stepped sliders and swipe thresholds. One-time hints for multi-select, edge swipes and list swipes.
  - Polish: a fade under the floating navigation bar, a new empty state, icon circles on profile tiles, the dictionary search in the same style as the other search fields, dimmed disabled switches, a label for the password visibility button.
  - Verified with unit and Robolectric screenshot tests only, not on a device.
- **Navigation and gestures**:
  - Settings search finds options by name and description (both languages) and shows where each one is; a result opens its section scrolled to the option and briefly highlights it.
  - Fast scroll in the library grid and list (shown on long libraries): a side thumb with a bubble that follows the sort — first letter, author, month or progress.
  - Predictive back between tabs: going back to the start tab previews it under the shrinking current tab.
  - Swipes on library list rows (read/unread, remove with Undo — files are never deleted by a swipe) and on chapter rows (read/unread, delete the download with Undo or queue it).
  - Haptics when multi-select starts, on bulk actions and when the reader moves to another chapter (the general Haptics switch turns them all off).
  - Tablets (≥ 840 dp): Settings show the root list with search beside the open section; a source's titles open in a side pane next to its grid.
  - Book and title pages take their background and accent colours from the cover, not just the header wash.
  - The text and PDF readers show their real tap zones (and the edge-swipe strips) once on first open, followed by the selection and menu hints.
  - Accessibility: merged TalkBack descriptions for book tiles, list rows and settings rows; toggle rows exposed as switches/checkboxes; 48 dp touch targets for small icon buttons; heights that grow with large system fonts on headers, chips and badges.
  - Verified with unit, lint and Robolectric screenshot tests only, not on a device or with TalkBack.

## 2.0.6 — trackers and PDF reflow (stages 2–3)

- New **MangaUpdates** tracker: sign in with your username, search (novels and drama CDs filtered out), bind titles, list statuses (Reading / Plan to Read / Complete / Dropped / On Hold), chapters and rating (rating 0 removes it). MangaUpdates bindings from Mihon backups are imported. Written against the service API and covered by mock-server tests; a real-account check is still pending.
- **Kitsu**: the entry's privacy flag now survives every update, and start/finish dates are sent on changes (empty dates clear them on the service).
- **PDF reflow** rewritten with a geometric page analysis (XY-cut over whitespace bands): two-column and multi-column pages are read in the correct order, page-number headers and footers are dropped, paragraphs are rebuilt from vertical gaps, first-line indents, heading sizes and bullets, and hyphenated words are joined. Pages without a text layer still render as an image. Complex float wrapping and tables inside columns may parse imprecisely.
- Localization: the sync-conflicts screen and the tracker UI strings are available in English and Russian.

## 2.0.5 — sync completion (stage 1)

- A **Sync conflicts** screen in Settings: the problems that stopped the last sync are listed one by one with explanations and actions; nothing is deleted or overwritten without your choice.
- Changed book files are adopted explicitly: "Keep my version" publishes it while keeping progress, annotations and tracker bindings (the re-binding is journaled and resumes after a crash), "Take the other version" replaces the local file; a file changed on both devices stays a conflict. Repeat publications — including automatic ones for PDFs the app itself wrote highlights into.
- Image folders: previously imported folders migrate to stable identities by a user action (page order, positions, annotations and bindings are preserved; other devices follow the marker on their own). Page changes (add, replace, remove) no longer stop the other books: an ambiguous folder lands in conflicts, publishing/accepting a version resets only positions that cannot be remapped, with a report; annotations of changed pages stay in place.
- Book deletions propagate between devices (the "Sync deletions" setting + file sync enabled): an unchanged copy is removed together with its records and the library file, a changed copy is kept with a conflict. Files on the server are not deleted.
- First release from the repository: a permanent signing key (further updates install over each other), "Check for updates" downloads releases from this repository.
- Not verified on real devices, WebDAV servers or shared folders: only simulated two-library exchanges and MockWebServer.

## 2.0.4 — local tracking (partial)

- A "Tracking" button on a local book or manga card: link to AniList, MyAnimeList and Shikimori, edit chapters, status and score manually; local books also appear in the profile. There is no automatic chapter detection for local files: pages and percentages are not converted to chapters.
- If a submission fails or you are signed out, the changes stay queued. Consecutive edits and tracker responses do not overwrite each other; editing takes the current entry. Bindings are included in backups but are not synced by the WebDAV data mechanism.
- The database migration 5 → 6 keeps existing bindings of online titles. Backups with files now also include books previously downloaded over WebDAV.
- Kitsu, MangaUpdates, extended format support and real-device/service checks remain open. This build has no embedded OAuth keys — set them up yourself.

## 2.0.3 — WebDAV book file sync (partial)

- Optional book file sync over WebDAV (off by default): enable it on each device in the sync settings. Progress and notes travel through the existing mechanism.
- Files are verified with SHA-256, damaged copies are not imported, conflicts are not overwritten. The server must support strong ETags and conditional writes. HTTPS recommended; the server can see the file contents.
- Shared folders and image folders are not supported. File deletions are not synced: a locally deleted book may come back and server files remain. Files changed after import, including PDFs with annotations, can raise a conflict and need manual attention. Libraries need extra space and traffic.
- Not verified on a physical device, a real WebDAV server or between two devices. The rest of the request is still open.

## 2.0.2 — partial rework

- APK download resumption when a checksum is available; the package name and certificate are checked before the installer is invoked. Android still verifies the APK on install.
- A damaged or unknown sync file format is no longer overwritten.
- EPUB files with encrypted content are rejected with a clear error; DRM reading is not implemented.
- Book file sync, local tracking, new trackers and full DOCX/PDF/fixed-layout EPUB improvements are not in this release yet.

## 2.0.1

> **Install.** Installs over 2.0.0 (and 1.9.0) if signed with the same key; the database schema is unchanged (5).
> The build is verified by compilation and tests but **2.0.1 has not been run on a real device** — see
> [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).

### Profile: Tracking and Downloads tabs

- **Profile → Tracking** — every title linked to MyAnimeList, AniList or Shikimori: cover, status, chapters read and score per service, a badge on unsent changes; filter by status, fresh changes first, tapping opens the title. The "Accounts" card shows signed-in services, "Manage" opens Settings → Tracking, "Send" pushes the queued changes. "Refresh from services" re-reads every entry at once: one failing service does not stop the others, the result is reported.
- **Profile → Downloads** — the same download queue as the dedicated screen: pause and resume, retry failures, clear, disk space; next to it — a link to the download settings.
- Profile tabs scroll when they do not fit on screen.

### Fixes

- **Shikimori**: the service moved to `shikimori.io` and the old address redirects requests — sign-in, list reading and submission broke. Yomu now talks to `shikimori.io` directly.
- Tracking: simultaneous requests with an expired token no longer refresh it twice (Shikimori refresh tokens are single-use — the second request signed you out); a server error during a token refresh no longer signs you out — changes are simply sent later; changes accumulated while signed out are sent right after sign-in; background submission no longer loops forever for a service you are not signed into; two chapters finished in quick succession can no longer submit stale progress last; the signed-in-without-username message is no longer cut off mid-word.
- Updating tracking entries on a title page no longer stops at the first error, and an unchanged entry no longer jumps to the top of the list.
- WebDAV: the sync file is read without compression — servers that compress responses on the fly (Apache with `mod_deflate`) changed the ETag, so every submission ended in a conflict; weak ETags (`W/…`) are no longer sent in `If-Match`.
- Bulk metadata editing no longer rewrites the books' language when the language field was untouched.
- Minor compiler-warning fixes.

### For development

- New tests: profile tabs and "Refresh from services" (`Release201Test`), one token refresh for several simultaneous requests and a refresh server error (`TrackersTest`), compression and weak ETags in WebDAV (`WebDavSyncBackendTest`).

## 2.0.0

> **Install.** Installs over 1.9.0 if signed with the same key. The database migrates itself (schema 4 → 5: the tracking table); still, make a backup before updating. **Sync must be updated on all devices at once:** the sync file format grew (deletions, online library) — 1.9.0 and older do not know its deletion fields. Verified by compilation and tests but **2.0.0 has not been run on a real device** — see [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).

### Moving from Mihon / Tachiyomi

- **Settings → Backup and sync → Import from Mihon / Tachiyomi** reads a `.tachibk` backup (Mihon and forks) or `.proto.gz` (Tachiyomi): titles with chapters, read marks and bookmarks, history, categories (they become library tabs) and tracker bindings. Yomu's source ids match Mihon's, so titles open as soon as the right extension is installed; titles of missing sources are kept and listed in the import result. Extension repositories from the backup can be added with one button, along with "Check for updates".
- Nothing is deleted: where a value exists both in Yomu and in the backup, Yomu's own stays, read marks merge. Mihon settings and fork-specific fields are skipped.

### Tracking: Shikimori, AniList, MyAnimeList

- **Settings → Tracking** — browser sign-in (OAuth), sign-out, "Send changes", "Update progress automatically". A **Tracking** button on a title page: search the service, bind, status, chapters read, score, open the site, unbind (optionally removing the list entry). Start and finish dates are set automatically.
- A finished chapter goes to the bound trackers by itself: progress only grows, the first chapter moves the title to "Reading" (from "Plan to read", "On hold" or "Dropped"), the last one to "Completed". Changes made offline queue up and are sent when the network returns.
- Tokens are stored only in the app's private settings — never in backups or the sync file.
- The services need app keys (client IDs): the build takes them from the `YOMU_ANILIST_CLIENT_ID`, `YOMU_MAL_CLIENT_ID`, `YOMU_SHIKIMORI_CLIENT_ID`, `YOMU_SHIKIMORI_CLIENT_SECRET` properties; without them, enter your own key in Settings → Tracking → App keys. See [docs/TRACKING.md](docs/TRACKING.md).

### Online novels

- A new `sources:novels` module — built-in novel sources **RanobeLib** (ranobelib.me, Russian) and **NovelBin** (novelbin.com, English): catalog, search, chapters. Chapters open in the book reader as text — with the dictionary, translation, highlights and read-aloud.

### "Continue reading" widget

- A home-screen widget: the books and online titles you are reading — freshest first, with cover and progress; titles show the number of unread chapters. Tapping opens the book at the stop position, a title at the chapter to continue from. The number of rows adapts to the widget height; the widget updates while you read.

### Editor

- **Manga page translation (OCR)** — in the page editor, "Translate page (OCR)": "Recognize text" finds the text on the page (OCR language from settings), or circle a bubble with your finger. Each block is translated (engine and language from settings), the original is painted over with the bubble color, white, black or left as is, and the translation is fitted to the bubble. A block can be edited and re-translated, the eye shows the result, the checkmark bakes the translation into the picture — one undoable step. "Translate pages automatically" does the same for several pages without review.
- **Visual chapter editing** (EPUB) — "Edit text": a chapter with the book's styles edited like in a word processor: bold, italic, underline, strike-through, superscript and subscript, headings, quotes, lists, indents, alignment, links, the book's images, a divider, style reset, search. Paste is text-only to keep foreign styles out. Saved as valid XHTML; source code is still available.
- **Selected-chapter translation** — in the editor ("Selected chapters, draft here", undoable) and on the book card ("Selected chapters"): only the checked chapters are translated, not the whole book.

### Library

- **Metadata for several books at once:** select books — "Edit metadata" appears in the top bar: authors, series with numbering (by name or in selection order, from a number), language, publisher, reading status, tags (add and remove). Fields you did not touch stay per-book. Library data changes, book files do not.

### PDF: highlights in the file

- PDF highlights, underlines and strike-outs are now stored **both in Yomu's database and in the PDF itself** — as standard annotations (Highlight/Underline/StrikeOut with a note) that other PDF apps show too. The file is appended as an incremental update: original bytes are untouched, foreign annotations stay, Yomu's own are replaced on the next change rather than accumulating. Highlights Yomu wrote into the file on another device are imported when the book is opened.
- Enabled by default; toggle in Settings → Manga and PDF → Save highlights in PDF files. Linked files (not copied into Yomu) are changed only if Yomu has write access; encrypted PDFs are not touched.

### Sync 2.0

- **A shared folder or WebDAV** (Nextcloud, ownCloud, Yandex Disk, Koofr, NAS…) with a connection check; the server file is replaced only if nobody changed it since the read (ETag), otherwise the data is merged again.
- **Deletions are synced:** highlights, notes, bookmarks, words, tabs and online titles deleted on one device are deleted on the others (can be turned off). A deletion travels only as an explicit mark: an old-version file, a fresh install or a book missing on a device deletes nothing. Books themselves are not deleted: a book removed on one device, with its notes, stays on the others.
- **Online library:** favorited titles, tabs, read-chapter marks and chapter bookmarks.
- Three-way merge — against the state of the last sync: the changed side wins.
- **When:** on app open, on exit, and in the background on a schedule — each switchable separately.

### Long-press menus

- **Settings → Long-press menus:** a long press on a manga or comics page opens a radial menu or a list (or runs the first action right away). Actions — translate, save the page, share, copy text, bookmark, set as cover, note — are toggled and reordered.
- The text-selection menu — a panel (as before), radial or list; button order and custom buttons live in "Selection actions" (Settings → Text selection).

### For development

- CI also runs `:sources:novels:test`; live novel-source tests run with `-Pyomu.liveTests=true`.
- Tracker keys are passed into release and nightly builds via the `YOMU_ANILIST_CLIENT_ID`, `YOMU_MAL_CLIENT_ID`, `YOMU_SHIKIMORI_CLIENT_ID`, `YOMU_SHIKIMORI_CLIENT_SECRET` secrets (optional).
- New tests: Mihon import, the 4 → 5 migration, tracking, sync merge, bulk metadata editing, PDF annotation writing (classic xref table, xref/object streams, predictors, rotated pages, foreign annotations; the result validated with PyMuPDF and PDFium), text typesetting into bubbles.

## 1.9.0

> **Install.** Installs over 1.8.0 if signed with the same key. The database is unchanged. Verified by compilation and tests but **1.9.0 has not been run on a real device** — see [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).

### Smooth tab switching

- **Tabs are no longer rebuilt.** Each tab used to be a separate navigation destination: tapping the bottom bar destroyed the current screen and built the next one from scratch — list, covers, ViewModel — in one long frame. That frame was the visible stutter, and the bar's animation froze with it. Now an opened tab stays composed while another is on screen: returning to it is just re-attaching, scroll and state in place.
- **Loading starts on touch.** A tab that has never been opened starts composing as soon as the finger touches the bar (not after release) — by the time the touch ends it is usually ready.
- **The switch animation** is a short fade with a slight zoom (0.28 s): the old tab dims, the new one appears over it. The animation runs in the graphics layer and does not recompose screens; with "Reduce motion" tabs switch instantly.
- A hidden tab is not drawn, does not receive taps, is invisible to TalkBack, and its lifecycle stops at STARTED (data keeps updating); "Back" and cover animations are disabled inside it. Each tab keeps its own ViewModels and saved state, as before. "Back" on any tab but the first returns to the first.
- Returning to the tabs (after closing a title or the reader) composes only the visible tab; the rest load on touch again, with the same scroll and data: the return frame is no heavier than in 1.8.0.

### Fixes

- **A crash on the title page.** If a catalog title page had not loaded (no network, source error), the app closed: the error block tried to fill the full height inside a scrolling list (`IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints`). The error block now stretches only where height is bounded — on the title page it sits in the list with a "Retry" button.
- **Settings writes no longer wait for the main thread.** DataStore applies a change on the writer's thread while holding the write lock: a write from a screen waited for a free main-thread frame, and all other writes waited for it. Changes now apply on a background thread.

### Crash report

- **After a crash**, Yomu shows a "Yomu closed unexpectedly" dialog on the next launch with the first line of the error and buttons "Send report" (a `yomu-crash.txt` file via Share), "Copy" and "Close". The dialog shows once per crash and only for crashes of this version; all entries are still in Settings → Advanced → Error log.
- The report now includes the screen the crash happened on and the last steps before it (up to six transitions, without screen contents) — making the crash easier to reproduce.

### Catalog: source language

- A **language switcher** in a source's top bar — for extensions with a source per language (MangaDex, Wikisource, NHentai and others): the button shows the language code (or "ALL" for a multi-language source), the menu lists the extension's languages. Choosing opens the same section in another language: search keeps the query, selected filters carry over. Disabled languages are hidden from the menu.

### For development

- **A shared debug-build key** — `gradle/debug.keystore` in the repository: debug APKs from CI, from computers and sent by chat install over each other instead of requiring a clean install (with the key of pre-1.9.0 builds — one reinstall). The key is public on purpose and signs only `app.yomu.reader.debug`; release is still signed with its own key.
- The **`fast` build** (`./gradlew assembleFast`) — the debug app built like a release (R8, not debuggable): installs over the debug build keeping its data and shows the real speed. Do not judge animation smoothness on a debug build — it is several times slower.
- Tests no longer hang every other run: `Release170LogicTest` waited on a settings write stuck behind a previous test's main-thread write (Robolectric resets the main thread between tests) — CI could hang until timeout.

## 1.8.0

> **Install.** Installs over 1.7.0 if signed with the same key. The database is unchanged. Verified by compilation and tests but **1.8.0 has not been run on a real device** — see [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).

### Profile instead of "History"

- A **Profile tab** replaces "History" — three sections: "Overview", "Statistics", "History".
- "Overview": name and avatar (emoji, changed by tapping the header), today's reading time with the daily goal, total, streak and best streak, counts (books, words, sessions, pages), **achievements** (reading hours, streaks, saved words, finished titles — with a bar to the next tier), the most-read titles and recently finished ones.
- **Share:** a profile card as an image (PNG 1080×1350, in theme colors), a short text, the reading history as CSV (opens in Excel and Google Sheets), a book backup without files — through the system Share menu; from there — a link to "Backups".
- "Statistics" and "History" are the previous screens, now inside the profile; statistics run in the background.

### Bottom bar

- The **navigation bar floats** above the content (like 1.6, but over the lists instead of below): lists scroll under it, the last item is not hidden, buttons and notifications sit above the bar. The background strip under the bar is gone.

### Catalog

- **No more stutter on open.** The filter and sorting of the source list (thousands of sources from big repositories) moved off the main thread; language names are cached (sorting "by language" used to build a `Locale` per comparison); extension icons are drawn and cached in the background; a source is marked "used" at most once a minute — previously every loaded catalog page re-sorted the whole list.
- **Source list filters:** "Filters", "Languages" and a sort control above the list, active filters as chips (tap removes one), "12 of 340" while something is hidden. Filters: content (manga, novels, books), type (extensions, built-ins, OPDS, local folder), 18+ (show / hide / 18+ only), pinned only, hide disabled and unresponsive. Languages are a separate list with search and per-language counts, with "All languages" and "Device language and English" buttons. **Everything is saved** and survives restarts.
- Source search debounces input; the list is not recomputed on every letter.

### Source filters (NHentai.xxx, GalleryAdults and others)

- **Tags load themselves.** Many extensions load tags in the background and before that show "Press 'reset' to attempt to load tags". Yomu now re-asks the extension (within ~30 seconds) and fills the tags in as they arrive, keeping the selection; instead of the hint — a "Loading the source's tags…" row with a "Load tags" button.
- **"Reset" always works** — the button used to be disabled until something was selected, and tags could not be loaded.
- **Filters are built in the background:** some extensions build them slowly or even fetch them over the network — the screen stuttered (or filters silently vanished: network on the main thread is forbidden).
- **A fast panel:** tapping a chip redraws only that chip and its group counter, not the whole panel (choosing "Latest / Popular" in sorting no longer lags); long groups show the first 60 tags and "Show all", and search inside a group searches nested groups too (typo-tolerant tag search).
- **Filters do not "drift":** selected values are saved together with the filter names and re-matched by type and name on search if the extension changed the list in the meantime (e.g. loaded tags). Values used to be carried over by position and could land on the wrong filter. Saved 1.7 filters are read.

### Manga reader

- **Tap zones no longer show on every title open** — off by default (back: reader settings → "Show zones on open").

### Stability

- Catalog, statistics, history and profile screen computations run in the background (`flowOn(Dispatchers.Default)`); saved-filter writes run in the background and serially.

## 1.7.0

> **Install.** Installs over 1.6.0 if signed with the same key (GitHub releases are signed with the key from the repository secrets — see [docs/RELEASING.md](docs/RELEASING.md)). The database is unchanged; the desktop icon stays.

### App icon

- **Seven icons to choose from:** Settings → Appearance → App icon — "Classic" and six new ones: "Spark", "Feed", "Fox", "Hanko", "Gaze" and "Manga". All are adaptive: the launcher crops a circle, square or capsule, parallax works, and on Android 13+ each has a themed (monochrome) variant matching the wallpaper. The picker is a tile grid like the accent colors; the About card shows the chosen icon.
- The icon changes when you exit the app: while Yomu is open, some launchers redraw or even kill it on a switch. Some launchers remove the old icon from the home screen — add Yomu again from the app list then.
- The choice is stored by the system itself (an `activity-alias` per icon), so there is no separate setting and it does not go into backups.
- Icon sources live in `branding/icon-options`: `make_icons.py` draws the SVGs (foreground in a 66 dp safe zone, monochrome layer), `svg2vd.py` converts them to VectorDrawables — baking transforms, gradients, masks and transparency, and splitting long paths (the dots of "Manga") into chunks: aapt2 does not keep attribute strings longer than 32 767 bytes.

### In-app updates

- **About → Check for updates finishes the job.** If a new version is out, a dialog opens with its description from the GitHub release (the "Install" note as a top callout) and the download size. "Download and install" fetches the APK for the device ABI, verifies its SHA-256 against the release's `SHA256SUMS.txt` and opens the system installer. Download progress is in the row itself (a ring with percent; tap cancels). A file that does not match the checksum is deleted and not installed; Android verifies the signature: an update installs only over a version signed with the same key.
- **"Check automatically"** — once a day at launch, one request to GitHub. Off by default: the app only goes online when you act. When a new version is out, a dot appears on the Settings tab (TalkBack: "new content available") and a "Yomu X available" card at the top of Settings leads to the description and the update.
- Installing needs the `REQUEST_INSTALL_PACKAGES` permission; on the first update Android asks whether apps may be installed from Yomu. The debug build (`app.yomu.reader.debug`) warns that a release installs alongside as a separate app.
- APK selection is stricter: the file is matched by the `-<ABI>.apk` suffix, so an x86 device is no longer offered an x86_64 APK; if none matches and several files exist — the release page instead.

### What's new

- After an update a short list of the version's changes shows once: "Got it" and "All changes" (the releases page). A fresh install does not show it — the welcome covers everything.

### Misc

- The main activity is now `YomuActivity`; the old `.MainActivity` name stays with the "Classic" icon, so home-screen shortcuts survive updates.
- The last update check, the found version and the shown "What's new" are per-installation state: they do not go into backups or sync and are not overwritten by a restore.
- The 1.6.0 source archive missed `docs/НЕ_СДЕЛАНО.md` and `docs/history/НЕ_СДЕЛАНО_до_доработки.md` (Cyrillic paths); they are in place now.
- Tests: `Release170LogicTest` (checksums, release parsing and description, "What's new", icon switching, the daily check, downloading and the installer on fakes) and `Release170ScreensTest` (all icons — circle, square, themed, the icon picker, the update dialog, downloading, the card, the tab dot, "What's new").

## 1.6.0

> **Install.** Installs over 1.5.0 if signed with the same key (GitHub releases are signed with the key from the repository secrets — see [docs/RELEASING.md](docs/RELEASING.md)). The database is unchanged.

### Navigation bar

- **A smooth animation instead of a jump.** The selected section's capsule flows to the tapped one: it stretches, neighbours step aside, the label fades in as it opens, the outlined icon fills in. Instead of a short tween — a soft spring without bounce, so quick consecutive taps continue the motion instead of jerking the bar. With labels on all sections a small indicator slides under the icon. All computations run in layout and draw phases, without per-frame recomposition. The bar hides and shows (e.g. when a book opens) with a smooth slide from the bottom. "Reduce motion" disables the animation.
- **Settings → Navigation bar** (a new section): tab order — drag by the handle or long-press; each tab has a switch and can be hidden, e.g. "Dictionary" or "History". The first tab opens at launch. "Settings" cannot be hidden so you can always get back; "Reset tabs to default". "Labels in the navigation bar" moved here too. TalkBack gets "Move up" / "Move down" actions.

### Catalog: the source screen

- **"Popular", "Latest", "Search" tabs.** Search and filters moved from the top bar into the third tab: the search field, a filter button with the active count, recent queries and all of the source's filters right on the page, with "Show results" at the bottom. Above the results — active filters as chips (a cross removes one), the filter button re-opens the panel. Tabs swipe with the indicator following the finger; each has its own list and scroll position.
- **Fixed: the "Latest" tab did not work** (e.g. on Weeb Central). The screen had one shared list switched between "Popular" and "Latest": while "Latest" was loading or failing, popular titles stayed on screen and the error was hidden — the tab seemed dead. "Popular", "Latest" and every search now have their own page loader: "Latest" shows its titles, and on failure — the reason, "Retry" and "Open in WebView" (if the site demands a browser check).
- "Nothing found" — only when the source really returned an empty list; while loading — card placeholders. If a pull-to-refresh fails, the titles stay and the reason appears as a snackbar.

### About

- A card with the icon, version and author — **Shinoame**. Links: the author ([github.com/adxptived](https://github.com/adxptived)), the source code ([github.com/adxptived/yomu](https://github.com/adxptived/yomu)), releases and "Report an issue".
- **"Check for updates"** — one GitHub Releases request per tap (no background checks): if a new version is out, tapping opens the APK for the device ABI.

### Build and releases

- **GitHub Actions builds APKs into releases:** the `vX.Y.Z` tag → tests → a GitHub Release with `yomu-vX.Y.Z-arm64-v8a.apk`, `…-x86_64.apk`, `SHA256SUMS.txt`, `mapping.txt` and a description from this file; the tag is checked against `versionName`, suffixed tags (`v1.6.0-beta.1`) are pre-releases. `nightly` builds from `main` after a green CI with permanent APK links. Dependabot for dependencies and Actions, issue templates with release links. Setting up the signing key and releasing — [docs/RELEASING.md](docs/RELEASING.md).
- The Gradle property `-Pyomu.versionSuffix=…` appends a suffix to `versionName` (nightlies, betas).
- Tests: `Release160ScreensTest` (bar animation frames, navigation settings, About, source tabs on a fake model) and `Release160LogicTest` (tab order, version comparison, APK selection from a release).

## 1.5.0

> **Install.** Signed with the same key as 1.3.1 and 1.4.0 — installs over 1.4.0, data is kept. The database migrates itself (schema 4): online-library categories become tabs.

### Library

- **Fixed: "Your library is empty" with favorited titles.** If there are no on-device books but online titles exist, the library shows them (an "From the catalog" shelf, "Continue reading"), and instead of an empty screen — a small "No on-device books yet" card with an import button. On an empty screen the "Import" button is no longer duplicated by a floating one.
- **Tabs instead of the "Books / PDF / Comics…" chip row.** On top — "All", "Online (N)" and **custom tabs**; "+" creates one. A tab can hold both books and online titles. Long-press a tab to rename, move left/right or delete (books and titles stay in the library).
- **Add to a tab:** select books with a long press → "To tabs"; long-press an online title → "Tabs…" (both can create a new tab right away). When tapping the heart, if tabs exist the title can be shelved immediately; on a title page — "⋮ → Tabs…". In "Catalog → Library" — the same tabs.
- **A real filter.** The "Filter" button (with the active count) opens a panel: type (books, PDF, comics, online), reading status, tags, languages, sorting, view (grid/list) and column count. Active filters show as chips under the toolbar, each with a cross, next to "Reset all". Sorting and the grid/list switch are separate buttons.
- **"Continue reading"** — a large card of the last book or title and a row of the next ones; online titles with unread chapters are here too.
- A "Library" header with counters, round search and menu buttons.

### Appearance

- **A new dark-toned design:** graphite background, lavender accent, rounded cards and pill buttons, a floating navigation bar (the selected section — a capsule with an icon and label).
- **Dark theme by default** (if you picked a theme manually before, the choice is kept).
- **Settings → Appearance:** a preview, theme (system/light/dark), "Pure black" (AMOLED), "Dynamic colors" (from wallpaper, Material You), an **accent color** — lavender, purple, indigo, blue, teal, green, orange, red, pink, "Classic" (the 1.0–1.4 palette) and a **custom color** (hue and saturation sliders); labels in the navigation bar. The whole UI repaints under the accent, text contrast is at least WCAG AA.
- The settings root screen — grouped cards with icons.
- Placeholder covers (for books and titles without a cover) and icons of sources without their own — in a range from teal through blue and purple to orange, without green.

### For developers

- DB schema 4: a `catalog_collections` table (title ↔ tab); book collections and catalog categories merged (`LibraryTabs`). The 3 → 4 migration and restoring old backups move categories into tabs (same-named tabs merge). Tests: `DatabaseMigrationTest.migrates3To4…`, `BackupCompatibilityTest.restoresCategories…`, `PaletteTest` (contrast of all accents), `Release150ScreensTest` (screenshots).

## 1.4.0

> **Install.** Signed with the same key as 1.3.1 — installs over 1.3.1, data is kept. The database did not change (schema 3).

### Chapters

- **A number on every chapter row.** On the left — the chapter's reading-order number: 1 for the first, the total count for the last. With "newest first" sorting the top row shows the largest number (e.g. 368) and the bottom — 1. Copies of one chapter from different scanlation groups share a number. Above the list — "Read X of N" with a progress bar and "Downloaded N".
- In the reader, under the title — "Chapter 12 of 368".
- Chapter rows carry icons for the release date, the scanlation group and the page you stopped on; the download state (queued, downloading, downloaded) animates.
- The title header has icons for author, status (ongoing, completed, paused, cancelled), type (manga, novel, book) and source; title menu items and the chapter-selection menu have icons too.

### Library

- **Favorited titles are now visible in "Library".** They used to live only in "Catalog → Library". The library has an **"Online (N)"** tab — the same grid, categories, sorting and filters as the catalog; library search covers them. In the "All" tab, above the books — an **"From the catalog"** shelf with an "All" button.
- A message after tapping the heart tells where to find the title: "Added to the library (Library → Online)".

### Network and traffic

A new **Settings → Network and traffic** section:

- **Wi-Fi only** — downloads, the chapter-update check and translation language packs (the same switches as in their own sections, gathered in one place).
- **Mobile data saver** — on mobile data the reader preloads only the next page, and new chapters and "download ahead" chapters are not downloaded automatically (Wi-Fi behaves as usual).
- **Load covers on mobile data** — if off, only covers already in the cache show on mobile data.
- **Ask before reading online on mobile data** — when opening an undownloaded chapter from a title page: "Read", "Download" or "Cancel".
- **Traffic used** — how much the app downloaded and sent: total, mobile and Wi-Fi, since which date, which network is active; "Reset counter". Counts the app's own traffic (catalog, covers, pages, downloads, extensions); pages of the built-in browser are not counted.

### Speed

- Chapter pages download in parallel — "Pages downloaded at once" (1–6, default 3) in "Downloads and storage". Pages used to download one by one.
- The manga reader preloads the next pages in parallel (up to three at once) and does not spend time on pages you already scrolled past; the same page can no longer be downloaded twice.
- More simultaneous connections per site (10 instead of 5): grid covers load faster.
- MangaDex: the "5 requests per second" limit now applies only to the API; images (covers and `uploads.mangadex.org` pages) no longer wait in the same queue.

### Source search

- **New filters.** Filter groups are cards with an icon, a count of selections and a "Clear the group" button; short groups are open, a collapsed one shows what is selected. Options are chips: one tap includes (✓), a second excludes (⛔), a third clears. Long groups (genres, themes) have a "Find in …" field. Sorting — chips with a direction arrow (tap again flips the order), short lists — chips, long — menus.
- **Active filters above the results** — as chips: tapping removes a filter and immediately re-searches, "Reset all" returns the source's default filters. The filter button shows the number of enabled filters.
- **Search history** — the last 10 queries as chips under the search field, "Clear". In incognito, queries are not remembered.

### Icons and animations

- Icons on every settings item and section.
- Smooth appear, remove and reorder animations in the library, catalog, chapter list, downloads, history and source results; a heart "pulse"; filter group expansion. A "Fewer animations" switch (Settings → Appearance) turns these off too.

## 1.3.1

> **Install.** APK 1.3.1 is signed with a new permanent key (the key that signed 1.2.0 was not kept), so it does not install over 1.2.0. Make a backup (Settings → Backup and sync → Create a backup), uninstall the old version, install 1.3.1 and restore the backup. Later versions with this key install over each other. The database is schema 3, as in 1.3.0: it migrates from 1.2.0 by itself.

### Fixed

- **Errors that closed the whole app now close only the action.** All screen actions (buttons, menus, import, saving) are guarded: an unhandled error goes to the error log and the screen shows "Something went wrong. Details — in the error log". PDF gestures, EPUB editor file reading, hints and onboarding are guarded the same way.
- **Incompatible extensions no longer crash the catalog.** An extension built against another API version (`NoSuchMethodError`, `NoClassDefFoundError`, `AbstractMethodError`), a function it "did not implement", or infinite recursion inside it is now an "Outdated source" error suggesting an extension update. A source's feed, global search with its "Retry", and the extensions screen survive any extension crash; a crash inside a network interceptor (including a foreign one) becomes a request error, not a background-thread death.
- **No WebView — no crash.** If Android System WebView is missing, disabled or updating, the EPUB reader, catalog site checks, the DOCX page layout and the source editor show "WebView unavailable" with "Retry" and "Open in store". Cookies without WebView no longer crash requests either.
- **Out of space.** A download that hit a full disk pauses itself and the whole queue with the reason "Not enough space" (chapters used to fail one by one), with a "Downloads paused: out of space" notification and a "Continue" button. Low space is recognized in the database too.
- A corrupted settings file no longer blocks launch: settings reset to defaults.
- The catalog cannot get "stuck" loading: OPDS catalogs, the local folder and extensions come up separately, one failing does not block the others.
- The manga reader in webtoon mode: a page that failed shows an error and "Retry" instead of an endless spinner.
- PDF: exiting the reader during a page render can no longer crash the app (the document closes after the current page); a page whose size cannot be read does not break the screen.
- The EPUB editor: a book with one chapter repeated in the reading order no longer crashes the chapter list; an unreadable file shows an error message.
- "Share" and "Open link" without a suitable app — a message instead of a crash.
- The Downloads screen: the space card is no longer half-hidden under the header; a chapter group's count is visible with long titles; downloads paused for space show the reason, and a manual pause clears old error messages.
- Opening the app from recents no longer repeats a previously shared-file import.
- The error log does not grow from a repeating error: the last 50 reports are kept.

### Improved

- **Download notifications**: tapping opens the Downloads screen; "Pause" (while downloading), "Retry" (after errors) and "Continue" (after a space pause) work without opening the app.
- A title whose source is not installed (extension removed): the header shows "Source not installed" instead of "—" and a card with a "Migrate" button.

### Tests

- New: `TachiyomiSourceErrorsTest` (extension crashes), `NotificationActionsTest` (notification buttons and the Downloads transition), `CatalogScreensTest` (Robolectric screenshots of the title page, sorting, download menu, chapter selection, the queue and library filters), `ChapterDownloadQueueTest.fullStoragePausesTheQueue`; `AppErrorTest` and `SourceCallsTest` extended.

## 1.3.0

> **Updating from 1.2.0** installs over (if the APK is signed with the same key). The database migrates itself to schema 3 (new fields for chapter sorting and the download queue): titles, chapters read, bookmarks and the download queue are kept — the migration is pinned by a test. 1.2.0 backups restore as before.

### Fixed

- **Chapter order on the title screen and in the reader disagreed**, and "Next chapter" could go backwards or to the same-numbered chapter of another group. The catalog now has one reading order — for the chapter list, the reader, "Continue", "Download next" and auto-downloads: the source list direction is derived from numbers (or dates), chapters without a number ("Prologue", "Extra") sit between their neighbours instead of at the end, per-volume renumbering does not mix volumes, and versions of one chapter from different groups sit next to each other — "Next"/"Previous" skip them and stay with your group.
- **A source failure no longer wipes the chapter list.** An empty website response for a title with chapters counts as an error, not "all chapters removed": read marks, bookmarks and history stay. If the site changed chapter addresses, marks move to the same chapter (same number and group) and such chapters are not counted as new. Duplicates in a source list are skipped, and a changed chapter number is picked up.
- **A web page is no longer saved instead of an image.** Manga pages (on download and in the online-reading cache) are validated by their first bytes (JPEG, PNG, GIF, WebP, AVIF, HEIC, JPEG XL, BMP): a check or error page and an empty response raise "The server sent a web page instead of an image" with a retry, not a "broken" chapter. For books — "…instead of a file (you may need to sign in)".
- A 429 ("too many requests") from extensions is retried with a pause instead of opening WebView; WebView stays for 403/503 checks.
- A source feed no longer repeats titles: already-shown ones are removed, and a page of only repeats stops pagination (some sites served the same page forever).
- Chapters added to the queue while it was finishing no longer wait for the next download run.

### Sorting and filters

- **A title's chapters** — a "Sort and filter" button above the list. Sorting: "By chapter number", "As in source", "By release date" — ascending or descending (an arrow). Filters: "Unread only", "Downloaded only", "Bookmarked only", "One chapter version" (hides copies of the same chapter from other groups), scanlation group selection (chapters of hidden groups are not shown and not auto-downloaded), "Reset filters". Stored per title; "For all titles" makes them the default everywhere. The list header shows "Chapters: N of M", and the filter icon is marked when something is hidden.
- **Chapter selection**: "Select unread", "Select range" (between marked), "Invert selection".
- **The catalog library**: sorting by "Updated", "Title", "Unread", "Last read", "Date added", "Chapter count" — both directions; filters "With unread chapters", "With downloaded chapters", "Completed". Names compare "humanely" (volume 2 before volume 10, case-insensitive). If nothing matches the filters — a message and "Reset filters".
- **Sources**: sorting "Recent", "Title", "Language" (device language first), pinned always first; source search once there are more than six.
- **Search**: a source's results — best matches first (same title, starts with the query, contains it, all words in any order), within a group — the source's order; duplicates removed. Book download links — without duplicates, convenient formats first (EPUB, FB2, MOBI/AZW3, PDF…).

### Source stability

- **Timeouts and retries.** Every source request has a deadline (catalog and details — 45 s, one source in global search — 30 s, a title on update check — 60 s); transient errors (timeout, connection drop, 429, 5xx) are retried with a growing pause — only when there is network. A WebView check, 4xx errors and incomprehensible data are not retried: they will not pass by themselves.
- **Source state**: a source that recently did not respond or responds slowly shows a "not responding" / "responding slowly" hint in the source list and in global search.
- **Global search**: pinned and recent sources go first, rows do not jump while answers arrive; a progress row "Sources answered: N of M · titles found: K"; a "With results" chip hides empty and failed sources (the choice is remembered); a failed source gets its own "Retry"; if nothing is found yet — it says so.
- The update check polls up to 4 sources in parallel, and a source's titles one by one with a pause, so the site does not block dozens of simultaneous requests. A slow or broken source does not interrupt the check; the notification shows how many titles could not be checked.
- Page images download with retries; a signed image URL is re-requested on retry (such links expire quickly).

### Downloads

- **A download menu on a title card**: the next 1, 5, 10, 25 chapters or "Specify a number…" (up to 1000), "Unread chapters", "All chapters" — one version of each chapter (the group you read; hidden groups are skipped); "Delete read chapters", "Delete all downloads" (with confirmation), a link to downloads. After tapping — how many chapters were queued, or "Nothing to download".
- **The catalog library**: long-press a title → "Download unread chapters".
- **Automatically** (Settings → Downloads and storage): "Download new chapters" — new chapters of library titles found by the update check are queued right away; "Download while reading" — while reading a library title the next 2, 3, 5 or 10 chapters are pre-downloaded; "Auto-retries on errors" (0–5, default 3) — transient errors retry with a growing pause, and without network a task waits for the network and is not counted as failed.
- **The Downloads screen**: the queue is grouped by titles — a group has "To the top of the queue" and a menu (pause, resume, cancel all of the title's chapters), a task has "To the top of the queue" and "Cancel". Failed ones are a separate block with "Retry all" and a clear reason ("No network connection", "The server did not respond in time", "Server error 503", "The site requires a check…", "Not enough space"…); during auto-retries — "Retry 1 of 3: reason". The menu has "Retry failed downloads", "Clear completed", "Clear the queue" (with confirmation; downloads stay). Completed — newest first.
- **The queue**: at most 2 simultaneous downloads per site (free slots go to other sources), "To the top of the queue" really reorders; without network downloads pause and resume by themselves when it returns; pages of a chapter already downloaded are not re-downloaded on retry; tapping "Download" again does not queue a duplicate, and chapters whose files were deleted manually are downloaded again.
- **Notifications**: progress with the number of remaining tasks and the chapter name; at the end — "Downloads finished" (how many succeeded and failed) or "Downloads will continue when the network is back". The new-chapters notification reports how many were queued.
- CBZ is first written to a temp file and renamed after writing: an interrupted packing leaves no "broken" archive.

### Tests

- New unit tests: chapter reading order (both list directions, chapters without a number, per-volume numbering, group versions, "Continue", "Next N"), chapter and library sorting and filters, "human" title comparison; retries, timeouts and source state; search ranking and book links; image detection by bytes; Downloads screen grouping; the 2 → 3 database migration keeping data.
- A Robolectric `ChapterDownloadQueueTest` on a fake site (MockWebServer): one chapter version, "To the top of the queue", a retry after 503, a web page instead of an image → a clear error, CBZ without temp files, "Retry failed", "Delete read chapters"; chapter sync on an empty response and changed addresses, "Download new chapters", "Download while reading".

## 1.2.0

> **Updating from 1.1.0.** This build is signed with a new key (the key that signed 1.1.0 is not stored in the sources), so it does not install over 1.1.0. Make a backup: Settings → Backup and sync → Create a backup (enable "Book files" if books were copied into the app), uninstall 1.1.0, install 1.2.0 and restore the backup there. If you kept a 1.1.0 keystore, build 1.2.0 with it (the `YOMU_KEYSTORE_*` variables, see the README) — then the update installs over without uninstalling.

### Fixed

- **Extensions did not start: "NoSuchFieldException: No field headers$delegate…"** (AComics, Weeb Central and other Mihon/Tachiyomi extensions). The cause: R8 in the release build renamed fields of the `eu.kanade.tachiyomi.*` compatibility-layer classes (e.g. `HttpSource.headers$delegate` → `d`), and extensions access them by name. The compatibility API is now kept whole (`compat/tachiyomi/consumer-rules.pro`); verified against the release build's `mapping.txt`.
- **Built-in MangaDex: "Unexpected JSON token… relationships[5].attributes.description".** The API serves localized fields in different shapes (an object, an empty array `[]`, a string) — the model only expected an object. `description` and `altTitles` now parse in any shape, the title and description are picked by the source's language with an English fallback. Pinned by a test.
- **Source errors became understandable.** Instead of raw exception text — an icon, a heading and an explanation ("The site is not responding", "The site denied access (403)", "Too many requests to the site", "The site has problems (5xx)", "Could not read the response" — when the site changed and the source cannot parse it…), "Retry"/"Open in WebView" buttons and expandable "Details" with the technical cause and a "Copy" button. HTTP codes inside extension messages ("HTTP error 403") are recognized too.
- **"Add a repository": the field is no longer pre-filled with `https://`** — pasting a link produced `https://https://…`. A double scheme is fixed automatically; an address without `https://`, a GitHub page and an "Add to Mihon" link (`tachiyomi://add-repo?url=…`, `mihon://…`) are accepted. "Paste" starts validation right away, so does Enter; network errors are named as they are, not "this is not a repository".
- "Add an OPDS catalog": also without `https://` in the field, an address can be entered without a scheme (for home servers — an IP, `localhost`, `*.local` — `http://` is assumed), the final address shows under the field, the password has a "show" toggle.
- Volume keys in manga respect the manga settings (they used to take the text reader's).

### Manga

- **Left-to-right by default.** If 1.1.0 had "right-to-left" saved (the old default), 1.2.0 changes it once to "left-to-right" on first launch; further your choice is untouched. Old backups are updated the same way on restore.
- **New manga settings** (in the reader and in Settings → Manga and PDF): a tiled reading-mode picker with pictograms and an explanation (left-to-right, right-to-left, vertical, webtoon), scale, page background (black, gray, white), page-turn animation, page number, preloading, margin crop, dual pages; for webtoon — side margins and gaps between pages; image filters (brightness, contrast, grayscale, invert) with a reset.
- **Tap zones**: layouts with diagrams, "Swap the zones", "Show zones on open", a "Show zones now" button and a first-open hint overlay (and after changing zones or direction). Volume keys can be swapped ("Volume up — forward").
- In "right-to-left" the page slider in the bottom bar also runs right-to-left.
- A page that failed to load shows "Could not load the page" and "Retry" instead of a blank screen.

### Catalog and extensions

- **Source logos** instead of identical letter circles: extension icons, the built-in sources' own logos (MangaDex, Project Gutenberg, Internet Archive, Open Library, Wikisource), folder and OPDS icons, and a colored monogram for the rest (each source has its own color). Logos are in global search too.
- Subtitles without "🌐 · Manga · Extension": language as a word ("Multi-language"), content type, OPDS/Local, 18+. Pinned sources are a separate block.
- **Untrusted extensions** — instead of red cards a calm block with an explanation and rows with an icon, version and signature prefix; "Trust" and "Remove" buttons read well in any theme; "Trust all" shows a list and asks for confirmation; the trust dialog shows the extension icon.
- Broken extensions: in the list — briefly "Does not start — tap to learn more", in the card — an explanation, the error text and "Copy" (to send to the authors).
- The extension update counter is in the primary color, not the error color.

### Appearance

- **Yomu's own palette**: ink-blue on a warm "paper" base in the light theme, ink-dark in the dark one, plus AMOLED; text contrast checked (at least 4.5:1). Enabled by default; Material You dynamic colors remain a switch in Settings → Appearance (if you enabled them yourself, the choice is kept).
- Headings are serif, like a book; picker tiles, switches and error screens share one style.
- Tabs: unselected in a neutral color, selected in the accent (previously all one color). Counter badges are not red.
- Floating buttons ("Import", "Read", "Add repository"…) are now announced by TalkBack — they used to have no label for screen readers.
- In manga the gesture hint is its own (without "hold a word") and at the bottom of the screen, so it does not cover tap zones.

### Tests

- New: parsing MangaDex responses of different shapes, manga-settings migration, repository and OPDS address normalization, error classification; Robolectric screenshots of the catalog, extensions (untrusted, broken, "Trust all"), repository and OPDS dialogs, the manga reader and its settings.

## 1.1.0

> **Important when updating from 1.0.0.** Version 1.0.0 was signed with a temporary key that was not kept, so 1.1.0 does not install over it. Before removing the old version make a backup: Settings → Backup and sync → Create a backup (enable "Book files" if books were copied into the app), uninstall 1.0.0, install 1.1.0 and restore the backup there. Starting with 1.1.0 release builds are signed with a permanent key — later versions install over.

### Fixed

- **A book downloaded from the catalog did not open: "No access to the file. Grant the permission again".** The cause: when the same book was downloaded again (a second "Download" tap, another format, a re-download) the new file was written under the same name over the file of the already-added book and then deleted as a duplicate — the book was left without a file. The "file not found" error was also shown as "no access". Now a second tap does not queue a second download, every file gets its own name, a duplicate does not touch the library book's file (and if the book's file is lost — the book takes the new one keeping progress and notes), "file not found" and "no access" are distinguished, and the reader has a "Specify the file" button. Pinned by the `DownloadedBookTest` test.
- **MangaDex: "Unexpected JSON token… <!doctype html>".** The MangaDex API answers a protection HTML page to a "browser" User-Agent. API and image requests now use the app's User-Agent (`Yomu/1.1.0`), and if an HTML page arrives instead of JSON, a WebView check opens instead of a cryptic error.
- A 1.0.0 backup restores into 1.1.0 (new fields get defaults) — pinned by a test.

### New

- **Mihon/Tachiyomi extensions.** A compatibility layer with the Mihon/Tachiyomi extension API (lib 1.4 and 1.6, as in Mihon): repositories are added by an `index.min.json`/`repo.json` address, a GitHub page link or the "Add to Mihon/Tachiyomi" button on the repository site (`tachiyomi://add-repo`, `mihon://extension-store`). Install, update ("Update all"), remove, install an APK from a file, source settings, filters, language selection, repository signature-key checks (a key change blocks updates), 18+ hidden by default. Yomu-API extensions work too.
- **Built-in extensions** (official platforms and open catalogs, Keiyoushi builds, Apache-2.0): MangaDex, MANGA Plus by SHUEISHA, Webtoons.com, GlobalComix, AComics (ru). Each was checked on the live sites: popular → card and chapters → pages → image download → search. Comikey failed the check (page timeouts) and is not included.
- **Page layout for Word documents (DOCX).** A second viewing mode: A4 pages (or the document's size) as printed — margins, headers and footers, footnotes, page numbers, tables, images, tabs, page breaks, paragraph spacing per Word rules. Pinch zoom, "Go to page", dark theme, the position is remembered, reading time goes into statistics. Opens from the reader menu ("⋮ → Page layout") and the book card; on first DOCX open the reader offers this mode itself. The regular mode (text reflow) stays the main one.
- **Position in a book.** "Page N of ≈M" (for reflowable books honestly marked as approximate), "Go to page/percent", a large preview card while dragging the slider (chapter and page), "Back to page N" after an accidental jump by slider, table of contents or search, time to the end of the chapter and book, the page in the chapter. A status line at the bottom of the page when the menu is hidden: chapter, page, progress, clock and battery.
- **Reader settings.** Font size and theme are always on top, the rest on "Text", "Screen", "Controls" tabs; appearance presets (Classic, Large font, Compact, Spacious, Easy reading) and custom presets; letter and word spacing; a custom theme (background and text color) with a low-contrast warning; a "for this book only" switch; a style reset.
- **History** — a new tab: books and online chapters read by day with reading time, search, "Continue", deleting an entry and clearing, the daily-goal progress and streak.
- **Reading statistics**: week, month, year, all time; comparison with the previous period; a per-day chart with a goal line; average per day, reading days, sessions, average and longest session, pages, speed (chars/min or pages/h), best day, streaks, finished books; a top of works; a heatmap; breakdown by hours and weekdays; a daily goal; a statistics reset.
- **Interface**: a "History" tab in the bottom bar; an extensions screen with "Installed", "Available", "Repositories" tabs, search and badges (built-in, 18+); clear messages and actions on file errors (re-specify the file); small placeholder covers show initials instead of cut-off words; the catalog's "History" tab renamed to "Recent" (the latest online chapters) to avoid confusion with the general history; download and error messages in the reader use the reading theme's colors (no black flash before a light page, readable text).
- **Default catalog languages** — the device language and English (plus sources without a language: OPDS, local, multi-language); other languages are enabled with chips at the top of the source list or "All languages". Global search respects this choice.

### Technical

- Database: version 2 (reading sessions for history), the 1 → 2 migration keeps data — pinned by the `DatabaseMigrationTest` test.
- Release is signed with a permanent key (the `YOMU_KEYSTORE_*` variables, see the README).
- New tests: book download and re-download (`DownloadedBookTest`), statistics (`StatsCalculatorTest`), migration and restoring old backups, DOCX layout (`DocxLayoutTest`), MangaDex (HTML instead of JSON), live extension checks (`TachiyomiExtensionsLiveTest`, run manually).

## 1.0.0

The first version: EPUB/PDF/FB2/MOBI/DOCX/TXT/HTML/CBZ/CBR/CB7 reader, dictionaries and translation, words and repetition, notes, EPUB and manga editor, catalog (OPDS, Gutendex, Open Library, MangaDex, local folder), downloads, backups and sync.

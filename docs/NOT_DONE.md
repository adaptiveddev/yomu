# Yomu — what is not done yet

Current state as of version 2.0.6 (what changed — [CHANGELOG.md](../CHANGELOG.md)). The full plan cross-check — [PROGRESS.md](PROGRESS.md), simplifications — [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

## What exists now

- The whole app: `YomuApp`, `YomuActivity` (before 1.7.0 — `MainActivity`; that name stayed with the "Classic" icon), Koin, navigation, onboarding, manifest (FileProvider, VIEW/SEND/PROCESS_TEXT), languages, app lock and `FLAG_SECURE`.
- Every screen from the plan: the library and book card, the reader (EPUB/text, PDF, manga) with all sheets and dialogs, annotations, dictionary and repetition, translation and language packs, the **editor** for EPUB and manga, the **catalog** (sources, titles, global search, history, updates, extensions, migration, WebView), **downloads**, **settings** (every section, backup, sync, the error log, storage).
- 1.1.0: fixed "No access to the file" after downloads and a MangaDex error; Mihon/Tachiyomi extensions (repositories, 5 built-ins), the DOCX page layout, reading history and statistics, new reader settings and position navigation.
- 1.2.0: fixed extension launches after R8 and MangaDex response parsing, clear source errors, new manga settings, the "Yomu" palette.
- 1.3.0: chapter sorting and filters (per title and global), catalog-library and source libraries; one chapter reading order for the list, reader, "Continue" and downloads; search ranking; timeouts, retries and source state, per-source retry in global search, image checks; convenient downloads — "next N"/custom count/unread/all, **auto-download of new chapters**, "download while reading", auto-retries, "to the top of the queue", per-title groups, notifications; database schema 3.
- 1.4.0: chapter numbers in reading order and "Read X of N", favorited titles in "Library" (the Online tab and shelf), Settings → Network and traffic (Wi-Fi only, data saver, covers and the ask-before-reading prompt, the traffic counter), parallel page downloads and preloading, new source filters with active filters and search history, icons and animations.
- 1.5.0: the dark design with the lavender accent, custom library tabs (books and online titles together), a filter panel with chips, "Continue reading", 11 accent colors and a custom one; database schema 4.
- 1.6.0: the smooth navigation bar and its settings (tab order and hiding), Popular/Latest/Search tabs on a source (the Latest tab fixed), About with update checks, releases and nightly builds via GitHub Actions.
- 1.7.0: seven app icons to choose from (`activity-alias`-based, themed on Android 13+, sources in `branding/icon-options`); in-app updates — the release description, APK download for the device, SHA-256 verification against `SHA256SUMS.txt`, the system installer, an optional daily check with a dot on Settings and a card on top; "What's new" shown once after an update.
- 1.8.0: the Profile tab (overview, statistics, history, achievements, card), a floating bar without a background, source-list filters, background filter tags.
- 1.9.0: smooth tab switching (tabs stay composed), a fixed crash on the title page, a crash report on the next launch, a source language switcher, a shared debug-build key and the `fast` build.
- 2.0.0: import from Mihon/Tachiyomi, Shikimori/AniList/MyAnimeList trackers, online novels (RanobeLib, NovelBin), the "Continue reading" widget, manga page translation via OCR in the editor, visual chapter editing, bulk metadata editing, selected-chapter translation, PDF highlights in the file itself, Sync 2.0 (WebDAV, deletions, online library), customizable long-press menus; database schema 5.
- 2.0.1: Tracking and Downloads tabs in the profile; fixed Shikimori (the new `shikimori.io` address), tracker token refreshes and sign-out on server errors, sending changes after sign-in, WebDAV with compressed responses and weak ETags, the language in bulk metadata edits.
- 1.3.1: stability — an action error no longer closes the app (a message and a log entry), incompatible extensions show "Outdated source" instead of crashing, a "WebView unavailable" screen, the download queue pauses out of space, Pause/Retry/Continue buttons in the download notification, a "Source not installed" card with migration, PDF protection, webtoon feeds, the EPUB editor and settings.
- Build: `assembleDebug` and `assembleRelease` (R8 + shrinkResources) pass; arm64-v8a and x86_64 APKs; release is signed with a permanent key.
- Tests: unit tests of the logic (editor, parsers/converters, dictionary imports, OPDS and MangaDex on MockWebServer, online translation, SM-2, lemmatization, export, PIN, chapter order and filters, source retries, search ranking), the "download from catalog → download again → open" regression test (MockWebServer + Robolectric), the chapter download queue and sync test on a fake site (`ChapterDownloadQueueTest`) and a whole-app Robolectric smoke test with screenshots (including history and statistics).
- Documentation: README, LICENSE, CONTRIBUTING, DECISIONS, KNOWN_LIMITATIONS, PROGRESS, ARCHITECTURE, EXTENSIONS, USER_GUIDE, PRIVACY; fastlane metadata; GitHub CI and tag releases.

## What remains

2.0.3 added optional book file sync over WebDAV; 2.0.4 — binding local books and manga to existing trackers with manual chapter entry. 2.0.5–2.0.6 complete sync stage 1 (file adoption, folder migration, page changes, book deletions, the conflicts screen) and add the MangaUpdates tracker plus geometric PDF reflow. Real-device, server and service verification and the other items below remain open.

### Needs manual verification

1. **Running 1.4.0–2.0.6 on real devices** (Android 8–15, phone and tablet): 1.0.0, 1.1.0 and 1.3.1 were run on a phone; 1.2.0, 1.3.0, 1.4.0 and later were verified only by builds, unit tests and Robolectric. First of all check: background chapter downloads (WorkManager, notifications, pause without network and resume), "Download new chapters" after the update check, "Download while reading", downloading a book from the catalog and opening it, built-in extensions (loading via `DexClassLoader`), the DOCX page layout in Android WebView; for 1.3.1 — notification buttons, the out-of-space pause, the no-WebView screen (disable Android System WebView) and a title whose extension was removed; for 1.4.0 — the data saver and the reading prompt on mobile data, the traffic counter, parallel page downloads and MangaDex filters; for 1.7.0 — icon switching across launchers (Pixel, One UI, MIUI/HyperOS, Nova: does the icon stay on the home screen, does the app get killed), themed icons on Android 13+, in-app updates on a real release (the "install unknown apps" permission, installing over with the same key) and "What's new" after updating from 1.6.0; for 2.0.0–2.0.1 — Shikimori (`shikimori.io`), AniList and MyAnimeList sign-ins (app keys needed, see [TRACKING.md](TRACKING.md)) and the `yomu://…` return, the widget across launchers, writing highlights into linked PDFs (SAF write permissions differ by provider) and how Acrobat/Xodo show them, WebDAV on Nextcloud and Yandex Disk, deletion sync between two phones, the visual chapter editor in Android WebView, manga page OCR translation, importing real Mihon backups.
2. **2.0.5–2.0.6 sync on two devices with real WebDAV and a shared folder**: changed file adoption, legacy folder migration, folder page changes, book deletions (changed copies kept), restoring a backup.
3. **Trackers with real accounts**: MangaUpdates (sign-in, search, binding, chapter/rating sync) and Kitsu (including the privacy flag and dates) — implemented per the services' APIs, verified only on mock servers.
4. Real files: large PDFs (500+ pages), multi-GB CBZ, 20 MB EPUBs, "broken" files, DRM; different OPDS servers. PDF reflow on real two-column layouts.
5. Plan targets: cold start < 2 s, a 1000+ book library, memory on large files.
6. Accessibility: TalkBack, large system fonts, contrast. The code pass is done (content descriptions, merged rows, 48 dp targets, text-height fixes); a real TalkBack and fontScale 2.0 run on a device is still pending.
7. `lint` warnings (0 errors, 64 warnings — style and false positives) and `detekt` (informational).

### Not implemented from the plan

1. Manga editor: a **cleaning brush** (manual painting over irregular bubbles and text over art; since 2.0.0 there is block-area painting and OCR typesetting), merging chapters into a volume and splitting volumes, "image folder" export.
2. A Japanese tokenizer (Kuromoji) — currently rule-based deinflection + JMdict.
3. The DOCX page layout: columns, exact float wrapping, charts/EMF, equations; selection and the dictionary in that mode.
4. Trackers besides Shikimori, AniList, MyAnimeList, Kitsu and MangaUpdates (Komga and others); automatic matching of local file progress to service chapters. Kitsu and MangaUpdates are implemented per their APIs but not verified with real accounts — the stage-2 check is yours.
5. Sync: stage-1 code is complete (changed-file adoption, legacy folder migration, folder page changes, book deletions with changed-copy protection, the conflicts screen), but it is not verified on real devices, real WebDAV and a cloud shared folder — only simulated two-library exchanges and MockWebServer.
6. Tests: resuming an interrupted book download (HTTP Range), the UI scenarios "highlight and save a word", "add a bookmark", "download a chapter", opening PDF/CBZ; 60% coverage.
7. A separate build without Google ML Kit (for F-Droid) — needs a product flavor. It should also disable the built-in updater and remove `REQUEST_INSTALL_PACKAGES`: F-Droid updates apps itself and rejects self-updating.
8. In-app updates: a notification for a background-downloaded APK and signing-key change support. Download resumption works with a checksum; the package and current certificate are checked before the installer, and Android verifies the APK on install too.
9. Git history by stages (`stage N`) was lost with the original working folder.

## How to verify a build

```bash
./gradlew assembleDebug assembleRelease
./gradlew testDebugUnitTest :domain:test :sources:opds:test :sources:mangadex:test :sources:novels:test
# live novel-source checks (RanobeLib, NovelBin):
./gradlew :sources:novels:test -Pyomu.liveTests=true
# live extension checks (network and JVM extension builds needed):
./gradlew :data:testDebugUnitTest --tests '*TachiyomiExtensionsLiveTest*' -Pyomu.liveTests=true -Pyomu.extJars=a.jar:b.jar -Pyomu.nativeLibDir=/path/to/zstd-native
```

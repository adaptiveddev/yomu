# Yomu — what was not done per the plan (an archive)

> Historical archive of the pre-rework state, kept for reference. The current status lives in [NOT_DONE.md](../NOT_DONE.md) and [DEVELOPMENT_WIP.md](../../DEVELOPMENT_WIP.md).

## The state of this handover

Restored sources of an unfinished Android project and the original `plan.md` are handed over. The code was recovered from saved shell history after the working folder was lost. This is not a finished product and not a guaranteed-buildable project.

A full build after the recovery was not performed. Old per-module compile artifacts do not prove this archive builds. There is no ready APK, including arm64-v8a.

## What is in the sources

- The Gradle skeleton, the version catalog and convention plugins in `build-logic`.
- `core:common`, `core:ui`, `core:database`, `core:datastore`, `core:network`; the models, interfaces and utilities of `domain`.
- `source-api` and local-source, OPDS and MangaDex implementations; the code of the public book catalogs.
- The `data` layer: import, parsing/conversion, repositories, translation, dictionaries, downloads, background work, backup/sync/OCR stubs.
- The library and book card code; the EPUB, PDF and manga engines/views; annotation, translation and dictionary screens, including word repetition.
- The shared reading screen is partially written: ViewModel, settings, TTS, HTML handling, windowed mode, the table of contents.

Code presence does not mean the feature is fully implemented, wired into the app or verified on a device.

## The main launch and build blockers

1. **The `app` module is not built as an app.** There is a Gradle configuration but no finished `Application`, `MainActivity`, Koin initialization, navigation, onboarding or manifest. FileProvider, language settings, app lock and FLAG_SECURE are unfinished.
2. **`feature:reader` is unfinished and not compiler-verified.** The components and resources listed below are missing.
3. **The editor, catalog, downloads screen and settings are module skeletons only.** No real finished screens exist in these modules.
4. No final release/R8/signing setup, no full dependency review or final APK build.

## The stages 0–9 remainder

### Stage 0 — skeleton and basic infrastructure: partial

- Re-verify all Gradle modules, dependency versions and DI after the recovery.
- Finish the app entry point, manifest, navigation, themes, system integration and initial setup.
- Set up CI, quality checks and required tests.
- There is no restored git history with per-stage commits.

### Stage 1 — library and import: code exists, acceptance not done

- Wire the library and book card into the real app.
- Verify SAF, permissions, folder scanning, duplicates, covers and metadata.
- Run the declared formats and problem files: EPUB/PDF, manga archives, FB2, TXT/HTML/DOCX/MOBI etc.
- Verify collections, sorting, filters, search, bulk actions and state saving.
- Fix errors found by the tests; parser presence does not prove full format support.

### Stage 2 — the EPUB/PDF/manga engines: code exists, integration unfinished

- Verify EPUB pagination, CSS, links, footnotes, images, selection and position restore.
- Verify PDF: zooming, navigation, search/selection, passwords, large documents and OCR.
- Verify manga: reading directions, page/feed modes, zoom, rotation and large archives.
- Measure memory and speed on real devices and files.

### Stage 3 — the shared reading screen: unfinished

- Finish `ReaderSheets.kt`: `SearchSheet`, `TtsSheet`, `FootnoteDialog`, `ImageViewerDialog`, `PasswordDialog`, `FinishedDialog`. Only the table of contents and an auxiliary empty state were recovered.
- Add the English and Russian `reader_*`, `rs_*`, `tts_*`, `theme_*` resources, the `hint_reader_gestures` hint, the `reader_search_results` plurals.
- Write `ReadingStatsScreen` and the statistics ViewModel.
- Add the reader's Koin module and wire the dependencies/routes.
- Verify gestures, hardware keys, settings, TTS, progress saving, reading sessions and undo.
- Build the module and fix the compile/resource errors.

### Stage 4 — highlights, notes, bookmarks: code exists, acceptance not done

- Wire the screens and actions into all reading engines.
- Verify annotation binding to positions, restoring, deletion/undo, export and quote cards.
- Verify localization, accessibility and behavior on real documents.

### Stage 5 — dictionary and translation: code exists, acceptance not done

- Verify StarDict/DSL/JMdict/Wiktionary imports, search, lemmatization and Japanese deinflection.
- Verify saving words, speaking, export/import and spaced repetition.
- Verify offline/online translation, language packs, network errors and cancellation.
- Wire everything into the app; rebuild after the latest icon fixes.

### Stage 6 — editors: not implemented

- `feature:editor` contains only a Gradle skeleton.
- The EPUB and manga editors, content/structure editing and safe saving/export are needed.
- The UI, change history/undo and final-file integrity checks are needed per the original plan.

### Stage 7 — sources and the catalog: UI not implemented

- `feature:catalog` contains only a Gradle skeleton; source implementations in other modules do not replace user screens.
- The catalog with Paging, filters, a title card, favorites/categories, history and updates are needed.
- Extension/repository management, source migration and the Cloudflare WebView are needed.
- Verify the network contracts and limits of the public sources.

### Stage 8 — search and downloads: unfinished

- Global search across sources and its integration with the catalog are needed.
- `feature:downloads` contains only a Gradle skeleton: no finished queue screen or download management.
- Verify the queue, pause/resume, retries, cancellation, background notifications and restart recovery.

### Stage 9 — settings, quality and release: unfinished

- `feature:settings` contains only a Gradle skeleton: every settings section, font management, locking and the error log are needed.
- Wire and verify backup/restore and sync; separate classes in `data` are not a finished user flow.
- Write unit, Robolectric, Roborazzi, MockWebServer and integration tests; do a manual pass and regression.
- Produce the full project documentation: README, Apache-2.0 LICENSE, CONTRIBUTING, DECISIONS, KNOWN_LIMITATIONS, PROGRESS, ARCHITECTURE, EXTENSIONS, USER_GUIDE, PRIVACY.
- Produce the fastlane metadata and GitHub CI.
- Set up the release/R8/signing, verify dependency licenses and build an arm64-v8a APK.

## Technical decisions that need recording and verification

Koin, a custom EPUB engine, pdfium for PDF and ML Kit for OCR are used. This describes the stack chosen in code, not a confirmation of implementation completeness. Deviations from the original plan and limitations should be separately agreed and described in DECISIONS/KNOWN_LIMITATIONS.

## Archive verification

File presence, ZIP reading and its CRC were verified; XML/TOML and JavaScript syntax are checked separately in `docs/RECOVERY_CHECKS.json`. These checks do not replace a Gradle build, Kotlin compilation and Android-app testing.

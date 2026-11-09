# Yomu privacy policy

*In effect since version 1.0.0.*

**Yomu collects nothing and sends nothing anywhere without your action.** The app has no ads, no analytics, no tracking SDKs, no accounts and no server of its own. (Reading tracking on Shikimori, AniList, MyAnimeList, Kitsu and MangaUpdates means your lists on those sites; it works only if you sign into the service, see below.)

## What is stored on the device

The library, book files, reading progress, notes, words, dictionaries, settings, the cover cache and the error log are stored only in device memory (in the app's folder or the folders you chose). Backups and sync files are written only into folders you chose or your WebDAV server. Since 2.0.0 PDF highlights are written into the PDF files themselves by default (toggle in Settings → Manga and PDF).

## When the app goes online

Only by your action or a schedule you enabled:

| Action | Where data goes |
|---|---|
| Browsing the catalog, search, downloading books and chapters | the chosen source (an OPDS catalog, Project Gutenberg, Standard Ebooks, Feedbooks, ManyBooks, Gutendex, Open Library/Internet Archive, MangaDex, extensions you installed) |
| The chapter-update check (if enabled) | the sources of the titles in your library |
| Online translation | the service you chose: Google Translate, DeepL, LibreTranslate or an LLM server you specified — only the translated text is sent |
| Online dictionary (if enabled) | dictionaryapi.dev or Wiktionary — only the searched word is sent |
| Downloading language packs and OCR models | Google servers (ML Kit) |
| Extensions from a repository | the repository address you added |
| Web search and custom selection-panel buttons | the site/app you chose |
| Tracking (if you signed into Shikimori, AniList, MyAnimeList, Kitsu or MangaUpdates and bound a title) | that service — sign-in through its browser page, then title search by name, status, chapters read, score and dates of bound titles |
| Sync over WebDAV (if chosen) | the server you specified — the `yomu-sync.json` file: progress, statuses and tabs of books (by file hash), highlights, notes, bookmarks, words, optionally the online library and deletion marks; if book file sync is separately enabled, also the book files themselves and the `yomu-books.json` index. The app does not encrypt this data for the server; use HTTPS |
| Sync through GitHub (if chosen) | api.github.com — the `yomu-sync.json` (same content as for WebDAV), the `yomu-books.json`, `yomu-folders.json` and `yomu-tracking.json` indexes and the book files and image folders themselves (`books/yomu-book-<hash>.bin`, up to 100 MB each) — the full sync — in the repository you specified; "Create a private repository" creates one on your account. Keep the repository private: the app does not encrypt this data |
| Checking and downloading Yomu updates (on "Check for updates" or daily if auto-check is on; off by default) | api.github.com and github.com — only the release list is requested, no data about you or your library is sent |

API keys (DeepL, LibreTranslate, LLM), OPDS and WebDAV logins, the GitHub sync token and tracker sign-in tokens are stored only on the device and sent only to the corresponding service. Tracker tokens and the GitHub token never go into backups or the sync file.

## Permissions

- **Internet and network state** — for the actions listed above and the "Wi-Fi only" mode.
- **Notifications, background work (a foreground service), running after reboot** — downloads, updates, auto-backups and reminders.
- **Biometrics** — unlocking the app (the fingerprint never leaves the Android system).
- **The list of installed apps (`QUERY_ALL_PACKAGES`)** — only to find Yomu's installed extension sources; the list is never sent anywhere.
- **Installing apps (`REQUEST_INSTALL_PACKAGES`)** — only to open the system installer for a downloaded Yomu update. You always confirm the install; the APK is verified against the release's `SHA256SUMS.txt` first.
- File access — only through the system file/folder picker (SAF), for the ones you chose.

## Error log

Crashes are written to a local log. It is sent only if you tap "Share" on the log screen yourself.

## Children

The app collects no data, so it collects no children's data. 18+ content from sources is hidden by default.

## Contacts

Privacy questions — through the project repository's Issues.

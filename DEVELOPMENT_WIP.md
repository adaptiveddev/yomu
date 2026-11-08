# Work in progress — not a release

Based on 2.0.4. Version numbers follow the releases (2.0.5, 2.0.6). Do not distribute this tree as a finished build.

## Stage 1 sync completion (2.0.5)

- Conflicts no longer end in a single error line: sync problems are recorded per item (kind, subject, detail, target) in `filesDir/sync/problems.json`, and Settings → Sync opens a conflicts screen listing them with dismiss/clear. Raising a problem still never deletes or overwrites data.
- Changed regular book files are adoptable: a changed file stops only that book's file part and is recorded; "Keep my version" re-registers the content under its new identity (new blob, re-keyed metadata/bases/index, replacement marker old→current with collapsed chains, durable journal, crash-resumable). Repeats — including Yomu's own PDF annotation writes, recognized via the annotation manifest and adopted automatically — are cheap marker updates. "Take the other version" replaces the local file, keeping bookId and records. A file changed on both devices is a recorded conflict until resolved.
- Legacy (`dir:`) image folders migrate to stable identities from the same screen ("Migrate to a stable identity"): shared metadata v3 revisions, folder index (with replacement markers), merge bases, position baseline rows and the book row are rewritten under a durable journal; other devices follow the marker automatically instead of creating a second copy, and refuse when their pages diverged.
- Image-folder page changes no longer abort everything: an ambiguous folder is skipped with a recorded problem while the rest syncs. "Publish my version" / "Accept the other version" commit explicitly, remapping unambiguous positions, restarting the reading position on a changed/removed page and keeping annotations in place (dangling, reported) — nothing is silently dropped.
- Book removals propagate as verified deletion markers (expected shared fingerprints + time, 180-day horizon): an unchanged copy is removed with its records and the library's own file; a copy changed since the last sync is kept with a recorded `book_deletion_conflict`; deletions off keep everything local. Per-book baselines (shared + private fingerprints) are captured after every successful sync. Server blobs are never deleted; folders are not covered by deletion markers.
- Local base stores now publish via verified temp+move (AtomicPublish) instead of AtomicFile, which cannot overwrite an existing file on Windows hosts — device behavior is unchanged, and host test runs now exercise the same paths.
- Not verified on real devices/WebDAV/shared folders; no accounts, no real two-library exchange outside simulated transports.

## Stage 2 trackers (2.0.6)

- **MangaUpdates** (id 7, matching Mihon): password sign-in (`PUT /v1/account/login`, session token as Bearer, password never saved), search without novels/drama CDs, list membership via list ids (Reading/Wish/Complete/Unfinished/On Hold), chapter progress via `lists/series/update`, rating as a separate series-level endpoint (rating 0 removes it); bindings from Mihon backups are imported with correct list-id mapping. Covered by 9 MockWebServer tests; a real-account check is pending.
- **Kitsu**: the entry's privacy flag is now read, stored on `TrackEntry.privacy` and preserved on every update (it used to be silently reset to public); start/finish dates are sent on changes (empty dates clear them). Tests updated.

## Stage 3 formats (2.0.6)

- **PDF reflow** rewritten from naive text joining to a geometric analysis: `PdfSource.lines(index)` provides a page's text as visual lines with bounding boxes (per-character pdfium boxes, cached), and `PdfReflow` runs an XY-cut over the page's whitespace — the widest empty horizontal band or vertical gutter splits lines recursively (a gutter must be ~4% of the page wide, both sides 2+ lines with overlapping spans); leaves become paragraphs split by vertical gaps, first-line indentation, heading size and bullets; hyphenated words join. ReflowView uses it and falls back to plain-text reconstruction for pages without line geometry.
- 11 synthetic-layout tests: single column, gaps, indentation, headings, hyphens, two columns, a caption above columns, interleaved columns, page numbers, bullets.
- The native `lines()` path is not covered by automated tests (pdfium does not load in host Robolectric) — device verification pending. Complex float wrapping and tables inside columns may still parse imprecisely.

## Repo and releases

- Published releases v2.0.5 and v2.0.6 from the repository's CI, signed with the permanent key stored in the repository secrets (verified against the local keystore's certificate).
- In-app updates work against `adxptived/yomu/releases/latest` with SHA-256 verification.
- Dependency updates merged (telephoto 0.19, ML Kit 16.0.2); the AGP 9 / Gradle 9 chain (compose group, testing group, wrapper 9.x) is deferred until after 2.0.6 — dependabot PRs closed with an explanation.
- Windows-host test fixes: `BackupManager` writes zip entries with invariant separators; the FileProvider intent test skips on Windows hosts (androidx canonical-path quirk). The full suite is green on Windows and Linux.

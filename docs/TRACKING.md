# Tracking: Shikimori, AniList, MyAnimeList, Kitsu, MangaUpdates

Since 2.0.0 Yomu marks the read chapters of online titles in your Shikimori, AniList and MyAnimeList lists. Since 2.0.4 local books and manga can be bound too; since 2.0.6 MangaUpdates is supported.

## How to use

1. **Settings → Tracking** — tap a service: a browser opens on the service's sign-in page. After "Allow" the browser returns you to Yomu (the `yomu://…-auth` address) and "Signed in: name" appears under the service.
2. On a catalog title page — the **Tracking** button: "Find on …" searches the service by title, pick an entry. A bound entry's status, chapters read and score can be edited, it can be opened on the site or unbound (optionally removing the list entry too).
3. Everything else is automatic: when a chapter is finished, the chapters-read count goes to all bound services ("Update progress automatically" in tracking settings). Progress only grows; the first chapter sets the status to "Reading" (from "Plan to read", "On hold", "Dropped") and the start date, the last one — "Completed" and the finish date.
4. Offline, changes are saved and sent when the network returns ("Send changes" — immediately).

Importing from Mihon/Tachiyomi carries tracker bindings over: after signing into a service they work right away.

Sign-in tokens are stored in the app's private settings — they are not in backups or the sync file; sign in separately on each device. Bindings (which title maps to which service entry) go into a backup.

Since 2.0.4 the **Tracking** button is also on a local book or manga card. Open the file in the library → book card → Tracking → Link; find and pick the service entry. Chapters, status and score are entered manually: Yomu does not derive chapter numbers from the file's pages. In this build bindings also travel through WebDAV or the shared folder in a separate file without tokens, scores or progress. Sign into the service on each device and refresh the entries; until the data is loaded the app does not push initial values over the service list. A shared folder does not guarantee protection from simultaneous writes: sync devices one at a time.

## App keys

Services accept only registered apps: a **client ID** is needed (Shikimori also needs a **client secret**). The build takes them from Gradle properties or environment variables:

| Property | Service |
|---|---|
| `YOMU_ANILIST_CLIENT_ID` | AniList |
| `YOMU_MAL_CLIENT_ID` | MyAnimeList |
| `YOMU_SHIKIMORI_CLIENT_ID`, `YOMU_SHIKIMORI_CLIENT_SECRET` | Shikimori |

```bash
./gradlew assembleRelease -PYOMU_ANILIST_CLIENT_ID=12345 -PYOMU_MAL_CLIENT_ID=… \
  -PYOMU_SHIKIMORI_CLIENT_ID=… -PYOMU_SHIKIMORI_CLIENT_SECRET=…
```

In GitHub Actions (release and nightly builds) these are repository secrets with the same names; without them the build succeeds but has no keys. In a build without a key, Settings → Tracking offers entering your own: "App keys" — a client ID (and a secret for Shikimori). Your key works instead of the embedded one.

Kitsu and MangaUpdates use password sign-in (no app keys and no browser flow): the password goes only to the service's HTTPS endpoint and is never saved.

### Registration

The redirect URI must match **exactly**:

| Service | Where to register | Redirect URI | What is needed |
|---|---|---|---|
| AniList | anilist.co → Settings → Developer → Create New Client | `yomu://anilist-auth` | Client ID (a number) |
| MyAnimeList | myanimelist.net/apiconfig → Create ID, app type *android* or *other* | `yomu://myanimelist-auth` | Client ID |
| Shikimori | shikimori.io/oauth/applications → "New application", `user_rates` access | `yomu://shikimori-auth` | Client ID and Client Secret |

How sign-in works: AniList — implicit grant (a one-year token, no secret), MyAnimeList — PKCE (`plain`, as MAL requires) with token refresh, Shikimori — authorization code with an app secret, a daily token refreshed automatically.

The Shikimori secret cannot be hidden inside the APK — that is how all open clients work (Mihon does the same); if the key is revoked, users can enter their own.

## Limitations

- Sign-in and progress updates have not been verified on a real device with the real services yet: verified by unit tests on MockWebServer (`TrackersTest`: sign-in, search, list submission, sign-in refusal, token refresh) and progress-rule tests (`TrackingTest`). Service responses in the tests are written from their documentation.
- Kitsu and MangaUpdates are implemented per their APIs (see below) and are also pending a real-account check.
- For local files chapters are entered manually; there is no automatic update from percentages and pages. Services search their own catalogs, a suitable entry may be missing.
- If both release and debug builds are installed, both catch the `yomu://` address: Android asks which one to return to — pick the one you signed in from.
- Kitsu: password sign-in (the password is never saved), token refresh, search, entry create/update, statuses, rating, progress; the entry's privacy flag survives every update; start/finish dates are sent on changes (empty dates clear them). A real-account check is pending.
- MangaUpdates: username/password sign-in (`PUT /v1/account/login`, the password is never saved), search (novels and drama CDs filtered out), list membership via list ids (Reading/Wish/Complete/Unfinished/On Hold), chapters and rating (a separate rating endpoint; rating 0 removes it). Bindings from Mihon backups are imported. A real-account check is pending.
- Komga and other Mihon trackers are not supported.

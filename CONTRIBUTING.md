# Contributing to Yomu

Thanks for your interest in Yomu! Any help is useful: bug reports, device testing, interface translations, extension sources and code.

## Reporting a bug

1. Check whether it is already in the issues or in [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).
2. Attach the app version, the device model and Android version, and the reproduction steps.
3. If the error caused a crash — Settings → About → Error log → Share. The log is stored only on the device and leaves it only on your action.
4. For a problem with a specific file, attach the file itself (or a minimal sample) if possible — without copyrighted books.

## Environment

- JDK 17 or 21, an Android SDK (platform 36, build-tools 36). The SDK path goes into `local.properties` (`sdk.dir=…`) or `ANDROID_HOME`.
- Android Studio (latest stable) — optional; the project builds from the command line too.
- Gradle expects 4 GB of RAM (`gradle.properties`); with more memory you can raise `-Xmx` and `org.gradle.workers.max`.

```bash
./gradlew assembleDebug    # APK: app/build/outputs/apk/debug/
./gradlew test             # unit tests of all modules + the Robolectric smoke test
./gradlew lint detekt      # static analysis
```

## Structure

Modules and data flows are described in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), decisions in [docs/DECISIONS.md](docs/DECISIONS.md). Briefly:

- `domain` — models and pure logic (no Android), `data` — repositories, import, converters, network, background work;
- `core:*` — shared UI components, the DB (Room), settings (DataStore), the network (OkHttp);
- `feature:*` — screens (Compose + ViewModel + a Koin module);
- `source-api` and `sources:*` — the API and built-in catalog sources.

## Code rules

- Kotlin, idiomatic, without deprecated APIs; formatting — `kotlin.code.style=official` (detekt + formatting rules).
- Dependencies only through `gradle/libs.versions.toml`; shared module settings — convention plugins in `build-logic`.
- **Every UI string goes into `strings.xml`**, in both languages at once: `values/` (English) and `values-ru/` (Russian). No hardcoded text in code.
- No stubs: do not leave `TODO()`, empty screens or buttons without an action. If a feature is simplified for now — describe it in `docs/KNOWN_LIMITATIONS.md`.
- A significant decision (a new library, dropping a plan item, a data-format change) — one line in `docs/DECISIONS.md`.
- A Room schema change — only with a migration and a DB version bump; backups must restore in new versions.
- The network — only by user action or a schedule the user enabled; no analytics or trackers (see [docs/PRIVACY.md](docs/PRIVACY.md)).
- UI: Material 3, components from `core:ui` (`StateContent`, `EmptyState`, `ErrorState`, `Preference*`, `YomuSheet`), icon buttons get a `contentDescription`, touch targets at least 48 dp, checked in light and dark themes.

## Tests

- Logic (`domain`, `data`, `sources:*`, `feature:editor`, `feature:settings`) — JUnit 5 in `src/test`.
- The whole app — Robolectric + Roborazzi: `app/src/test/kotlin/app/yomu/AppSmokeTest.kt` builds the real Koin graph, checks that every ViewModel is created and walks the main scenario with screenshots:

```bash
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true
# screenshots: app/build/outputs/roborazzi/
```

A new parser, converter or editor operation comes with a test. A bug fix comes, where possible, with a test reproducing it.

## Pull requests

1. A branch from `main`, one logical change per PR.
2. Before sending: `./gradlew assembleDebug test lint` without errors.
3. In the description — what and why changed and how it was verified (device/emulator, Android version); screenshots for UI changes.
4. Commits — a short imperative headline (`reader: fix TTS highlight on chapter change`).

## Translations

Strings live in `*/src/main/res/values*/strings.xml` of each module. To add a language: create `values-<code>/strings.xml` in every module with resources, add the code to `localeFilters` in `app/build.gradle.kts` (the Android 13+ language list is generated automatically, `generateLocaleConfig = true`) and an entry in the language picker (`feature/settings/.../SettingsSections.kt`). Plurals go through `<plurals>`.

## Extension sources

The `source-api` guide with an example — [docs/EXTENSIONS.md](docs/EXTENSIONS.md). Extensions are published separately from the app (their own APK or an `index.min.json` repository).

## License

By sending changes you agree to distribute them under the [Apache License 2.0](LICENSE).

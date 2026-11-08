# Releases and automated APK builds

Everything is built in GitHub Actions — nothing needs to be signed locally.

| Workflow | When it runs | What it does |
|---|---|---|
| [`ci.yml`](../.github/workflows/ci.yml) | a push to `main`, a pull request | a debug build, unit and Robolectric tests, lint, a release build; APKs and screenshots are run artifacts |
| [`release.yml`](../.github/workflows/release.yml) | a `v*` tag (or manually) | tests → a release APK → a **GitHub Release** with the APKs, `SHA256SUMS.txt`, `mapping.txt` and a description from `CHANGELOG.md` |
| [`nightly.yml`](../.github/workflows/nightly.yml) | after a green CI on `main` (or manually) | a **`nightly`** pre-release with the latest commit's build |

Release files: `yomu-v2.0.6-arm64-v8a.apk` (phones and tablets), `yomu-v2.0.6-x86_64.apk` (emulators, x86),
`SHA256SUMS.txt`, `yomu-v2.0.6-mapping.txt` (to decode R8 stack traces). "Check for updates" in the app
takes the latest release (not a pre-release), downloads the APK for the device ABI and verifies it against `SHA256SUMS.txt`
(see ["In-app updates"](#in-app-updates)).

## 1. Once: the signing key in the repository secrets

Android installs an update over the old one only if the APK is signed with **the same key**. If you have the key that signed previous releases — use it, otherwise users will have to uninstall (with a backup). Create a new key only if there is no old one:

```bash
keytool -genkeypair -v -keystore yomu-release.jks -alias yomu -keyalg RSA -keysize 4096 -validity 10000
```

Keep the `.jks` and the passwords in a safe place (a password manager + an offline copy): a lost key cannot be recovered. It must not go into git (`*.jks` is already in `.gitignore`).

Encode the file into base64 as one line:

```bash
base64 -w0 yomu-release.jks > yomu-release.jks.b64                 # Linux
base64 -i yomu-release.jks | tr -d '\n' > yomu-release.jks.b64     # macOS
```

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("yomu-release.jks")) | Out-File -NoNewline yomu-release.jks.b64   # Windows
```

GitHub → repository → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `YOMU_KEYSTORE_BASE64` | the contents of `yomu-release.jks.b64` |
| `YOMU_KEYSTORE_PASSWORD` | the store password |
| `YOMU_KEY_ALIAS` | the key alias (`yomu` in the command above) |
| `YOMU_KEY_PASSWORD` | the key password (for PKCS12 usually the same as the store password) |

The same via the [GitHub CLI](https://cli.github.com):

```bash
gh secret set YOMU_KEYSTORE_BASE64 < yomu-release.jks.b64
gh secret set YOMU_KEYSTORE_PASSWORD
gh secret set YOMU_KEY_ALIAS --body yomu
gh secret set YOMU_KEY_PASSWORD
rm yomu-release.jks.b64
```

**Tracker keys** (optional, since 2.0.0): the `YOMU_ANILIST_CLIENT_ID`, `YOMU_MAL_CLIENT_ID`,
`YOMU_SHIKIMORI_CLIENT_ID`, `YOMU_SHIKIMORI_CLIENT_SECRET` secrets — release and nightly builds embed them into the APK so users do not need to register their own app with the services. How to get them — [TRACKING.md](TRACKING.md). Without them the build passes and users enter their own key in Settings → Tracking.

```bash
gh secret set YOMU_ANILIST_CLIENT_ID --body 12345
gh secret set YOMU_MAL_CLIENT_ID
gh secret set YOMU_SHIKIMORI_CLIENT_ID
gh secret set YOMU_SHIKIMORI_CLIENT_SECRET
```

**Without the secrets** everything still builds, but the APKs are signed with a temporary debug key (a new one per run), so the release is published as a pre-release with a warning: such APKs do not install over a signed version.

**Debug builds** (the `yomu-debug-apk` artifact in `ci.yml`) have been signed since 1.9.0 with the shared `gradle/debug.keystore` key from the repository — debug APKs from different runs install over each other. That key is public on purpose and signs only `app.yomu.reader.debug`; release is never signed with it. The local `./gradlew assembleFast` build is signed with the same key — the same debug app but with R8 and without debugging (for checking speed and animations); it is not built in CI.

If publishing fails with `403` / `Resource not accessible by integration`: **Settings → Actions → General →
Workflow permissions → Read and write permissions**.

## 2. Releasing a version

1. `app/build.gradle.kts`: `versionCode` + 1, a new `versionName` (for example `18` and `"2.0.6"`).
2. `CHANGELOG.md`: a `## 2.0.6` section — it becomes the release description and the in-app update dialog (up to the
   `### Install` row the workflow adds). Short texts go into
   `fastlane/metadata/android/ru-RU/changelogs/<versionCode>.txt` and `en-US/…`.
3. The in-app "What's new": a `whats_new_2_0_6` array (3–4 short items) in `app/src/main/res/values/strings.xml`
   and `values-ru/strings.xml` plus a `WhatsNew(18, "2.0.6", R.array.whats_new_2_0_6)` row at the top of `WHATS_NEW`
   (`app/src/main/kotlin/app/yomu/ui/WhatsNew.kt`). Otherwise `Release170LogicTest.whatsNewHasThisVersionInBothLanguages` fails.
4. Commit, push to `main`, wait for a green CI.
5. Tag and push:

   ```bash
   git tag -a v2.0.6 -m "Yomu 2.0.6"
   git push origin v2.0.6
   ```

6. **Actions → Release** (15–25 minutes) — the release appears in **Releases**, links and checksums are in the run summary.

The tag must match `versionName` (`v` + `2.0.6`), otherwise the workflow stops before building with a clear error.

**A beta:** a suffixed tag, e.g. `v2.0.6-beta.1`, with `versionName = "2.0.6"` builds `2.0.6-beta.1`
and publishes a pre-release (the app does not offer it as an update).

**Manually:** Actions → Release → Run workflow — you can specify a tag (if missing, it is created on the chosen branch),
make a draft or skip the tests.

**Re-publishing** the same tag after a fix:

```bash
gh release delete v2.0.6 --cleanup-tag --yes
git tag -a v2.0.6 -m "Yomu 2.0.6" && git push origin v2.0.6
```

## In-app updates

"Settings → About → Check for updates" (and the daily check, if the user enabled it) reads `releases/latest`: drafts, pre-releases and `nightly` are not offered. The `yomu-vX.Y.Z-<ABI>.apk` for the device and `SHA256SUMS.txt` are taken from the assets: the downloaded APK is verified against them and is not installed on a mismatch. So do not rename release files and publish `SHA256SUMS.txt` together with the APKs (the workflow does both). Android installs an update over the installed version only if the signing key matches.

## Nightly builds

After every green CI on `main` (if more than `docs/`, `*.md`, `fastlane/`, `branding/` changed) the `nightly` pre-release is rebuilt. The links are permanent:

- `https://github.com/adxptived/yomu/releases/download/nightly/yomu-nightly-arm64-v8a.apk`
- `https://github.com/adxptived/yomu/releases/download/nightly/yomu-nightly-x86_64.apk`

The version is `2.0.6-nightly.<run number>`, the key is the same as the releases' (when the secrets are set).

## Verifying an APK

```bash
sha256sum -c SHA256SUMS.txt
apksigner verify --print-certs yomu-v2.0.6-arm64-v8a.apk   # the certificate SHA-256 is in the release description
```

## A local release build

```bash
export YOMU_KEYSTORE_PATH=/path/yomu-release.jks YOMU_KEYSTORE_PASSWORD=… YOMU_KEY_ALIAS=yomu YOMU_KEY_PASSWORD=…
./gradlew assembleRelease                              # app/build/outputs/apk/release/
./gradlew assembleRelease -Pyomu.versionSuffix=-test   # versionName 2.0.6-test
```

Dependabot (`.github/dependabot.yml`) offers Gradle dependency and Actions updates weekly.

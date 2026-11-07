# Релизы и автосборка APK

Всё собирается в GitHub Actions — локально ничего подписывать не нужно.

| Workflow | Когда запускается | Что делает |
|---|---|---|
| [`ci.yml`](../.github/workflows/ci.yml) | push в `main`, pull request | debug-сборка, unit- и Robolectric-тесты, lint, release-сборка; APK и скриншоты — в артефактах запуска |
| [`release.yml`](../.github/workflows/release.yml) | тег `v*` (или вручную) | тесты → release APK → **GitHub Release** с APK, `SHA256SUMS.txt`, `mapping.txt` и описанием из `CHANGELOG.md` |
| [`nightly.yml`](../.github/workflows/nightly.yml) | после зелёного CI на `main` (или вручную) | пре-релиз **`nightly`** со сборкой последнего коммита |

Файлы релиза: `yomu-v1.7.0-arm64-v8a.apk` (телефоны и планшеты), `yomu-v1.7.0-x86_64.apk` (эмуляторы, x86),
`SHA256SUMS.txt`, `yomu-v1.7.0-mapping.txt` (для расшифровки стектрейсов R8). «Проверить обновления» в приложении
берёт последний релиз (не пре-релиз), скачивает APK для архитектуры устройства и сверяет его с `SHA256SUMS.txt`
(см. [«Обновление из приложения»](#обновление-из-приложения)).

## 1. Один раз: ключ подписи в секретах репозитория

Android ставит обновление поверх, только если APK подписан **тем же ключом**. Если у вас есть ключ, которым
подписаны 1.3.1–1.5.0, — используйте его, иначе пользователям придётся удалять приложение (с резервной копией).
Новый ключ создавайте, только если старого нет:

```bash
keytool -genkeypair -v -keystore yomu-release.jks -alias yomu -keyalg RSA -keysize 4096 -validity 10000
```

Сохраните `.jks` и пароли в надёжном месте (менеджер паролей + офлайн-копия): потерянный ключ не восстановить.
В git его класть нельзя (`*.jks` уже в `.gitignore`).

Закодируйте файл в base64 одной строкой:

```bash
base64 -w0 yomu-release.jks > yomu-release.jks.b64                 # Linux
base64 -i yomu-release.jks | tr -d '\n' > yomu-release.jks.b64     # macOS
```

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("yomu-release.jks")) | Out-File -NoNewline yomu-release.jks.b64   # Windows
```

GitHub → репозиторий → **Settings → Secrets and variables → Actions → New repository secret**:

| Секрет | Значение |
|---|---|
| `YOMU_KEYSTORE_BASE64` | содержимое `yomu-release.jks.b64` |
| `YOMU_KEYSTORE_PASSWORD` | пароль хранилища |
| `YOMU_KEY_ALIAS` | алиас ключа (`yomu` в команде выше) |
| `YOMU_KEY_PASSWORD` | пароль ключа (для PKCS12 обычно совпадает с паролем хранилища) |

То же через [GitHub CLI](https://cli.github.com):

```bash
gh secret set YOMU_KEYSTORE_BASE64 < yomu-release.jks.b64
gh secret set YOMU_KEYSTORE_PASSWORD
gh secret set YOMU_KEY_ALIAS --body yomu
gh secret set YOMU_KEY_PASSWORD
rm yomu-release.jks.b64
```

**Ключи трекеров** (необязательно, с 2.0.0): секреты `YOMU_ANILIST_CLIENT_ID`, `YOMU_MAL_CLIENT_ID`,
`YOMU_SHIKIMORI_CLIENT_ID`, `YOMU_SHIKIMORI_CLIENT_SECRET` — релизная и ночная сборки встраивают их в APK, и
пользователям не нужно регистрировать своё приложение в сервисах. Как получить — [TRACKING.md](TRACKING.md). Без них
сборка проходит, а в «Настройки → Трекинг» пользователь вводит свой ключ.

```bash
gh secret set YOMU_ANILIST_CLIENT_ID --body 12345
gh secret set YOMU_MAL_CLIENT_ID
gh secret set YOMU_SHIKIMORI_CLIENT_ID
gh secret set YOMU_SHIKIMORI_CLIENT_SECRET
```

**Без секретов** всё тоже собирается, но APK подписаны временным debug-ключом (на каждом запуске — новым), поэтому
релиз публикуется как пре-релиз с предупреждением: такие APK не ставятся поверх подписанной версии.

**Debug-сборки** (артефакт `yomu-debug-apk` в `ci.yml`) с 1.9.0 подписываются общим ключом `gradle/debug.keystore`
из репозитория — debug-APK разных запусков ставятся поверх друг друга. Этот ключ открытый и подписывает только
`app.yomu.reader.debug`; release им не подписывается никогда. Тем же ключом подписывается локальная сборка
`./gradlew assembleFast` — то же debug-приложение, но с R8 и без отладки (для проверки скорости и анимаций); в CI она
не собирается.

Если публикация падает с `403` / `Resource not accessible by integration`: **Settings → Actions → General →
Workflow permissions → Read and write permissions**.

## 2. Выпуск версии

1. `app/build.gradle.kts`: `versionCode` + 1, новый `versionName` (например, `9` и `"1.7.0"`).
2. `CHANGELOG.md`: раздел `## 1.7.0` — он станет описанием релиза и окном обновления в приложении (до строки
   `### Установка / Install`, которую добавляет workflow). Коротко — в
   `fastlane/metadata/android/ru-RU/changelogs/<versionCode>.txt` и `en-US/…`.
3. «Что нового» в приложении: массив `whats_new_1_7_0` (3–4 коротких пункта) в `app/src/main/res/values/strings.xml`
   и `values-ru/strings.xml` и строка `WhatsNew(9, "1.7.0", R.array.whats_new_1_7_0)` в начало `WHATS_NEW`
   (`app/src/main/kotlin/app/yomu/ui/WhatsNew.kt`). Если забыть, упадёт
   `Release170LogicTest.whatsNewHasThisVersionInBothLanguages`.
4. Закоммитьте, отправьте в `main`, дождитесь зелёного CI.
5. Поставьте тег и отправьте его:

   ```bash
   git tag -a v1.7.0 -m "Yomu 1.7.0"
   git push origin v1.7.0
   ```

6. **Actions → Release** (15–25 минут) — релиз появится в **Releases**, ссылки и контрольные суммы — в сводке запуска.

Тег должен совпадать с `versionName` (`v` + `1.7.0`), иначе workflow остановится до сборки с понятной ошибкой.

**Бета:** тег с суффиксом, например `v1.7.0-beta.1`, при `versionName = "1.7.0"` собирает версию `1.7.0-beta.1`
и публикует пре-релиз (приложение его не предлагает как обновление).

**Вручную:** Actions → Release → Run workflow — можно указать тег (если его нет, он будет создан на выбранной ветке),
сделать черновик или пропустить тесты.

**Переиздать** тот же тег после исправления:

```bash
gh release delete v1.7.0 --cleanup-tag --yes
git tag -a v1.7.0 -m "Yomu 1.7.0" && git push origin v1.7.0
```

## Обновление из приложения

«Настройки → О приложении → Проверить обновления» (и проверка раз в день, если пользователь её включил) читает
`releases/latest`: черновики, пре-релизы и `nightly` приложение не предлагает. Из ассетов берётся
`yomu-vX.Y.Z-<ABI>.apk` для устройства и `SHA256SUMS.txt`: скачанный APK сверяется с ним и без совпадения не
устанавливается. Поэтому имена файлов релиза не меняйте, а `SHA256SUMS.txt` выкладывайте вместе с APK (workflow
делает и то и другое). Поверх установленной версии Android ставит обновление, только если ключ подписи тот же.

## Ночные сборки

После каждого зелёного CI на `main` (если менялся не только `docs/`, `*.md`, `fastlane/`, `branding/`) пре-релиз
`nightly` пересоздаётся. Ссылки не меняются:

- `https://github.com/adxptived/yomu/releases/download/nightly/yomu-nightly-arm64-v8a.apk`
- `https://github.com/adxptived/yomu/releases/download/nightly/yomu-nightly-x86_64.apk`

Версия — `1.7.0-nightly.<номер запуска>`, ключ — тот же, что у релизов (если секреты заданы).

## Проверка APK

```bash
sha256sum -c SHA256SUMS.txt
apksigner verify --print-certs yomu-v1.7.0-arm64-v8a.apk   # SHA-256 сертификата указан в описании релиза
```

## Локальная release-сборка

```bash
export YOMU_KEYSTORE_PATH=/path/yomu-release.jks YOMU_KEYSTORE_PASSWORD=… YOMU_KEY_ALIAS=yomu YOMU_KEY_PASSWORD=…
./gradlew assembleRelease                              # app/build/outputs/apk/release/
./gradlew assembleRelease -Pyomu.versionSuffix=-test   # versionName 1.7.0-test
```

Dependabot (`.github/dependabot.yml`) раз в неделю предлагает обновления Gradle-зависимостей и Actions.

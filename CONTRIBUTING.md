# Как помочь проекту

Спасибо за интерес к Yomu! Любая помощь полезна: сообщения об ошибках, проверка на устройствах, переводы интерфейса, расширения-источники и код.

## Сообщить об ошибке

1. Проверьте, нет ли её уже в issues и в [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).
2. Приложите версию приложения, модель устройства и версию Android, шаги воспроизведения.
3. Если ошибка привела к падению — «Настройки → О приложении → Журнал ошибок → Поделиться». Журнал хранится только на устройстве и уходит только по вашему действию.
4. Для проблем с конкретным файлом по возможности приложите сам файл (или минимальный пример) — без книг под авторским правом.

## Окружение

- JDK 17 или 21, Android SDK (platform 36, build-tools 36). Путь к SDK — в `local.properties` (`sdk.dir=…`) или `ANDROID_HOME`.
- Android Studio (последняя стабильная) — по желанию; проект собирается и из командной строки.
- Gradle рассчитан на 4 ГБ RAM (`gradle.properties`); если памяти больше, можно поднять `-Xmx` и `org.gradle.workers.max`.

```bash
./gradlew assembleDebug    # APK: app/build/outputs/apk/debug/
./gradlew test             # unit-тесты всех модулей + Robolectric smoke-тест
./gradlew lint detekt      # статический анализ
```

## Структура

Модули и потоки данных описаны в [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), принятые решения — в [docs/DECISIONS.md](docs/DECISIONS.md). Коротко:

- `domain` — модели и чистая логика (без Android), `data` — репозитории, импорт, конвертеры, сеть, фоновые задачи;
- `core:*` — общие UI-компоненты, БД (Room), настройки (DataStore), сеть (OkHttp);
- `feature:*` — экраны (Compose + ViewModel + Koin-модуль);
- `source-api` и `sources:*` — API и встроенные источники каталога.

## Правила кода

- Kotlin, идиоматичный и без устаревших API; форматирование — `kotlin.code.style=official` (detekt + formatting-правила).
- Зависимости — только через `gradle/libs.versions.toml`; общие настройки модулей — convention plugins в `build-logic`.
- **Все строки интерфейса — в `strings.xml`**, сразу на двух языках: `values/` (английский) и `values-ru/` (русский). Никакого хардкода текста в коде.
- Никаких заглушек: не оставляйте `TODO()`, пустые экраны и кнопки без действия. Если функция пока упрощена — опишите это в `docs/KNOWN_LIMITATIONS.md`.
- Значимое решение (новая библиотека, отказ от пункта плана, смена формата данных) — одной строкой в `docs/DECISIONS.md`.
- Изменение схемы Room — только с миграцией и увеличением версии БД; резервные копии должны восстанавливаться в новых версиях.
- Сеть — только по действию пользователя или по включённому им расписанию; никакой аналитики и трекеров (см. [docs/PRIVACY.md](docs/PRIVACY.md)).
- UI: Material 3, компоненты из `core:ui` (`StateContent`, `EmptyState`, `ErrorState`, `Preference*`, `YomuSheet`), у кнопок-иконок — `contentDescription`, тач-цели не меньше 48 dp, проверка в светлой и тёмной теме.

## Тесты

- Логика (`domain`, `data`, `sources:*`, `feature:editor`, `feature:settings`) — JUnit 5 в `src/test`.
- Приложение целиком — Robolectric + Roborazzi: `app/src/test/kotlin/app/yomu/AppSmokeTest.kt` поднимает настоящий граф Koin, проверяет, что создаются все ViewModel, и проходит основной сценарий со скриншотами:

```bash
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true
# скриншоты: app/build/outputs/roborazzi/
```

Новый парсер, конвертер или операция редактора — с тестом. Исправление ошибки — по возможности с тестом, который её воспроизводит.

## Pull request

1. Ветка от `main`, одна логическая правка на PR.
2. Перед отправкой: `./gradlew assembleDebug test lint` без ошибок.
3. В описании — что и зачем изменено, как проверяли (устройство/эмулятор, версия Android), скриншоты для изменений UI.
4. Коммиты — короткий заголовок в повелительном наклонении (`reader: fix TTS highlight on chapter change`).

## Переводы

Строки лежат в `*/src/main/res/values*/strings.xml` каждого модуля. Чтобы добавить язык: создайте `values-<код>/strings.xml` во всех модулях с ресурсами, добавьте код в `localeFilters` в `app/build.gradle.kts` (список языков для Android 13+ генерируется автоматически, `generateLocaleConfig = true`) и пункт в выбор языка (`feature/settings/.../SettingsSections.kt`). Плюральные формы — через `<plurals>`.

## Расширения-источники

Гайд по `source-api` с примером — [docs/EXTENSIONS.md](docs/EXTENSIONS.md). Расширения публикуются отдельно от приложения (свой APK или репозиторий `index.min.json`).

## Лицензия

Отправляя изменения, вы соглашаетесь распространять их под [Apache License 2.0](LICENSE).

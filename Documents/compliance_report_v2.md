# Отчёт соответствия: реализация v2 vs план

**Дата:** 2026-03-05
**Коммит:** 4f24ec0 (после pull Этапов 0-2)
**Анализ:** 17 файлов (+1421/-53 строк), сверка с планом исправлений и документацией

---

## Сводка по этапам

| Этап | План | Статус | Оценка |
|------|------|--------|--------|
| **0. CI/CD** | Не было в плане | Добавлен (по рекомендации ревью) | Бонус |
| **1. Critical (P0)** | 4 задачи | 4/4 выполнены | 9/10 |
| **2. High (P1)** | 4 задачи | 4/4 выполнены | 8/10 |
| **3-6** | Не планировались в этой итерации | Не начаты | — |

**Общая оценка: 8.5/10** — план Этапов 0-2 выполнен на ~90%, главный пропуск — VoiceOrchestratorTest.

---

## Этап 0: CI/CD (добавлен по рекомендации ревью)

**Файл:** `.github/workflows/android.yml` — 4 job'а: lint, test, build, release-check.

| Аспект | Статус |
|--------|--------|
| Lint + test + assembleRelease | Есть |
| Gradle cache (`setup-gradle@v4`) | Есть |
| Meta SDK secrets (`META_SDK_USER`/`META_SDK_TOKEN`) | Есть |
| Concurrency + cancel-in-progress | Есть |
| Триггеры: push main, `claude/*`, PR на main | Есть |

### Проблемы CI

| # | Проблема | Критичность |
|---|---------|-------------|
| CI-1 | `assembleRelease` без signing config — job упадёт | High |
| CI-2 | Нет upload артефактов (APK, test reports) | Medium |
| CI-3 | Нет `isShrinkResources = true` в `build.gradle.kts` | Low |

---

## Этап 1: Critical (P0)

### 1.1 ProGuard rules (C3) — ВЫПОЛНЕНО

**Файл:** `app/proguard-rules.pro` — 65 строк правил.

Покрытие:
- Moshi: `@JsonClass`, `@Json`, `@FromJson`/`@ToJson`, adapters
- Room: `@Entity`, `@Dao`, `@Database`, subclasses
- Hilt/Dagger: `dagger.hilt.**`, `javax.inject.**`
- Retrofit: HTTP method annotations, interfaces
- OkHttp: Platform, conscrypt, bouncycastle, openjsse
- Kotlin Coroutines: volatile fields
- Kotlin Metadata
- Gemini SDK
- Domain models: `com.vzor.ai.domain.model.**`

**Замечания:**
- Blanket keep `com.vzor.ai.data.remote.**` избыточен — мешает R8 оптимизировать неиспользуемый код
- Нет правил для `security-crypto` (AndroidX обычно поставляет свои consumer rules, но стоит проверить)
- Нет explicit `-keepattributes EnclosingMethod`

### 1.2 Шифрование (C4) — ПЕРЕСМОТРЕНО, ВЫПОЛНЕНО ЧАСТИЧНО

**Файл:** `data/local/PreferencesManager.kt`

**Что сделано:**
- SQLCipher **отложен** (обоснованно для MVP — нет PII в Room DB)
- EncryptedSharedPreferences для 6 API-ключей: gemini, claude, openai, yandex, glm, tavily
- Шифрование: AES-256-SIV (ключи) + AES-256-GCM (значения), backed by Android Keystore
- Несензитивные настройки (ai_provider, stt/tts_provider, system_prompt, local_ai_host) остаются в DataStore
- TODO-комментарий с критериями для миграции на SQLCipher
- Критерии задокументированы в ADR-SEC-001

**Зависимость:** `androidx.security:security-crypto:1.1.0-alpha06` — alpha-версия, стабильная 1.0.0.

**Потенциальный баг:** API key Flow привязан к `dataStore.data.map { encryptedPrefs.getString() }`. При изменении ключа через `setGeminiApiKey()` запись идёт в EncryptedSharedPreferences, но DataStore не эмитит — Flow не обновится до следующего изменения DataStore. Нужен отдельный trigger или MutableStateFlow.

### 1.3 HTTP logging (M7→P0) — ВЫПОЛНЕНО

**Файл:** `di/AppModule.kt`

```
level = if (BuildConfig.DEBUG) BODY else HEADERS
```

**Замечание:** `HEADERS` всё ещё логирует `Authorization` и `x-api-key`. Рекомендуется `BASIC` или `redactHeader("Authorization")`.

### 1.4 DB migration (M6→P0) — ВЫПОЛНЕНО

**Файл:** `data/local/AppDatabase.kt`

- `fallbackToDestructiveMigration()` удалён
- Добавлена `Migration(1, 2)` с `CREATE TABLE` для `session_log` и `memory_facts`
- Версия БД = 2, `exportSchema = false`
- В AppModule: `.addMigrations(*AppDatabase.MIGRATIONS)`

**Замечание:** `exportSchema = false` — для production лучше `true` с `room.schemaLocation` в KSP args для автоматической валидации миграций.

---

## Этап 2: High (P1)

### 2.1 Unit-тесты (H3) — ЧАСТИЧНО (6 из 7 файлов)

69 тестов в 6 файлах. Стиль: JUnit 4 + MockK, Arrange/Act/Assert.

| Файл | Тестов | Оценка | Главный пропуск |
|------|:------:|--------|----------------|
| IntentClassifierTest | 18 | 8/10 | Тип `UNKNOWN` не тестируется (12-й из 12) |
| BackendRouterTest | 11 | 8/10 | Нет fallback recovery, `latencyBudgetMs` не варьируется |
| SentenceSegmenterTest | 9 | 6/10 | Нет чистого EN текста, нет чередования RU/EN |
| ContextManagerTest | 7 | 6/10 | Нет LRU persistent memory, нет лимита 20 реплик |
| PerceptionCacheTest | 11 | 9/10 | Нет перезаписи ключа |
| SceneComposerTest | 13 | 9/10 | Полноценное покрытие |
| **VoiceOrchestratorTest** | **0** | **0/10** | **Файл отсутствует** |

#### Критичные пропуски в тестах

1. **VoiceOrchestratorTest.kt** — отсутствует. Центральный компонент (FSM с 8 состояниями) без тестов. Должен покрывать: FSM-переходы, невалидные переходы, barge-in, гонки, timeout.

2. **ContextManagerTest — LRU persistent memory** — заявлено в архитектуре (~100 записей с LRU), но тесты проверяют только session token budget (2048 токенов). Нет теста на лимит 20 реплик.

3. **SentenceSegmenterTest — EN-only и чередование языков** — план требовал "RU/EN/mixed". Есть RU и mixed, нет чистого EN и чередования RU↔EN (ключевой сценарий мультиязычного TTS).

4. **IntentClassifierTest — UNKNOWN** — 11 из 12 типов покрыты, `UNKNOWN` пропущен.

#### Зависимости

Добавлены в `build.gradle.kts` / `libs.versions.toml`:
- `junit:4.13.2`
- `mockk:1.13.13`
- `kotlinx-coroutines-test:1.9.0`
- `turbine:1.2.0`

### 2.2 Privacy ADR (H5) — ВЫПОЛНЕНО

**Файл:** `Documents/Review/ADR-SEC-001.md`

| Критерий | Статус |
|----------|--------|
| 152-ФЗ | Есть (раздел 6) |
| Перечень данных (7 категорий) | Есть (раздел 1) |
| Политика хранения (TTL для каждой категории) | Есть |
| DPA для 5 внешних сервисов | Есть (раздел 2) |
| Encryption at-rest | Есть (раздел 3) |
| Encryption in-transit (TLS 1.3, BT 5.3) | Есть (раздел 3) |
| User consent (permissions + onboarding) | Есть (раздел 4) |
| Право на удаление (3 уровня) | Есть (раздел 5) |

**Замечания:**
- Утверждение "Vzor не является оператором ПД" — спорное. Голос, фото, контакты могут квалифицироваться как ПД по 152-ФЗ даже при локальном хранении.
- Голос пользователя (STT) может считаться биометрией — не упомянуто.
- DPA Yandex SpeechKit ("данные не хранятся") — следует проверить актуальные условия.

### 2.3 Scene контракт (H4) — ВЫПОЛНЕНО

**Файл:** `vision/SceneContract.kt`

Kotlin sealed interface (вместо protobuf — по рекомендации ревью):

| Подтип | Поля |
|--------|------|
| `DetectedObject` | label, confidence, bbox |
| `RecognizedText` | text, language, confidence |
| `FaceDetection` | landmarks, expression |
| `SceneDescription` | summary, provider, timestamp |

`SceneAnalysisResult` — data class с convenience-методами (`objects()`, `texts()`, `faces()`, `descriptions()`), TTL (5000ms default), stability score.

Обоснование выбора sealed interface vs protobuf документировано в KDoc.

**Замечание:** `stability` без документации диапазона (0..1? 0..100?).

### 2.4 Телеметрия (M2→P1) — ВЫПОЛНЕНО

**Файл:** `telemetry/TelemetryTracker.kt`

| Метрика | Тип | API |
|---------|-----|-----|
| `sttLatency` | `StateFlow<Long?>` | `recordSttLatency(durationMs)` |
| `ttsFirstAudioMs` | `StateFlow<Long?>` | `recordTtsFirstAudio(durationMs)` |
| `routeReason` | `StateFlow<String?>` | `recordRouteReason(reason)` |
| `vadFalsePositiveRate` | `StateFlow<Float?>` | `recordVadActivation(isFalsePositive)` |

- Thread-safe: `ConcurrentHashMap`, `ConcurrentLinkedDeque`, `AtomicInteger`
- Circular buffer на 100 записей (`MAX_LATENCY_ENTRIES`)
- STT/TTS дублируются в latency buffers для агрегации (скользящее среднее)
- Все метрики включены в `TelemetryReport` через `getReport()`

---

## Сверка с рекомендациями ревью плана

| Рекомендация | Учтена? |
|-------------|---------|
| Убрать Protobuf → Kotlin interface | Да |
| SQLCipher → отложить, EncryptedSP для ключей | Да |
| Добавить CI (GitHub Actions) | Да |
| Fallback-план для DAT SDK (Camera2 API) | Частично — упомянут в report v2, код не написан |
| Переместить ML classifier в Этап 4 | Да |

---

## Список проблем (по приоритету)

### Critical

| # | Проблема | Файл |
|---|---------|------|
| 1 | VoiceOrchestratorTest.kt отсутствует — центральный FSM без тестов | app/src/test/ |
| 2 | Signing config для release отсутствует — CI job упадёт | app/build.gradle.kts |

### High

| # | Проблема | Файл |
|---|---------|------|
| 3 | Баг: Flow API-ключей не реактивен (DataStore trigger, запись в EncryptedSP) | PreferencesManager.kt |
| 4 | SentenceSegmenterTest: нет EN-only и чередования RU↔EN | SentenceSegmenterTest.kt |
| 5 | ContextManagerTest: нет LRU persistent memory, нет лимита 20 реплик | ContextManagerTest.kt |

### Medium

| # | Проблема | Файл |
|---|---------|------|
| 6 | Room schema export: нет `schemaLocation` в KSP args | build.gradle.kts |
| 7 | HTTP logging: HEADERS логирует Authorization | AppModule.kt |
| 8 | security-crypto alpha (1.1.0-alpha06) — для production лучше 1.0.0 | libs.versions.toml |
| 9 | ProGuard: blanket keep `data.remote.**` мешает R8 оптимизации | proguard-rules.pro |
| 10 | IntentClassifierTest: тип UNKNOWN не покрыт | IntentClassifierTest.kt |

### Low

| # | Проблема | Файл |
|---|---------|------|
| 11 | CI: нет upload артефактов (APK, test reports) | android.yml |
| 12 | CI: нет `isShrinkResources = true` | build.gradle.kts |
| 13 | SceneContract: stability без документации диапазона | SceneContract.kt |
| 14 | ADR-SEC-001: биометрия голоса не упомянута | ADR-SEC-001.md |

---

## Файлы, затронутые в этой итерации

### Созданные (10)

| Файл | Назначение |
|------|-----------|
| `.github/workflows/android.yml` | CI: lint, test, build, release-check |
| `Documents/Review/ADR-SEC-001.md` | Privacy ADR (152-ФЗ, DPA, шифрование) |
| `Documents/implementation_report_v2.md` | Отчёт о реализации Этапов 0-2 |
| `vision/SceneContract.kt` | Kotlin sealed interface для vision pipeline |
| `test/.../IntentClassifierTest.kt` | 18 тестов классификатора интентов |
| `test/.../BackendRouterTest.kt` | 11 тестов маршрутизатора бэкендов |
| `test/.../SentenceSegmenterTest.kt` | 9 тестов сегментации предложений |
| `test/.../ContextManagerTest.kt` | 7 тестов менеджера контекста |
| `test/.../PerceptionCacheTest.kt` | 11 тестов кеша восприятия |
| `test/.../SceneComposerTest.kt` | 13 тестов композитора сцен |

### Модифицированные (7)

| Файл | Изменения |
|------|-----------|
| `app/proguard-rules.pro` | +63 строки keep rules |
| `app/build.gradle.kts` | +security-crypto, +test dependencies |
| `gradle/libs.versions.toml` | +6 новых зависимостей |
| `data/local/AppDatabase.kt` | Migration(1,2), убран destructive fallback |
| `data/local/PreferencesManager.kt` | EncryptedSharedPreferences для API-ключей |
| `di/AppModule.kt` | HTTP logging conditional, новые DI bindings |
| `telemetry/TelemetryTracker.kt` | +4 специфичные метрики |

---

## Влияние на оценку compliance_report v1

| Компонент | v1 | v2 | Изменение |
|-----------|:--:|:--:|-----------|
| ProGuard/Build | N/A | 9/10 | Новый — закрыт |
| Memory (шифрование) | 7/10 | 8/10 | EncryptedSP для ключей |
| Телеметрия | 6/10 | 9/10 | +3, все 4 метрики |
| Vision (контракт) | 4/10 | 5/10 | +1, sealed interface |
| Тесты | N/A | 7/10 | Новый — 69 тестов, нет VoiceOrchestrator |
| Privacy/Docs | N/A | 8/10 | Новый — ADR-SEC-001 |
| **Средняя** | **7.4** | **8.2** | **+0.8** |

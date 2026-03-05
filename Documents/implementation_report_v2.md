# Vzor AI — Отчёт о реализации (Этапы 0–2, v2)

**Дата:** 2026-03-05
**Ветка:** `claude/android-ai-assistant-meta-Sm71O`
**Базовый коммит:** 0edc165 (main)

---

## Контекст

Реализация плана исправлений v2, составленного по итогам compliance report (оценка 7.4/10) и ревью плана (оценка 7.5/10). Ключевые изменения в v2 по сравнению с v1:

- SQLCipher убран из P0 → отложен, заменён на EncryptedSharedPreferences
- Protobuf убран → Kotlin sealed interface
- Добавлен CI/CD (GitHub Actions)
- DAT SDK получил fallback-план
- ML IntentClassifier перемещён в Этап 4
- Добавлен бюджет APK size

---

## Выполненные этапы

### Этап 0: CI/CD Foundation

| Задача | Статус | Файл |
|--------|--------|------|
| GitHub Actions workflow | Выполнено | `.github/workflows/android.yml` |

**Что сделано:**
- 4 jobs: lint, test, build (debug), release-check (ProGuard)
- Triggers: push to main/claude/*, PR to main
- Gradle caching через `gradle/actions/setup-gradle@v4`
- Secrets для Meta DAT SDK maven repo (подготовлены)
- Concurrency: cancel-in-progress для одинаковых workflow

### Этап 1: Критические исправления (P0)

| Задача | Статус | Файл(ы) |
|--------|--------|---------|
| 1.1 ProGuard rules | Выполнено | `app/proguard-rules.pro` |
| 1.2 HTTP logging level | Выполнено | `di/AppModule.kt` |
| 1.3 Proper DB migration | Выполнено | `data/local/AppDatabase.kt`, `di/AppModule.kt` |
| 1.4 EncryptedSharedPreferences | Выполнено | `data/local/PreferencesManager.kt`, `gradle/libs.versions.toml`, `app/build.gradle.kts` |

**Детали:**

**1.1 ProGuard:**
- Добавлены keep rules для: Moshi (@JsonClass, @Json, adapters), Room (@Entity, @Dao, @Database), Hilt/Dagger, Retrofit (HTTP method annotations), OkHttp (Platform, internal), Kotlin Coroutines, Kotlin Reflection, Google Generative AI, Vzor domain models

**1.2 HTTP logging:**
- `HttpLoggingInterceptor.Level.BODY` → `if (BuildConfig.DEBUG) BODY else HEADERS`
- Добавлен import `BuildConfig`

**1.3 DB migration:**
- Удалён `fallbackToDestructiveMigration()`
- Добавлена `Migration(1, 2)` с CREATE TABLE для `session_log` и `memory_facts`
- `AppModule.DatabaseModule` использует `addMigrations(*AppDatabase.MIGRATIONS)`

**1.4 EncryptedSharedPreferences:**
- API keys (6 штук: gemini, claude, openai, yandex, glm, tavily) перенесены из DataStore в EncryptedSharedPreferences
- Шифрование: AES-256-SIV (key) + AES-256-GCM (value), backed by Android Keystore
- Настройки (ai_provider, stt/tts_provider, system_prompt, local_ai_host) остаются в DataStore (не чувствительные)
- Добавлена зависимость `androidx.security:security-crypto:1.1.0-alpha06`
- TODO-комментарий с критериями для миграции на SQLCipher

### Этап 2: Тесты + Документация (P1)

| Задача | Статус | Файл(ы) |
|--------|--------|---------|
| 2.1 Unit-тесты | Выполнено | 6 test-файлов |
| 2.2 Privacy ADR | Выполнено | `Documents/Review/ADR-SEC-001.md` |
| 2.3 SceneData контракт | Выполнено | `vision/SceneContract.kt` |
| 2.4 Метрики телеметрии | Выполнено | `telemetry/TelemetryTracker.kt` |

**2.1 Unit-тесты (6 файлов, JUnit 4 + MockK):**

| Тест | Покрытие |
|------|----------|
| `IntentClassifierTest` | 11 типов интентов, slot extraction, edge cases (пустая строка, case insensitivity) |
| `BackendRouterTest` | 12 сценариев: offline, low battery (boundary 19/20), wifi±X2, queue threshold (799/800), LTE |
| `SentenceSegmenterTest` | Flush rules (punctuation, timeout, stream end), reset, mixed RU+EN, multiple sentences |
| `ContextManagerTest` | Token estimation, session CRUD, eviction при превышении бюджета, persistent facts fallback |
| `PerceptionCacheTest` | Put/get, TTL expiry, invalidation, hit rate tracking, size with expired entries |
| `SceneComposerTest` | Empty scene, VLM preference, auto-summary, OCR parsing, stability scoring, TTL rules, unique IDs |

Добавлены test-зависимости в `libs.versions.toml` и `build.gradle.kts`:
- `junit:4.13.2`
- `io.mockk:mockk:1.13.13`
- `kotlinx-coroutines-test:1.9.0`
- `app.cash.turbine:turbine:1.2.0`

**2.2 Privacy ADR (ADR-SEC-001):**
- 7 категорий данных с указанием хранения, TTL и шифрования
- DPA для 5 внешних сервисов
- 3 уровня шифрования (EncryptedSP, Android sandbox, TLS 1.3)
- Политика согласия (permissions + onboarding)
- Право на удаление (3 уровня: история, память, всё)
- Критерии для внедрения SQLCipher

**2.3 SceneData контракт:**
- `SceneElement` sealed interface с 4 подтипами: DetectedObject, RecognizedText, FaceDetection, SceneDescription
- `SceneAnalysisResult` data class с convenience-методами (objects(), texts(), faces(), descriptions())
- Документировано обоснование выбора sealed interface вместо Protobuf

**2.4 Метрики телеметрии (4 новые):**
- `sttLatency` (StateFlow<Long?>) — от записи до транскрипта
- `ttsFirstAudioMs` (StateFlow<Long?>) — от текста до первого аудио-чанка
- `routeReason` (StateFlow<String?>) — "wifi_local", "lte_cloud", "offline_device", "fallback_{reason}"
- `vadFalsePositiveRate` (StateFlow<Float?>) — false activations / total activations
- Все метрики включены в TelemetryReport
- STT/TTS метрики также записываются в latency буферы для агрегации

---

## Полный список изменённых/созданных файлов

### Созданные (новые):
```
.github/workflows/android.yml                                    — CI/CD
Documents/Review/ADR-SEC-001.md                                  — Privacy ADR
Documents/implementation_report_v2.md                            — этот отчёт
app/src/main/java/com/vzor/ai/vision/SceneContract.kt           — sealed interface контракт
app/src/test/java/com/vzor/ai/orchestrator/IntentClassifierTest.kt
app/src/test/java/com/vzor/ai/orchestrator/BackendRouterTest.kt
app/src/test/java/com/vzor/ai/tts/SentenceSegmenterTest.kt
app/src/test/java/com/vzor/ai/context/ContextManagerTest.kt
app/src/test/java/com/vzor/ai/vision/PerceptionCacheTest.kt
app/src/test/java/com/vzor/ai/vision/SceneComposerTest.kt
```

### Модифицированные:
```
app/proguard-rules.pro                                           — полные keep rules
app/build.gradle.kts                                             — security-crypto, test deps
gradle/libs.versions.toml                                        — security-crypto, junit, mockk, turbine
app/src/main/java/com/vzor/ai/di/AppModule.kt                   — BuildConfig logging, proper migration
app/src/main/java/com/vzor/ai/data/local/AppDatabase.kt         — Migration(1,2), убран destructive
app/src/main/java/com/vzor/ai/data/local/PreferencesManager.kt  — EncryptedSharedPreferences
app/src/main/java/com/vzor/ai/telemetry/TelemetryTracker.kt     — 4 новые метрики
```

---

## Что осталось (Этапы 3–6)

| Этап | Приоритет | Статус | Блокеры |
|------|-----------|--------|---------|
| 3.1 DAT SDK / Camera2 fallback | P1-P2 | Не начат | Доступ к Meta DAT SDK |
| 3.2 Porcupine Wake Word | P1 | Не начат | Picovoice Console для keyword |
| 3.3 MediaPipe + ML Kit OCR | P1 | Не начат | — |
| 4.1 Offline Whisper | P2 | Не начат | — |
| 4.2 Yandex gRPC STT | P2 | Не начат | — |
| 4.3 Mixed-language TTS | P2 | Не начат | — |
| 4.4 ML IntentClassifier | P2 | Не начат | Ollama с Qwen3.5-0.8B |
| 5.1–5.5 UX Polish | P2-P3 | Не начат | — |
| 6.1–6.3 Документация | P3 | Частично (ADR done) | — |

---

## APK Size Impact

| Изменение | Прирост |
|-----------|---------|
| EncryptedSharedPreferences (security-crypto) | ~50 KB |
| Test dependencies (testImplementation only) | 0 KB (не в APK) |
| ProGuard rules | 0 KB (build-time only) |
| SceneContract.kt | ~2 KB |
| **Итого** | **~50 KB** |

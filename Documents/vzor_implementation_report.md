# Vzor AI — Отчёт о реализации

**Дата:** 2026-03-05
**Ветка:** `claude/android-ai-assistant-meta-Sm71O`
**Автор:** Claude AI (автоматическая генерация)

---

## Общие итоги

| Метрика | Значение |
|---------|----------|
| Kotlin-файлов | 91 |
| Строк Kotlin-кода | 11 325 |
| Коммитов | 5 |
| Новых/изменённых файлов | 90 |
| Строк добавлено (всего) | ~16 400 |

### Коммиты

| Хеш | Описание |
|------|----------|
| `657990f` | docs: merge architecture documentation from main branch |
| `a41d188` | feat: Sprint 1 — VoiceOrchestrator FSM, STT/TTS pipeline, glasses integration |
| `8fb6ba9` | feat: Sprint 2 — Vision pipeline, Context Manager, Memory, UI screens |
| `710975d` | feat: Sprint 3 — Android Actions, Translation, Telemetry |
| `f9c70d7` | fix: finalize HomeScreen and NavGraph from background agent updates |

---

## Sprint 1 — Ядро (40 файлов, +4197 строк)

### VoiceOrchestrator FSM (`orchestrator/`)

| Файл | Строк | Описание |
|------|-------|----------|
| `VoiceOrchestrator.kt` | 245 | Конечный автомат с 8 состояниями: IDLE → LISTENING → PROCESSING → GENERATING → RESPONDING → CONFIRMING → SUSPENDED → ERROR. Barge-in, hard reset, listener pattern. |
| `IntentClassifier.kt` | 146 | Rule-based классификатор по ключевым словам. 12 типов интентов (CALL_CONTACT, VISION_QUERY, NAVIGATE, PLAY_MUSIC и др.). |
| `BackendRouter.kt` | 75 | 3-уровневый алгоритм маршрутизации: Wi-Fi → Local AI (Ollama), LTE → Cloud, Offline → On-device. Учитывает батарею, доступность X2, время ожидания в очереди. |
| `ConversationSession.kt` | 28 | Структура активной сессии в RAM с сообщениями, routing context, tool calls. |

### Domain модели (`domain/model/`)

| Файл | Описание |
|------|----------|
| `VoiceState.kt` | Enum 8 состояний FSM |
| `VoiceEvent.kt` | Sealed class с 14 типами событий (WAKE_WORD_DETECTED, SPEECH_END, BARGE_IN и др.) |
| `RoutingContext.kt` | NetworkType enum, RoutingDecision enum, контекст маршрутизации |
| `VzorIntent.kt` | 12 типов интентов + слоты + confidence + флаги vision/confirmation |
| `SceneData.kt` | Scene JSON v2: DetectedObject, BoundingBox, OCR текст, stability score, TTL |
| `NoiseProfile.kt` | QUIET/INDOOR/OUTDOOR/LOUD с порогами VAD (0.3-0.85), STT confidence, TTS volume boost |
| `AiProvider.kt` | GEMINI, CLAUDE, OPENAI, GLM_5, LOCAL_QWEN, OFFLINE_QWEN + CLOUD_PROVIDERS list |

### Glasses + Audio (`glasses/`, `speech/`, `service/`)

| Файл | Строк | Описание |
|------|-------|----------|
| `GlassesManager.kt` | 566 | BT HFP lifecycle: connect/disconnect, SCO audio, BroadcastReceiver для состояния, SharedFlow для аудио и камеры |
| `AudioStreamHandler.kt` | 250 | PCM 16kHz mono capture, BT mic → VOICE_COMMUNICATION, fallback на MIC, RMS dB расчёт |
| `WakeWordService.kt` | 382 | Энергетический VAD: длительность 300-1200мс, 3-фазный профиль энергии, zero-crossing для фрикативного "В" |
| `NoiseProfileDetector.kt` | 76 | Скользящее окно RMS (10 фреймов) → dB SPL → NoiseProfile |
| `VzorAssistantService.kt` | 375 | Foreground service (connectedDevice), persistent notification, наблюдение за glasses state, audio pipeline management |

### STT + TTS pipeline (`speech/`, `tts/`)

| Файл | Строк | Описание |
|------|-------|----------|
| `SttService.kt` | 20 | Interface: startListening() → Flow\<SttResult\>, stopListening(), isListening |
| `WhisperSttService.kt` | 114 | Batch STT: запись → WAV → OpenAI Whisper API. Для Wi-Fi. |
| `YandexSttService.kt` | 184 | Streaming STT: chunked audio → Yandex REST API → partial/final results. Для LTE. |
| `TtsManager.kt` | 350 | Streaming pipeline: token buffering → SentenceSegmenter → language detection (Cyrillic ratio) → Yandex(RU)/Google(EN) → AudioTrack |
| `YandexTtsProvider.kt` | 149 | POST tts.api.cloud.yandex.net, голос "alena", PCM 48kHz |
| `GoogleTtsProvider.kt` | 182 | Android TextToSpeech, speakDirect() с suspend completion |
| `SentenceSegmenter.kt` | 69 | Разбивка по пунктуации (min 5 слов) или таймаут 200мс (min 3 слова) |
| `PhraseCacheManager.kt` | 150 | Pre-synthesis 6 системных фраз (UNDERSTOOD, EXECUTING, CANT_HEAR и др.) на RU + EN |

### Новые AI-провайдеры (`data/remote/`)

| Файл | Строк | Описание |
|------|-------|----------|
| `GlmApiService.kt` | 19 | Retrofit interface для Zhipu API (glm-5 текст, glm-4v vision). OpenAI-совместимый формат. |
| `OllamaService.kt` | 132 | HTTP клиент для Ollama: sync/streaming chat, health check, runtime host update |
| `TavilySearchService.kt` | 39 | Retrofit interface для веб-поиска (search_depth, max_results, include_answer) |

### Конфигурация и интеграция

| Файл | Описание |
|------|----------|
| `EndpointRegistry.kt` (185 строк) | Парсер endpoints.json из assets, runtime override хоста через PreferencesManager |
| `endpoints.json` | Local AI (host/port/paths), Cloud (Claude/OpenAI/Gemini/Tavily URLs), Offline (model paths) |
| `system_qwen.txt` | Промпт для Qwen: краткий (≤500 токенов), {{scene_block}}, {{memory_block}}, {{tools_block}} |
| `system_claude.txt` | Промпт для Claude: + {{history_block}}, те же правила |
| `AppModule.kt` | DI: GlmApiService, OllamaService, TavilySearchService, SessionLogDao, MemoryFactDao |
| `AiRepositoryImpl.kt` | +67 строк: dispatch для GLM_5, LOCAL_QWEN, OFFLINE_QWEN |
| `VisionRepositoryImpl.kt` | +35 строк: GLM-4V vision, fallback для LOCAL/OFFLINE |
| `PreferencesManager.kt` | +27 строк: glmApiKey, tavilyApiKey, localAiHostOverride |
| `SettingsScreen.kt` | +28 строк: GLM-5 radio, Tavily API key, Local AI host input |
| `SettingsViewModel.kt` | +33 строки: glmApiKey, localAiHost, tavilyApiKey state + setters |

---

## Sprint 2 — Vision + Memory + UI (26 файлов, +3112 строк)

### Context Manager (`context/`)

| Файл | Строк | Описание |
|------|-------|----------|
| `ContextManager.kt` | 96 | Session Memory (ConcurrentLinkedDeque, ~2048 токенов sliding window) + Persistent Memory queries. Token estimate: text.length / 4. |
| `MemoryExtractor.kt` | 128 | Отправляет разговор в LLM с промптом "Extract key facts...". Парсит JSON array, толерантен к markdown wrapping. Категории: PREFERENCE, PERSONAL, LOCATION, CONTACT, HABIT, OTHER. |
| `PromptBuilder.kt` | 172 | Загружает шаблоны из assets/prompts/, заменяет {{scene_block}}, {{memory_block}}, {{tools_block}}, {{history_block}}. |

### Persistent Memory (`data/local/`, `domain/`)

| Файл | Описание |
|------|----------|
| `MemoryFactEntity.kt` | Room entity: fact, category, importance (1-5), createdAt, lastAccessedAt, accessCount |
| `MemoryFactDao.kt` | insert, searchByKeyword (LIKE), getTopFacts, updateAccessTime, deleteOlderThan, getCount |
| `SessionLogEntity.kt` | Room entity: sessionId, startedAt, endedAt, messageCount, provider, routingMode, summary |
| `SessionLogDao.kt` | insert, getAll (DESC), getById, update, deleteOlderThan |
| `MemoryFact.kt` | Domain model + MemoryCategory enum (6 категорий) |
| `MemoryRepository.kt` | Interface: saveFact, searchFacts, getTopFacts, deleteFact, cleanup(maxFacts=100) |
| `MemoryRepositoryImpl.kt` | Room-based, access-time tracking, LRU cleanup |
| `AppDatabase.kt` | v2: +session_log, +memory_facts таблицы |

### Vision Pipeline (`vision/`)

| Файл | Строк | Описание |
|------|-------|----------|
| `FrameSampler.kt` | 110 | 5 режимов fps (0/1/5/10/15). AtomicLong для thread-safe timestamp. Auto-LOW при battery < 20%. |
| `PerceptionCache.kt` | 107 | ConcurrentHashMap + TTL (objects=30s, text=60s, description=10s). Atomic hit/miss счётчики. |
| `VisionRouter.kt` | 122 | Cache-hit → return cached. Cache-miss → visionRepository.analyzeImage() → parse → cache. |
| `EventBuilder.kt` | 137 | Сравнение SceneData: TEXT_APPEARED, TEXT_CHANGED, SCENE_CHANGED, NEW_OBJECT, OBJECT_REMOVED |
| `SceneComposer.kt` | 134 | Stability score: 60% avg confidence + 40% reliable ratio. TTL: 60s (text), 30s (complex), 10s (simple). |
| `CameraStreamHandler.kt` | 110 | SharedFlow\<ByteArray\> (replay=1, DROP_OLDEST). Coroutine на Dispatchers.IO с frameSampler polling. |

### UI экраны (`ui/`)

| Файл | Строк | Описание |
|------|-------|----------|
| `HomeScreen.kt` | 501 | Dashboard: glasses card (connect/disconnect, battery), status row (routing badge + voice state), quick actions (Listen, Photo, Settings, Translate) |
| `HistoryScreen.kt` | 400 | LazyColumn с карточками сессий: дата, message count, provider, duration, summary. SwipeToDismissBox для удаления. |
| `LogsScreen.kt` | 224 | Developer mode: latency (10 последних), cache hit rate, noise profile, voice state, routing log. Auto-refresh каждые 2с. |
| `VoiceStateIndicator.kt` | 228 | Анимации: IDLE (синяя точка), LISTENING (пульсирующий круг), PROCESSING (спиннер), GENERATING (3 точки), RESPONDING (5 баров), ERROR (красная точка) |
| `ConfirmDialog.kt` | 151 | ModalBottomSheet: иконка, заголовок, описание, Confirm/Cancel, 15с auto-dismiss с countdown |
| `RoutingBadge.kt` | 76 | AssistChip: LOCAL (зелёный, Memory), CLOUD (синий, Cloud), OFFLINE (оранжевый, CloudOff) |
| `GlassesBatteryIndicator.kt` | 69 | Icon + %: BatteryFull (>75%, зелёный), Battery3Bar (25-75%, жёлтый), Battery0Bar (<25%, красный) |
| `NavGraph.kt` | +107 строк | HOME (стартовый), CHAT, HISTORY, TRANSLATION + NavigationBar (3 таба) |

---

## Sprint 3 — Actions + Translation + Telemetry (13 файлов, +1981 строк)

### Android Actions (`actions/`)

| Файл | Строк | Описание |
|------|-------|----------|
| `ActionExecutor.kt` | 141 | Центральный диспетчер: IntentType → Action class. Обрабатывает 6 типов + unsupported fallback. |
| `CallAction.kt` | 127 | ContactsContract lookup (fuzzy LIKE), ACTION_CALL intent. requiresConfirmation=true. |
| `MessageAction.kt` | 121 | WhatsApp (api.whatsapp.com deep link), Telegram (tg://msg), SMS (ACTION_SENDTO). Auto-detect по packageManager. |
| `MusicAction.kt` | 134 | MEDIA_PLAY_FROM_SEARCH (Spotify/YT Music/Яндекс Музыка/Deezer fallback). AudioManager.dispatchMediaKeyEvent для pause/next/prev. |
| `NavigationAction.kt` | 135 | Yandex Maps (yandexmaps://) → Google Maps (geo:) → browser fallback. requiresConfirmation=true. |
| `ReminderAction.kt` | 152 | AlarmManager.setExactAndAllowWhileIdle + PendingIntent → ReminderReceiver. canScheduleExactAlarms() check (API 31+). NotificationChannel "vzor_reminders". |
| `ReminderReceiver.kt` | 30 | BroadcastReceiver → high-priority notification |

### Перевод (`translation/`, `ui/translation/`)

| Файл | Строк | Описание |
|------|-------|----------|
| `TranslationManager.kt` | 264 | 3 режима: LISTEN (иностранный → родной), SPEAK (родной → иностранный), BIDIRECTIONAL (auto-detect по Cyrillic/Latin ratio). STT → AI translate → TTS. |
| `TranslationSession.kt` | 46 | TranslationMode enum, TranslationState, TranslationResult (source/translated text, latency). |
| `TranslationScreen.kt` | 631 | Compose UI: language chips (RU↔EN) со swap, 3 toggle buttons для режимов, center card (source + перевод + latency), pulsing status indicator, PTT button с анимацией. |

### Телеметрия (`telemetry/`)

| Файл | Строк | Описание |
|------|-------|----------|
| `TelemetryTracker.kt` | 181 | Circular buffers (100 entries): latency per operation, cache hit/miss, fallback count, barge-in count, errors. StateFlow-based reactive reporting. getReport() snapshot. |

### Permissions (AndroidManifest.xml)

Добавлены: `CALL_PHONE`, `READ_CONTACTS`, `SEND_SMS`, `SET_ALARM`, `SCHEDULE_EXACT_ALARM`, `BLUETOOTH_SCAN`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`.

---

## Архитектура (итоговая структура пакетов)

```
com.vzor.ai/
├── actions/          7 файлов — Android-интеграции (звонки, сообщения, музыка, навигация, напоминания)
├── context/          3 файла  — Context Manager + Memory Extractor + Prompt Builder
├── data/local/       8 файлов — Room DB v2 + DataStore + EndpointRegistry
├── data/remote/      6 файлов — API-клиенты (Gemini/Claude/OpenAI/GLM-5/Ollama/Tavily)
├── data/repository/  4 файла  — Repository implementations
├── di/               1 файл   — Hilt DI modules (Network + Database + Repository)
├── domain/model/     10 файлов — Domain модели
├── domain/repository/ 4 файла  — Repository interfaces
├── glasses/          3 файла  — BT HFP audio + camera frames
├── orchestrator/     4 файла  — VoiceOrchestrator FSM + IntentClassifier + BackendRouter
├── service/          1 файл   — Foreground service
├── speech/           6 файлов — STT (Whisper/Yandex) + Wake Word + Noise Detection
├── telemetry/        1 файл   — Performance metrics
├── translation/      2 файла  — Real-time translation (3 modes)
├── tts/              6 файлов — TTS streaming pipeline (Yandex RU / Google EN)
├── ui/               15+ файлов — Compose UI
│   ├── home/         — Dashboard
│   ├── chat/         — AI чат
│   ├── settings/     — Настройки провайдеров и ключей
│   ├── history/      — История сессий
│   ├── logs/         — Developer телеметрия
│   ├── translation/  — Экран перевода
│   ├── components/   — Reusable компоненты (VoiceStateIndicator, ConfirmDialog, RoutingBadge, GlassesBatteryIndicator)
│   ├── navigation/   — NavGraph с bottom navigation
│   └── theme/        — Material3 тема
└── vision/           6 файлов — FrameSampler + PerceptionCache + VisionRouter + EventBuilder + SceneComposer
```

---

## Как это было сделано

### Метод разработки

Каждый спринт разделён на 2-3 параллельных потока, выполняемых одновременно субагентами:

| Sprint | Поток A | Поток B | Поток C |
|--------|---------|---------|---------|
| 1 | Orchestrator + FSM (12 файлов) | Glasses + Audio (6 файлов) | STT + TTS pipeline (10 файлов) |
| 2 | Context + Memory + DB (9 файлов) | Vision Pipeline (6 файлов) | UI screens + Nav (8 файлов) |
| 3 | Android Actions (7 файлов) | Translation + Telemetry (4 файла) | — |

После завершения субагентов — ручная интеграция:
1. Удаление дубликатов (`speech/AudioStreamHandler.kt`)
2. Создание недостающих файлов (`NoiseProfileDetector.kt`)
3. Обновление DI (`AppModule.kt`)
4. Обновление репозиториев (`AiRepositoryImpl`, `VisionRepositoryImpl`)
5. Обновление UI (`SettingsScreen`, `SettingsViewModel`)

### Ключевые архитектурные решения

1. **VoiceOrchestrator как центральный FSM** — все голосовые события проходят через единый конечный автомат, что предотвращает race conditions и упрощает отладку.

2. **3-уровневый AI routing** — BackendRouter автоматически выбирает оптимальный бэкенд (Local → Cloud → Offline) на основе сети, батареи и доступности X2.

3. **Streaming TTS pipeline** — текст от LLM разбивается SentenceSegmenter на фразы и синтезируется параллельно с генерацией, минимизируя latency.

4. **PerceptionCache с TTL** — vision-анализ кэшируется с разными TTL по типу данных, снижая нагрузку на API и улучшая отзывчивость.

5. **OpenAI-совместимый GLM-5 API** — Zhipu GLM-5 переиспользует существующие DTOs от OpenAI, минимизируя дублирование кода.

6. **Room DB v2 с persistent memory** — факты о пользователе сохраняются между сессиями с LRU-cleanup (max 100 фактов).

---

## Следующие шаги

1. **Gradle build** — проверить компиляцию на реальном Android SDK
2. **Meta DAT SDK** — интегрировать реальный SDK в GlassesManager и CameraStreamHandler
3. **Porcupine SDK** — заменить энергетический VAD на настоящий wake word detection
4. **SQLCipher** — шифрование persistent memory
5. **Тестирование** — unit tests для IntentClassifier, BackendRouter, SentenceSegmenter
6. **ProGuard правила** — добавить keep rules для Moshi, Room entities

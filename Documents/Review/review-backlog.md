# Review Backlog — отложенные замечания

Замечания из code review, не исправленные в текущих стейджах. Для будущих итераций.

---

## Закрытые замечания

| Stage | Severity | Issue | Closed in |
|-------|----------|-------|-----------|
| 10 | High | Миграция незашифрованной БД → SQLCipher | Stage 15 |
| 10 | High | sendMessage race при отсутствии conversation | Stage 15 |
| 12 | Medium | cleanup() не атомарен | Stage 15 |
| 12 | Low | Дублирование тестов cleanup | Stage 15 |
| 13 | High | Tool use цикл v2 не реализован | Stage 14 |
| 13 | High | Clean Architecture нарушение в domain interface | Stage 15 |
| 13 | Medium | parseToolArguments теряет вложенные типы | Stage 16 |
| 13 | Medium | playMusic не валидирует параметр action | Stage 15 |
| 13 | Medium | Дублирование маппинга messages в Claude формат | Stage 15 |
| 13 | Low | buildClaudeTools() при каждом сообщении | Stage 16 |
| 15-17 | High | LiveCommentaryService while(isActive) проверял объект вместо .value | Review fix |
| 15-17 | High | ChatViewModel indexOfLast мог перезаписать чужое сообщение | Review fix |
| 15-17 | High | MemoryRepositoryImplTest мёртвые тесты cleanup | Review fix |
| 15-17 | Medium | OpenAiStreamingClient проглоченное исключение parseJsonArgs | Review fix |
| 20 | High | YandexSttService REST fallback результаты терялись | Review fix |
| 20 | High | YandexSttService activeWebSocket race condition | Review fix |
| 20 | High | YandexSttService MAX_DURATION не прерывал collect | Review fix |
| 20 | Medium | BackendRouter hardcoded memory limit 86400 MB | Review fix |
| 21-22 | High | Prompt injection — userQuery без sanitization в промптах | Review fix |
| 21-22 | High | ContactPreferenceManager проглоченные исключения | Review fix |
| 21-22 | Medium | ActionExecutor NotFound вызывал звонок с невалидным контактом | Review fix |
| 15-17 | Medium | streamToolContinuation привязан к Claude API | Stage 23 |
| 15-17 | Medium | Двойной emitAccumulatedToolCalls в OpenAI клиенте | Stage 23 |
| 21-22 | Medium | VisionRouter двойное обогащение промпта | Stage 23 |
| 20 | Low | OkHttpClient в YandexSttService не инжектируется | Stage 23 |
| 20 | Low | Тройное декодирование Bitmap в MediaPipeVisionProcessor | Stage 23 |
| 21-22 | Low | IntentClassifier Regex создаётся при каждом вызове | Stage 23 |
| 15-17 | Low | ClaudeStreamingClient неидиоматичный flow builder | Stage 26 |
| 23-26 | High | SttServiceRouter runBlocking → ANR на Main thread | Review fix |
| 23-26 | High | BatteryMonitor registerReceiver без RECEIVER_NOT_EXPORTED (Android 14+) | Review fix |
| 23-26 | High | GlassesNotificationManager утечка CoroutineScope | Review fix |
| 23-26 | High | OfflineSttService close()/awaitClose гонка | Review fix |
| 23-26 | Medium | AcousticEchoCanceller thread safety (@Volatile) | Review fix |

---

## Открытые замечания

### [Medium] Deprecated MasterKeys API
- **Stage:** 10
- **File:** `di/AppModule.kt`
- **Problem:** `MasterKeys.getOrCreate()` deprecated, замена `MasterKey.Builder` в alpha
- **Status:** Ожидает стабильного релиза security-crypto 1.1.0

### [Medium] PII в disambiguation message (ContactPreferenceManager)
- **Stage:** 21-22
- **File:** `actions/ContactPreferenceManager.kt:103`
- **Problem:** Номера телефонов в тексте попадают в UI/логи
- **Status:** By design для TTS, но проверить что не логируется

### [Medium] SpeakerDiarizer mutable state без синхронизации
- **Stage:** 23-26
- **File:** `speech/SpeakerDiarizer.kt:69-72`
- **Problem:** segments, lastSpeechEndMs, currentSegmentStartMs, isInSpeech — без Mutex/synchronized
- **Fix:** Добавить Mutex для защиты mutable state

### [Medium] PhotoCaptureAction imageData теряется
- **Stage:** 23-26
- **File:** `actions/PhotoCaptureAction.kt:42-47`
- **Problem:** Документация обещает imageData в ActionResult, но поле не заполняется
- **Fix:** Добавить imageData = photoBytes или обновить документацию

### [Medium] TranslationManager CoroutineScope утечка при повторном start
- **Stage:** 23-26
- **File:** `translation/TranslationManager.kt:88`
- **Problem:** Новый scope создаётся при каждом startTranslation без гарантии отмены предыдущего
- **Fix:** Хрупко, хотя stopTranslation вызывается — добавить проверку

### [Low] ToolRegistryTest тавтология
- **Stage:** 12
- **File:** `test/.../ToolRegistryTest.kt`
- **Problem:** Тест создаёт локальный список и проверяет его
- **Fix:** Инстанцировать ToolRegistry с mock-зависимостями

### [Low] Контакты загружаются заново при каждом resolveContact
- **Stage:** 21-22
- **File:** `actions/ContactPreferenceManager.kt:145`
- **Fix:** Кеш с TTL ~30 сек

### [Low] OfflineSttService WAV файлы не очищаются при crash
- **Stage:** 23-26
- **File:** `speech/OfflineSttService.kt:129`
- **Fix:** Очистка старых offline_stt_*.wav при инициализации

### [Low] ToolResult hashCode не учитывает imageData
- **Stage:** 23-26
- **File:** `orchestrator/ToolResult.kt`
- **Fix:** Включить imageData?.contentHashCode()

---

## Сводка

| Severity | Open | Closed |
|----------|:----:|:------:|
| High | 0 | 14 |
| Medium | 5 | 10 |
| Low | 4 | 8 |
| **Total** | **9** | **32** |

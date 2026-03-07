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

---

## Открытые замечания

### [Medium] Deprecated MasterKeys API
- **Stage:** 10
- **File:** `di/AppModule.kt`
- **Problem:** `MasterKeys.getOrCreate()` deprecated, замена `MasterKey.Builder` в alpha
- **Status:** Ожидает стабильного релиза security-crypto 1.1.0

### [Medium] streamToolContinuation привязан к Claude API
- **Stage:** 15-17
- **File:** `data/repository/AiRepositoryImpl.kt:181-222`
- **Problem:** `streamToolContinuation()` всегда использует Claude API, игнорируя текущий провайдер
- **Fix:** Добавить ветвление по провайдеру или документировать ограничение

### [Medium] Двойной вызов emitAccumulatedToolCalls в OpenAI клиенте
- **Stage:** 15-17
- **File:** `data/remote/OpenAiStreamingClient.kt:121-150`
- **Problem:** При finish_reason="tool_calls" + [DONE] — два вызова (безопасно, maps очищены)
- **Fix:** Пропускать второй вызов если maps пусты

### [Medium] VisionRouter двойное обогащение промпта
- **Stage:** 21-22
- **File:** `vision/VisionRouter.kt:54-64`
- **Problem:** auto-enrichment может дублировать промпт при вызове через ToolRegistry
- **Fix:** Добавить флаг `skipEnrichment` или убрать auto-enrichment

### [Medium] PII в disambiguation message (ContactPreferenceManager)
- **Stage:** 21-22
- **File:** `actions/ContactPreferenceManager.kt:103`
- **Problem:** Номера телефонов в тексте попадают в UI/логи
- **Status:** By design для TTS, но проверить что не логируется

### [Low] ToolRegistryTest тавтология
- **Stage:** 12
- **File:** `test/.../ToolRegistryTest.kt`
- **Problem:** Тест создаёт локальный список и проверяет его
- **Fix:** Инстанцировать ToolRegistry с mock-зависимостями

### [Low] ClaudeStreamingClient неидиоматичный flow builder
- **Stage:** 15-17
- **File:** `data/remote/ClaudeStreamingClient.kt:39-47`
- **Fix:** Заменить на `filterIsInstance<StreamChunk.Text>().map { it.content }`

### [Low] OkHttpClient в YandexSttService не инжектируется
- **Stage:** 20
- **File:** `speech/YandexSttService.kt:76-80`
- **Fix:** Инжектировать общий OkHttpClient через Hilt

### [Low] Тройное декодирование Bitmap в MediaPipeVisionProcessor
- **Stage:** 20
- **File:** `vision/MediaPipeVisionProcessor.kt`
- **Problem:** detectFaces/detectObjects/detectGestures каждый декодируют imageBytes
- **Fix:** Декодировать один раз в VisionRouter

### [Low] IntentClassifier Regex создаётся при каждом вызове
- **Stage:** 21-22
- **File:** `orchestrator/IntentClassifier.kt:321`
- **Fix:** Вынести `"\\s+".toRegex()` в companion object

### [Low] Контакты загружаются заново при каждом resolveContact
- **Stage:** 21-22
- **File:** `actions/ContactPreferenceManager.kt:145`
- **Fix:** Кеш с TTL ~30 сек

---

## Сводка

| Severity | Open | Closed |
|----------|:----:|:------:|
| High | 0 | 10 |
| Medium | 5 | 5 |
| Low | 6 | 2 |
| **Total** | **11** | **17** |

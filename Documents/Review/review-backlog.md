# Review Backlog — отложенные замечания

Замечания из code review, не исправленные в текущих стейджах. Для будущих итераций.

## Stage 10 — SQLCipher, ConversationFocus

### ~~[High] Миграция незашифрованной БД → SQLCipher~~ ✅ Stage 15
- **File:** `di/AppModule.kt`
- **Fix:** Добавлен `fallbackToDestructiveMigration()` для безопасного перехода на SQLCipher

### ~~[High] sendMessage race при отсутствии conversation~~ ✅ Stage 15
- **File:** `ui/chat/ChatViewModel.kt`
- **Fix:** Извлечена suspend-функция `createNewConversation()`, убрана вложенная асинхронность

### [Medium] Deprecated MasterKeys API
- **File:** `di/AppModule.kt`
- **Problem:** `MasterKeys.getOrCreate()` deprecated, замена `MasterKey.Builder` в alpha
- **Fix:** Обновить при стабильном релизе security-crypto 1.1.0
- **Status:** Ожидает стабильного релиза библиотеки

## Stage 12 — ToolRegistry

### ~~[Medium] cleanup() не атомарен~~ ✅ Stage 15
- **File:** `data/repository/MemoryRepositoryImpl.kt`
- **Fix:** Заменён на атомарный `deleteExceptTop()` — один SQL запрос

### [Low] ToolRegistryTest не проверяет реальный toolDescriptions
- **File:** `test/.../ToolRegistryTest.kt`
- **Problem:** Тест создаёт локальный список и проверяет его — тавтология
- **Fix:** Инстанцировать ToolRegistry с mock-зависимостями

### ~~[Low] Дублирование тестов cleanup~~ ✅ Stage 15
- **File:** `test/.../MemoryRepositoryImplTest.kt`
- **Fix:** Тесты переписаны, дублирование убрано

## Stage 13 — Tool Calling Pipeline

### ~~[High] Clean Architecture нарушение в domain interface~~ ✅ Stage 15
- **File:** `domain/repository/AiRepository.kt`
- **Fix:** `StreamChunk` перенесён в domain/model/, создан `ToolDefinition` (провайдер-агностик), typealias для обратной совместимости

### ~~[Medium] parseToolArguments теряет вложенные типы~~ ✅ Stage 16
- **File:** `data/remote/ClaudeStreamingClient.kt`, `data/remote/OpenAiStreamingClient.kt`
- **Fix:** Используется `moshi.adapter(Any::class.java).toJson()` для нестроковых значений

### ~~[Medium] playMusic не валидирует обязательный параметр action~~ ✅ Stage 15
- **File:** `orchestrator/ToolRegistry.kt`
- **Fix:** Добавлена явная валидация с ToolResult(false, ...) при отсутствии action

### ~~[Medium] Дублирование маппинга messages в Claude формат~~ ✅ Stage 15
- **File:** `data/repository/AiRepositoryImpl.kt`
- **Fix:** Вынесено в `buildClaudeMessages()` helper

### ~~[Low] buildClaudeTools() вызывается при каждом сообщении~~ ✅ Stage 16
- **File:** `ui/chat/ChatViewModel.kt`
- **Fix:** Lazy-init через `by lazy { toolCallProcessor.buildToolDefinitions() }`

## Оставшиеся открытые замечания

| # | Severity | Issue | Status |
|---|----------|-------|--------|
| 1 | Medium | Deprecated MasterKeys API | Ожидает security-crypto 1.1.0 |
| 2 | Low | ToolRegistryTest тавтология | Открыто |

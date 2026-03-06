# Review Backlog — отложенные замечания

Замечания из code review, не исправленные в текущих стейджах. Для будущих итераций.

## Stage 10 — SQLCipher, ConversationFocus

### [High] Миграция незашифрованной БД → SQLCipher
- **File:** `di/AppModule.kt`
- **Problem:** При обновлении с предыдущей версии (без SQLCipher) Room попытается открыть незашифрованную БД как зашифрованную → SQLiteException
- **Fix:** Добавить проверку + `SQLCipher.encrypt()` или `fallbackToDestructiveMigration()`

### [High] sendMessage race при отсутствии conversation
- **File:** `ui/chat/ChatViewModel.kt`
- **Problem:** `startNewConversation()` асинхронен, следующая строка читает conversationId до его создания
- **Fix:** Сделать suspend-функцией или использовать CompletableDeferred

### [Medium] Deprecated MasterKeys API
- **File:** `di/AppModule.kt`
- **Problem:** `MasterKeys.getOrCreate()` deprecated, замена `MasterKey.Builder` в alpha
- **Fix:** Обновить при стабильном релизе security-crypto 1.1.0

## Stage 12 — ToolRegistry

### [Medium] cleanup() не атомарен
- **File:** `data/repository/MemoryRepositoryImpl.kt`
- **Problem:** 3 последовательных запроса к DAO без транзакции, race condition
- **Fix:** `@Transaction` или один SQL `DELETE WHERE id NOT IN (...)`

### [Low] ToolRegistryTest не проверяет реальный toolDescriptions
- **File:** `test/.../ToolRegistryTest.kt`
- **Problem:** Тест создаёт локальный список и проверяет его — тавтология
- **Fix:** Инстанцировать ToolRegistry с mock-зависимостями

### [Low] Дублирование тестов cleanup
- **File:** `test/.../MemoryRepositoryImplTest.kt`
- **Problem:** Тесты на строках 25-40 и 94-113 идентичны

## Stage 13 — Tool Calling Pipeline

### [High] Clean Architecture нарушение в domain interface
- **File:** `domain/repository/AiRepository.kt`
- **Problem:** Интерфейс зависит от `ClaudeTool` и `StreamChunk` из data layer
- **Fix:** Перенести `StreamChunk` в domain/model/, создать абстракцию `ToolDefinition` вместо `ClaudeTool`

### [Medium] parseToolArguments теряет вложенные типы
- **File:** `data/remote/ClaudeStreamingClient.kt`
- **Problem:** `.toString()` на вложенных объектах даёт Java-представление, не JSON
- **Fix:** Использовать `moshi.adapter(Any::class.java).toJson()` для нестроковых значений

### [Medium] playMusic не валидирует обязательный параметр action
- **File:** `orchestrator/ToolRegistry.kt`
- **Problem:** `action` в required, но в коде `args["action"]?.let` — бесшумно создаёт пустой intent
- **Fix:** Валидировать и возвращать ToolResult(false, ...) если action отсутствует

### [Medium] Дублирование маппинга messages в Claude формат
- **File:** `data/repository/AiRepositoryImpl.kt`
- **Problem:** `streamClaudeWithTools()`, `streamClaude()`, `sendClaude()` дублируют Message → ClaudeMessage
- **Fix:** Вынести в `buildClaudeMessages()`

### [Low] buildClaudeTools() вызывается при каждом сообщении
- **File:** `ui/chat/ChatViewModel.kt`
- **Problem:** Список tools статический, вычисляется повторно
- **Fix:** Lazy init или вычислить в init {}

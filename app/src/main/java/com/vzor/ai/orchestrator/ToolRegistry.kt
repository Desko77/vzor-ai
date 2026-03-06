package com.vzor.ai.orchestrator

import android.util.Log
import com.vzor.ai.actions.ActionExecutor
import com.vzor.ai.domain.model.IntentType
import com.vzor.ai.domain.model.MemoryFact
import com.vzor.ai.domain.model.VzorIntent
import com.vzor.ai.domain.repository.MemoryRepository
import com.vzor.ai.domain.repository.VisionRepository
import com.vzor.ai.glasses.GlassesManager
import com.vzor.ai.translation.TranslationManager
import com.vzor.ai.translation.TranslationMode
import com.vzor.ai.data.remote.TavilySearchService
import com.vzor.ai.data.local.PreferencesManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool Registry — маппинг LLM tool calls к сервисам приложения.
 *
 * Поддерживаемые инструменты (12 из ТЗ):
 * 1. vision.getScene    — описание сцены с камеры
 * 2. vision.describe    — описание изображения
 * 3. action.capture     — фото с камеры очков (NEW)
 * 4. web.search         — поиск через Tavily
 * 5. action.call        — исходящий звонок
 * 6. action.message     — отправка сообщения
 * 7. action.navigate    — навигация
 * 8. action.playMusic   — управление музыкой
 * 9. memory.get         — получить из памяти
 * 10. memory.set        — сохранить в память
 * 11. translate         — перевод текста (NEW)
 * 12. audio.fingerprint — распознавание музыки (заглушка, нужен ACRCloud)
 */
@Singleton
class ToolRegistry @Inject constructor(
    private val visionRepository: VisionRepository,
    private val glassesManager: GlassesManager,
    private val memoryRepository: MemoryRepository,
    private val translationManager: TranslationManager,
    private val tavilySearchService: TavilySearchService,
    private val prefs: PreferencesManager,
    private val actionExecutor: ActionExecutor
) {
    companion object {
        private const val TAG = "ToolRegistry"
    }

    /**
     * Описание всех доступных инструментов для LLM function calling.
     */
    val toolDescriptions: List<ToolDescription> = listOf(
        ToolDescription(
            name = "vision.getScene",
            description = "Описать текущую сцену с камеры умных очков",
            parameters = emptyMap()
        ),
        ToolDescription(
            name = "vision.describe",
            description = "Описать предоставленное изображение",
            parameters = mapOf("prompt" to "string: Вопрос об изображении")
        ),
        ToolDescription(
            name = "action.capture",
            description = "Сфотографировать через камеру умных очков",
            parameters = emptyMap()
        ),
        ToolDescription(
            name = "web.search",
            description = "Поиск информации в интернете",
            parameters = mapOf("query" to "string: Поисковый запрос")
        ),
        ToolDescription(
            name = "memory.get",
            description = "Найти информацию в долговременной памяти",
            parameters = mapOf("query" to "string: Что искать")
        ),
        ToolDescription(
            name = "memory.set",
            description = "Сохранить факт в долговременную память",
            parameters = mapOf(
                "fact" to "string: Факт для запоминания",
                "category" to "string: Категория (PERSONAL, LOCATION, PREFERENCE, TASK)",
                "importance" to "int: Важность (1-5)"
            )
        ),
        ToolDescription(
            name = "translate",
            description = "Перевести текст с одного языка на другой",
            parameters = mapOf(
                "text" to "string: Текст для перевода",
                "from" to "string: Исходный язык (ru, en, etc.)",
                "to" to "string: Целевой язык"
            )
        ),
        ToolDescription(
            name = "action.call",
            description = "Позвонить контакту",
            parameters = mapOf("contact" to "string: Имя контакта или номер телефона")
        ),
        ToolDescription(
            name = "action.message",
            description = "Отправить сообщение контакту (WhatsApp, Telegram, SMS)",
            parameters = mapOf(
                "contact" to "string: Имя получателя",
                "text" to "string: Текст сообщения",
                "app" to "string: Приложение (whatsapp, telegram, sms) — необязательно"
            )
        ),
        ToolDescription(
            name = "action.navigate",
            description = "Навигация к месту назначения",
            parameters = mapOf("destination" to "string: Адрес или название места")
        ),
        ToolDescription(
            name = "action.playMusic",
            description = "Управление воспроизведением музыки",
            parameters = mapOf(
                "action" to "string: Действие (play, pause, next, previous)",
                "query" to "string: Запрос для поиска музыки — необязательно"
            )
        ),
        ToolDescription(
            name = "audio.fingerprint",
            description = "Распознать играющую музыку (Shazam-подобный)",
            parameters = emptyMap()
        )
    )

    /**
     * Выполнить вызов инструмента.
     * @return Результат в виде строки.
     */
    suspend fun executeTool(name: String, args: Map<String, String>): ToolResult {
        Log.d(TAG, "Executing tool: $name with args: ${args.keys}")

        return try {
            when (name) {
                "vision.getScene" -> executeVisionGetScene()
                "vision.describe" -> executeVisionDescribe(args)
                "action.capture" -> executeCapture()
                "web.search" -> executeWebSearch(args)
                "memory.get" -> executeMemoryGet(args)
                "memory.set" -> executeMemorySet(args)
                "translate" -> executeTranslate(args)
                "action.call" -> executeActionCall(args)
                "action.message" -> executeActionMessage(args)
                "action.navigate" -> executeActionNavigate(args)
                "action.playMusic" -> executeActionPlayMusic(args)
                "audio.fingerprint" -> ToolResult(
                    success = false,
                    output = "audio.fingerprint пока не реализован (нужен ACRCloud API ключ)"
                )
                else -> ToolResult(success = false, output = "Неизвестный инструмент: $name")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed: $name", e)
            ToolResult(success = false, output = "Ошибка: ${e.message}")
        }
    }

    private suspend fun executeVisionGetScene(): ToolResult {
        val photo = glassesManager.capturePhoto()
            ?: return ToolResult(false, "Не удалось получить кадр с камеры")

        val result = visionRepository.analyzeImage(photo, "Опиши что ты видишь кратко. Отвечай на русском.")
        return result.fold(
            onSuccess = { ToolResult(true, it) },
            onFailure = { ToolResult(false, "Ошибка анализа: ${it.message}") }
        )
    }

    private suspend fun executeVisionDescribe(args: Map<String, String>): ToolResult {
        val prompt = args["prompt"] ?: "Что ты видишь на этом изображении?"
        val photo = glassesManager.capturePhoto()
            ?: return ToolResult(false, "Не удалось получить кадр с камеры")

        val result = visionRepository.analyzeImage(photo, prompt)
        return result.fold(
            onSuccess = { ToolResult(true, it) },
            onFailure = { ToolResult(false, "Ошибка анализа: ${it.message}") }
        )
    }

    private suspend fun executeCapture(): ToolResult {
        val photo = glassesManager.capturePhoto()
            ?: return ToolResult(false, "Не удалось сделать фото")
        return ToolResult(true, "Фото сделано (${photo.size} байт)", imageData = photo)
    }

    private suspend fun executeWebSearch(args: Map<String, String>): ToolResult {
        val query = args["query"]
            ?: return ToolResult(false, "Не указан поисковый запрос")

        val apiKey = prefs.tavilyApiKey.first()
        if (apiKey.isBlank()) {
            return ToolResult(false, "Tavily API ключ не настроен")
        }

        val response = tavilySearchService.search(
            request = com.vzor.ai.data.remote.TavilySearchRequest(
                apiKey = apiKey,
                query = query
            )
        )
        val resultText = response.results.take(3).joinToString("\n\n") { r ->
            "${r.title}\n${r.content}"
        }
        return ToolResult(true, resultText.ifBlank { "Ничего не найдено" })
    }

    private suspend fun executeMemoryGet(args: Map<String, String>): ToolResult {
        val query = args["query"]
            ?: return ToolResult(false, "Не указан запрос для поиска")

        val facts = memoryRepository.searchFacts(query, 5)
        if (facts.isEmpty()) {
            return ToolResult(true, "Ничего не найдено в памяти по запросу: $query")
        }
        val text = facts.joinToString("\n") { "- ${it.fact} (${it.category})" }
        return ToolResult(true, text)
    }

    private suspend fun executeMemorySet(args: Map<String, String>): ToolResult {
        val fact = args["fact"]
            ?: return ToolResult(false, "Не указан факт для запоминания")
        val category = args["category"] ?: "PERSONAL"
        val importance = args["importance"]?.toIntOrNull() ?: 3

        val id = memoryRepository.saveFact(fact, category, importance)
        return ToolResult(true, "Запомнил: $fact (id=$id, категория=$category)")
    }

    private suspend fun executeTranslate(args: Map<String, String>): ToolResult {
        val text = args["text"]
            ?: return ToolResult(false, "Не указан текст для перевода")
        val from = args["from"] ?: "ru"
        val to = args["to"] ?: "en"

        return try {
            val translated = translationManager.translateText(text, from, to)
            ToolResult(true, translated)
        } catch (e: Exception) {
            ToolResult(false, "Ошибка перевода: ${e.message}")
        }
    }

    private suspend fun executeActionCall(args: Map<String, String>): ToolResult {
        val contact = args["contact"]
            ?: return ToolResult(false, "Не указан контакт для звонка")
        val intent = VzorIntent(
            type = IntentType.CALL_CONTACT,
            confidence = 1.0f,
            slots = mapOf("contact" to contact)
        )
        val result = actionExecutor.execute(intent)
        return ToolResult(result.success, result.message)
    }

    private suspend fun executeActionMessage(args: Map<String, String>): ToolResult {
        val contact = args["contact"]
            ?: return ToolResult(false, "Не указан получатель")
        val text = args["text"]
            ?: return ToolResult(false, "Не указан текст сообщения")
        val slots = mutableMapOf("contact" to contact, "text" to text)
        args["app"]?.let { slots["app"] = it }

        val intent = VzorIntent(
            type = IntentType.SEND_MESSAGE,
            confidence = 1.0f,
            slots = slots
        )
        val result = actionExecutor.execute(intent)
        return ToolResult(result.success, result.message)
    }

    private suspend fun executeActionNavigate(args: Map<String, String>): ToolResult {
        val destination = args["destination"]
            ?: return ToolResult(false, "Не указан пункт назначения")
        val intent = VzorIntent(
            type = IntentType.NAVIGATE,
            confidence = 1.0f,
            slots = mapOf("destination" to destination)
        )
        val result = actionExecutor.execute(intent)
        return ToolResult(result.success, result.message)
    }

    private suspend fun executeActionPlayMusic(args: Map<String, String>): ToolResult {
        val action = args["action"]
            ?: return ToolResult(false, "Не указано действие (play, pause, next, previous)")

        val slots = mutableMapOf("action" to action)
        args["query"]?.let { slots["query"] = it }

        val intent = VzorIntent(
            type = IntentType.PLAY_MUSIC,
            confidence = 1.0f,
            slots = slots
        )
        val result = actionExecutor.execute(intent)
        return ToolResult(result.success, result.message)
    }
}

/**
 * Описание инструмента для LLM function calling.
 */
data class ToolDescription(
    val name: String,
    val description: String,
    val parameters: Map<String, String>
)

/**
 * Результат выполнения инструмента.
 */
data class ToolResult(
    val success: Boolean,
    val output: String,
    val imageData: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ToolResult) return false
        return success == other.success && output == other.output
    }

    override fun hashCode(): Int = 31 * success.hashCode() + output.hashCode()
}

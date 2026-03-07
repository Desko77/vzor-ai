package com.vzor.ai.orchestrator

import android.util.Log
import com.vzor.ai.actions.ActionExecutor
import com.vzor.ai.actions.VideoCaptureAction
import com.vzor.ai.domain.model.IntentType
import com.vzor.ai.domain.model.MemoryFact
import com.vzor.ai.domain.model.VzorIntent
import com.vzor.ai.domain.repository.MemoryRepository
import com.vzor.ai.domain.repository.VisionRepository
import com.vzor.ai.glasses.GlassesManager
import com.vzor.ai.translation.TranslationManager
import com.vzor.ai.translation.TranslationMode
import com.vzor.ai.vision.AccessibilityHelper
import com.vzor.ai.vision.FoodAnalysisPrompts
import com.vzor.ai.vision.ClipEmbeddingService
import com.vzor.ai.vision.PlaceIdentificationHelper
import com.vzor.ai.vision.ShoppingComparisonHelper
import com.vzor.ai.data.remote.AcrCloudService
import com.vzor.ai.data.remote.TavilySearchService
import com.vzor.ai.data.local.PreferencesManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool Registry — маппинг LLM tool calls к сервисам приложения.
 *
 * Поддерживаемые инструменты (20):
 * 1. vision.getScene    — описание сцены с камеры
 * 2. vision.describe    — описание изображения
 * 3. action.capture     — фото с камеры очков
 * 4. web.search         — поиск через Tavily
 * 5. action.call        — исходящий звонок
 * 6. action.message     — отправка сообщения
 * 7. action.navigate    — навигация
 * 8. action.playMusic   — управление музыкой
 * 9. memory.get         — получить из памяти
 * 10. memory.set        — сохранить в память
 * 11. translate         — перевод текста
 * 12. audio.fingerprint — распознавание музыки (ACRCloud)
 * 13. vision.food          — анализ еды: калории, БЖУ, ингредиенты (UC#4)
 * 14. vision.shopping      — шопинг: анализ товара, сравнение, ценники (UC#5)
 * 15. vision.accessibility — доступность: описание окружения, навигация (UC#8)
 * 16. vision.place         — идентификация мест и достопримечательностей (UC#2)
 * 17. action.reminder      — установка напоминания с текстом и задержкой
 * 18. action.timer         — установка таймера обратного отсчёта
 * 19. action.video         — запись видео с камеры очков (UC#11)
 * 20. vision.classify      — zero-shot классификация сцены (CLIP)
 */
@Singleton
class ToolRegistry @Inject constructor(
    private val visionRepository: VisionRepository,
    private val glassesManager: GlassesManager,
    private val memoryRepository: MemoryRepository,
    private val translationManager: TranslationManager,
    private val tavilySearchService: TavilySearchService,
    private val prefs: PreferencesManager,
    private val actionExecutor: ActionExecutor,
    private val acrCloudService: AcrCloudService,
    private val videoCaptureAction: VideoCaptureAction,
    private val clipEmbeddingService: ClipEmbeddingService? = null
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
        ),
        ToolDescription(
            name = "vision.food",
            description = "Анализ еды: определить блюдо, калорийность, БЖУ, ингредиенты",
            parameters = mapOf(
                "mode" to "string: Режим (full, calories, ingredients) — по умолчанию full"
            )
        ),
        ToolDescription(
            name = "vision.shopping",
            description = "Шопинг-помощник: анализ товара, сравнение цен, чтение ценников",
            parameters = mapOf(
                "mode" to "string: Режим (analyze, compare, price) — по умолчанию analyze",
                "query" to "string: Дополнительный вопрос о товаре — необязательно"
            )
        ),
        ToolDescription(
            name = "vision.accessibility",
            description = "Помощь слабовидящим: описание окружения, чтение текста, навигация",
            parameters = mapOf(
                "mode" to "string: Режим (scene, read, navigate, identify) — по умолчанию scene"
            )
        ),
        ToolDescription(
            name = "vision.place",
            description = "Идентификация здания, места или достопримечательности",
            parameters = mapOf(
                "query" to "string: Дополнительный вопрос о месте — необязательно"
            )
        ),
        ToolDescription(
            name = "action.reminder",
            description = "Установить напоминание с текстом через указанное время",
            parameters = mapOf(
                "text" to "string: Текст напоминания",
                "minutes" to "int: Через сколько минут напомнить (по умолчанию 5)"
            )
        ),
        ToolDescription(
            name = "action.timer",
            description = "Установить таймер обратного отсчёта",
            parameters = mapOf(
                "minutes" to "int: Длительность таймера в минутах"
            )
        ),
        ToolDescription(
            name = "action.video",
            description = "Записать видео с камеры умных очков",
            parameters = mapOf(
                "action" to "string: Действие (start, stop) — по умолчанию start",
                "duration" to "int: Длительность записи в секундах (по умолчанию 15, макс 60)"
            )
        ),
        ToolDescription(
            name = "vision.classify",
            description = "Быстрая zero-shot классификация сцены (еда, товар, текст, здание и т.д.)",
            parameters = mapOf(
                "labels" to "string: Категории через запятую — необязательно"
            )
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
                "vision.food" -> executeVisionFood(args)
                "vision.shopping" -> executeVisionShopping(args)
                "vision.accessibility" -> executeVisionAccessibility(args)
                "vision.place" -> executeVisionPlace(args)
                "action.reminder" -> executeActionReminder(args)
                "action.timer" -> executeActionTimer(args)
                "audio.fingerprint" -> executeAudioFingerprint()
                "action.video" -> executeVideoAction(args)
                "vision.classify" -> executeVisionClassify(args)
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

    private suspend fun executeVisionFood(args: Map<String, String>): ToolResult {
        val photo = glassesManager.capturePhoto()
            ?: return ToolResult(false, "Не удалось получить кадр с камеры")

        val mode = args["mode"]?.lowercase() ?: "full"
        val prompt = when (mode) {
            "calories" -> FoodAnalysisPrompts.buildQuickCaloriePrompt()
            "ingredients" -> FoodAnalysisPrompts.buildIngredientsPrompt()
            else -> FoodAnalysisPrompts.buildAnalysisPrompt(args["query"] ?: "Что это за блюдо?")
        }

        val result = visionRepository.analyzeImage(photo, prompt)
        return result.fold(
            onSuccess = { ToolResult(true, it) },
            onFailure = { ToolResult(false, "Ошибка анализа еды: ${it.message}") }
        )
    }

    private suspend fun executeVisionShopping(args: Map<String, String>): ToolResult {
        val photo = glassesManager.capturePhoto()
            ?: return ToolResult(false, "Не удалось получить кадр с камеры")

        val mode = args["mode"]?.lowercase() ?: "analyze"
        val userQuery = args["query"] ?: "Проанализируй товар"
        val prompt = when (mode) {
            "compare" -> ShoppingComparisonHelper.buildComparisonPrompt()
            "price" -> ShoppingComparisonHelper.buildPriceTagPrompt()
            else -> ShoppingComparisonHelper.buildProductAnalysisPrompt(userQuery)
        }

        val result = visionRepository.analyzeImage(photo, prompt)
        return result.fold(
            onSuccess = { ToolResult(true, it) },
            onFailure = { ToolResult(false, "Ошибка анализа товара: ${it.message}") }
        )
    }

    private suspend fun executeVisionAccessibility(args: Map<String, String>): ToolResult {
        val photo = glassesManager.capturePhoto()
            ?: return ToolResult(false, "Не удалось получить кадр с камеры")

        val mode = args["mode"]?.lowercase() ?: "scene"
        val prompt = when (mode) {
            "read" -> AccessibilityHelper.buildReadAloudPrompt()
            "navigate" -> AccessibilityHelper.buildNavigationAssistPrompt()
            "identify" -> AccessibilityHelper.buildObjectIdentificationPrompt(args["query"] ?: "Что это?")
            else -> AccessibilityHelper.buildSceneDescriptionPrompt()
        }

        val result = visionRepository.analyzeImage(photo, prompt)
        return result.fold(
            onSuccess = { ToolResult(true, it) },
            onFailure = { ToolResult(false, "Ошибка анализа: ${it.message}") }
        )
    }

    private suspend fun executeVisionPlace(args: Map<String, String>): ToolResult {
        val photo = glassesManager.capturePhoto()
            ?: return ToolResult(false, "Не удалось получить кадр с камеры")

        val userQuery = args["query"] ?: "Что это за место?"
        val prompt = PlaceIdentificationHelper.buildPlaceIdentificationPrompt(userQuery)

        val result = visionRepository.analyzeImage(photo, prompt)
        return result.fold(
            onSuccess = { ToolResult(true, it) },
            onFailure = { ToolResult(false, "Ошибка идентификации места: ${it.message}") }
        )
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

    private suspend fun executeActionReminder(args: Map<String, String>): ToolResult {
        val text = args["text"]
            ?: return ToolResult(false, "Не указан текст напоминания")
        val minutes = args["minutes"]?.toIntOrNull() ?: 5

        val intent = VzorIntent(
            type = IntentType.SET_REMINDER,
            confidence = 1.0f,
            slots = mapOf("text" to text, "minutes" to minutes.toString())
        )
        val result = actionExecutor.execute(intent)
        return ToolResult(result.success, result.message)
    }

    private suspend fun executeActionTimer(args: Map<String, String>): ToolResult {
        val minutes = args["minutes"]?.toIntOrNull()
            ?: return ToolResult(false, "Не указана длительность таймера")

        val intent = VzorIntent(
            type = IntentType.SET_REMINDER,
            confidence = 1.0f,
            slots = mapOf("type" to "timer", "minutes" to minutes.toString())
        )
        val result = actionExecutor.execute(intent)
        return ToolResult(result.success, result.message)
    }

    private suspend fun executeAudioFingerprint(): ToolResult {
        if (!acrCloudService.isConfigured()) {
            return ToolResult(false, "ACRCloud не настроен. Укажите Access Key, Secret и Host в настройках.")
        }

        // Записываем аудио через GlassesManager (8 сек)
        val audioData = glassesManager.recordAudioChunk(AcrCloudService.RECOMMENDED_AUDIO_DURATION_MS)
            ?: return ToolResult(false, "Не удалось записать аудио для распознавания")

        val result = acrCloudService.identify(audioData)
            ?: return ToolResult(false, "Не удалось распознать музыку. Попробуйте ещё раз в тихом месте.")

        return ToolResult(true, result.formatForUser())
    }

    private suspend fun executeVideoAction(args: Map<String, String>): ToolResult {
        val action = args["action"]?.lowercase() ?: "start"
        return if (action == "stop") {
            val result = videoCaptureAction.stopRecording()
            ToolResult(result.success, result.message)
        } else {
            val duration = args["duration"]?.toIntOrNull() ?: 15
            val result = videoCaptureAction.startRecording(duration)
            ToolResult(result.success, result.message)
        }
    }

    private suspend fun executeVisionClassify(args: Map<String, String>): ToolResult {
        val clip = clipEmbeddingService
            ?: return ToolResult(false, "CLIP не доступен (Edge AI сервер отключён)")

        val photo = glassesManager.capturePhoto()
            ?: return ToolResult(false, "Не удалось получить кадр с камеры")

        val customLabels = args["labels"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
        val labels = if (!customLabels.isNullOrEmpty()) customLabels else clip.defaultSceneLabels

        val result = clip.classify(photo, labels)
            ?: return ToolResult(false, "Не удалось классифицировать сцену")

        val output = buildString {
            appendLine("Классификация сцены:")
            result.scores.take(3).forEach { score ->
                appendLine("  ${score.label}: ${(score.score * 100).toInt()}%")
            }
        }
        return ToolResult(true, output.trim())
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
        return success == other.success && output == other.output &&
            imageData.contentEquals(other.imageData)
    }

    override fun hashCode(): Int {
        var result = 31 * success.hashCode() + output.hashCode()
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        return result
    }
}

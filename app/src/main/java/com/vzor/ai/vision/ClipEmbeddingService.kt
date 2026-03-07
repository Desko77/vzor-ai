package com.vzor.ai.vision

import android.util.Base64
import android.util.Log
import com.vzor.ai.data.remote.OllamaMessage
import com.vzor.ai.data.remote.OllamaService
import com.vzor.ai.orchestrator.ModelPriority
import com.vzor.ai.orchestrator.ModelRuntimeManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zero-shot visual classification через CLIP ViT-B/32 на Edge AI сервере.
 *
 * CLIP (Contrastive Language-Image Pre-training) позволяет классифицировать
 * изображения по произвольным текстовым меткам без дообучения.
 *
 * Использование:
 * - Быстрая предварительная классификация сцены перед cloud VLM
 * - Определение типа объекта для маршрутизации промптов
 * - Фильтрация нерелевантных кадров в LiveCommentary
 *
 * Модель: clip:vit-b32 через Ollama (~0.6GB VRAM)
 */
@Singleton
class ClipEmbeddingService @Inject constructor(
    private val ollamaService: OllamaService,
    private val modelRuntimeManager: ModelRuntimeManager
) {
    companion object {
        private const val TAG = "ClipEmbedding"
        private const val CLIP_MODEL = "clip:vit-b32"
        private const val CLIP_MEMORY_MB = 600
    }

    /**
     * Результат zero-shot классификации.
     *
     * @param label Наиболее подходящая метка.
     * @param scores Все метки с оценками (отсортированы по убыванию).
     */
    data class ClassificationResult(
        val label: String,
        val confidence: Float,
        val scores: List<LabelScore>
    )

    data class LabelScore(
        val label: String,
        val score: Float
    )

    /** Стандартные категории сцен для pre-classification. */
    val defaultSceneLabels = listOf(
        "еда на тарелке",
        "товар в магазине",
        "текст или вывеска",
        "здание или достопримечательность",
        "человек или группа людей",
        "природа или пейзаж",
        "документ или книга",
        "дорога или тротуар"
    )

    /**
     * Регистрирует CLIP модель в ModelRuntimeManager.
     * Вызывается при инициализации приложения.
     */
    suspend fun register() {
        modelRuntimeManager.registerModel(CLIP_MODEL, ModelPriority.CLIP, CLIP_MEMORY_MB)
    }

    /**
     * Zero-shot классификация изображения по заданным текстовым меткам.
     *
     * Отправляет изображение + список меток на Edge AI сервер,
     * где CLIP ViT-B/32 вычисляет cosine similarity между
     * image embedding и text embeddings каждой метки.
     *
     * @param imageBytes JPEG или PNG изображение.
     * @param labels Текстовые метки для классификации.
     * @return ClassificationResult или null если сервер недоступен.
     */
    suspend fun classify(
        imageBytes: ByteArray,
        labels: List<String> = defaultSceneLabels
    ): ClassificationResult? {
        if (labels.isEmpty()) return null

        // Запрашиваем загрузку модели (выгрузит низкоприоритетные если нужно)
        val loaded = modelRuntimeManager.requestLoad(CLIP_MODEL)
        if (!loaded) {
            Log.w(TAG, "Failed to load CLIP model — insufficient memory")
            return null
        }

        return try {
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val labelsFormatted = labels.mapIndexed { i, l -> "${i + 1}. $l" }.joinToString("\n")

            val prompt = """Classify this image. Which label best matches?
$labelsFormatted

Respond ONLY with the number and confidence (0.0-1.0) for EACH label, one per line:
1: 0.85
2: 0.12
..."""

            val response = ollamaService.sendMessage(
                model = CLIP_MODEL,
                messages = listOf(
                    OllamaMessage(
                        role = "user",
                        content = prompt,
                        images = listOf(base64Image)
                    )
                )
            )

            val responseText = response.message?.content ?: return null
            parseClassificationResponse(responseText, labels)
        } catch (e: Exception) {
            Log.e(TAG, "CLIP classification failed", e)
            null
        }
    }

    /**
     * Быстрая предварительная классификация сцены.
     * Использует стандартные метки для определения типа контента.
     */
    suspend fun preClassifyScene(imageBytes: ByteArray): String? {
        val result = classify(imageBytes, defaultSceneLabels) ?: return null
        return if (result.confidence >= 0.5f) result.label else null
    }

    /**
     * Проверяет доступность CLIP на Edge AI сервере.
     */
    suspend fun isAvailable(): Boolean {
        return try {
            ollamaService.isHealthy()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Парсит ответ CLIP модели с оценками.
     * Ожидаемый формат: "1: 0.85\n2: 0.12\n..."
     */
    internal fun parseClassificationResponse(
        response: String,
        labels: List<String>
    ): ClassificationResult? {
        val scorePattern = Regex("""(\d+)\s*:\s*([\d.]+)""")
        val scores = mutableListOf<LabelScore>()

        for (match in scorePattern.findAll(response)) {
            val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: continue
            val score = match.groupValues[2].toFloatOrNull() ?: continue
            if (index in labels.indices) {
                scores.add(LabelScore(labels[index], score.coerceIn(0f, 1f)))
            }
        }

        if (scores.isEmpty()) return null

        val sorted = scores.sortedByDescending { it.score }
        return ClassificationResult(
            label = sorted.first().label,
            confidence = sorted.first().score,
            scores = sorted
        )
    }
}

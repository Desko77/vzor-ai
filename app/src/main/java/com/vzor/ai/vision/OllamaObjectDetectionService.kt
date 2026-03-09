package com.vzor.ai.vision

import android.util.Base64
import android.util.Log
import com.vzor.ai.data.remote.OllamaMessage
import com.vzor.ai.data.remote.OllamaService
import com.vzor.ai.domain.model.BoundingBox
import com.vzor.ai.domain.model.DetectedObject
import com.vzor.ai.orchestrator.ModelPriority
import com.vzor.ai.orchestrator.ModelRuntimeManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Обнаружение объектов через VLM на Edge AI сервере (Ollama).
 *
 * Использует Qwen-VL для структурированного детектирования объектов
 * с bounding boxes — замена нативному YOLOv8 через prompt-based подход.
 *
 * Применение:
 * - Детальное обнаружение объектов с координатами (Tier 3)
 * - Дополнение MediaPipe EfficientDet (больше категорий, лучшее описание)
 * - Входные данные для SceneComposer
 *
 * Модель: qwen-vl:7b через Ollama (~14GB VRAM)
 */
@Singleton
class OllamaObjectDetectionService @Inject constructor(
    private val ollamaService: OllamaService,
    private val modelRuntimeManager: ModelRuntimeManager
) {
    companion object {
        private const val TAG = "OllamaObjDetect"
        private const val DETECTION_MODEL = "qwen-vl:7b"
        private const val DETECTION_MEMORY_MB = 14_000
        private const val MAX_OBJECTS = 20
        private val CONFIDENCE_PATTERN = Regex("""\((\d*\.?\d+)\)""")
        private val BBOX_PATTERN = Regex("""\[\s*(-?\d*\.?\d+)\s*,\s*(-?\d*\.?\d+)\s*,\s*(-?\d*\.?\d+)\s*,\s*(-?\d*\.?\d+)\s*]""")
    }

    data class DetectionResult(
        val objects: List<DetectedObject>,
        val rawResponse: String
    )

    /**
     * Регистрирует модель обнаружения в ModelRuntimeManager.
     */
    suspend fun register() {
        modelRuntimeManager.registerModel(
            DETECTION_MODEL,
            ModelPriority.OBJECT_DETECTION,
            DETECTION_MEMORY_MB
        )
    }

    /**
     * Обнаруживает объекты на изображении через Edge AI VLM.
     *
     * @param imageBytes JPEG/PNG изображение.
     * @param categories Опциональный список категорий для фокусировки.
     * @return Список обнаруженных объектов или null при ошибке.
     */
    suspend fun detectObjects(
        imageBytes: ByteArray,
        categories: List<String>? = null
    ): DetectionResult? {
        val loaded = modelRuntimeManager.requestLoad(DETECTION_MODEL)
        if (!loaded) {
            Log.w(TAG, "Failed to load detection model — insufficient memory")
            return null
        }

        return try {
            val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

            val categoryHint = if (!categories.isNullOrEmpty()) {
                "\nFocus on: ${categories.joinToString(", ")}."
            } else {
                ""
            }

            val prompt = """Detect all objects in this image. For each object, provide:
- label (in Russian if possible)
- confidence (0.0-1.0)
- bounding box as x,y,width,height (normalized 0.0-1.0)$categoryHint

Format EXACTLY as:
object: label (confidence) [x, y, w, h]

Example:
object: чашка кофе (0.92) [0.1, 0.3, 0.15, 0.2]
object: ноутбук (0.87) [0.4, 0.2, 0.5, 0.6]

List up to $MAX_OBJECTS objects, sorted by confidence."""

            val response = ollamaService.sendMessage(
                model = DETECTION_MODEL,
                messages = listOf(
                    OllamaMessage(
                        role = "user",
                        content = prompt,
                        images = listOf(base64Image)
                    )
                )
            )

            val responseText = response.message?.content ?: return null
            val objects = parseDetectionResponse(responseText)
            DetectionResult(objects = objects, rawResponse = responseText)
        } catch (e: Exception) {
            Log.e(TAG, "Object detection failed", e)
            null
        }
    }

    /**
     * Быстрое обнаружение: возвращает только метки и confidence без bbox.
     */
    suspend fun detectLabels(imageBytes: ByteArray): List<DetectedObject>? {
        return detectObjects(imageBytes)?.objects?.map {
            it.copy(boundingBox = null)
        }
    }

    /**
     * Проверяет доступность модели на Edge AI сервере.
     */
    suspend fun isAvailable(): Boolean {
        return try {
            ollamaService.isHealthy()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Парсит ответ VLM с обнаруженными объектами.
     * Формат: "object: label (confidence) [x, y, w, h]"
     */
    internal fun parseDetectionResponse(response: String): List<DetectedObject> {
        val objects = mutableListOf<DetectedObject>()

        for (line in response.lines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("object:", ignoreCase = true)) continue

            val parsed = parseObjectLine(trimmed) ?: continue
            objects.add(parsed)

            if (objects.size >= MAX_OBJECTS) break
        }

        return objects.sortedByDescending { it.confidence }
    }

    /**
     * Парсит одну строку формата "object: label (confidence) [x, y, w, h]".
     */
    private fun parseObjectLine(line: String): DetectedObject? {
        // object: label (0.92) [0.1, 0.3, 0.15, 0.2]
        val withoutPrefix = line.substringAfter(":", "").trim()
        if (withoutPrefix.isEmpty()) return null

        // Извлекаем confidence: (...)
        val confMatch = CONFIDENCE_PATTERN.find(withoutPrefix)
        val confidence = confMatch?.groupValues?.get(1)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f

        // Извлекаем label (до скобки confidence)
        val label = if (confMatch != null) {
            withoutPrefix.substring(0, confMatch.range.first).trim()
        } else {
            withoutPrefix.substringBefore("[").trim()
        }

        if (label.isEmpty()) return null

        // Извлекаем bbox: [x, y, w, h]
        val bboxMatch = BBOX_PATTERN.find(withoutPrefix)
        val bbox = if (bboxMatch != null) {
            val values = bboxMatch.groupValues.drop(1).mapNotNull { it.toFloatOrNull() }
            if (values.size == 4) {
                BoundingBox(
                    x = values[0].coerceIn(0f, 1f),
                    y = values[1].coerceIn(0f, 1f),
                    width = values[2].coerceIn(0f, 1f),
                    height = values[3].coerceIn(0f, 1f)
                )
            } else null
        } else null

        return DetectedObject(
            label = label,
            confidence = confidence,
            boundingBox = bbox
        )
    }

}

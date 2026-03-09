package com.vzor.ai.vision

import android.util.Log
import com.vzor.ai.domain.model.DetectedObject
import com.vzor.ai.domain.model.SceneData
import com.vzor.ai.domain.repository.VisionRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VisionRouter @Inject constructor(
    private val perceptionCache: PerceptionCache,
    private val visionRepository: VisionRepository,
    private val onDeviceProcessor: OnDeviceVisionProcessor,
    private val mediaPipeProcessor: MediaPipeVisionProcessor,
    private val budgetManager: VisionBudgetManager,
    private val clipEmbeddingService: ClipEmbeddingService? = null,
    private val ollamaObjectDetection: OllamaObjectDetectionService? = null
) {
    companion object {
        private const val TAG = "VisionRouter"
        private const val CACHE_KEY_SCENE = "scene_cloud"
        private const val CACHE_KEY_SCENE_PREPROCESSED = "scene_preprocessed"
        private const val DEFAULT_SCENE_TTL = 10_000L

        private val OBJECT_PATTERN = Regex("""(?i)object:\s*(.+?)\s*\((\d*\.?\d+)\)""")
        private val TEXT_PATTERN = Regex("""(?i)text:\s*(.+)""")
        private val SUMMARY_PATTERN = Regex("""(?i)summary:\s*(.+)""")
    }

    /**
     * Analyzes a scene from the given image bytes and prompt.
     *
     * If a valid cache entry exists and [forceRefresh] is false, the cached
     * [SceneData] is returned immediately. Otherwise a fresh analysis is
     * requested from the [VisionRepository], parsed into [SceneData], cached,
     * and returned.
     */
    suspend fun analyzeScene(
        imageBytes: ByteArray,
        prompt: String,
        forceRefresh: Boolean = false,
        skipEnrichment: Boolean = false
    ): Result<SceneData> {
        if (!forceRefresh) {
            val cached = perceptionCache.get(CACHE_KEY_SCENE)
            if (cached != null) {
                return Result.success(cached)
            }
        }

        // Проверяем бюджет перед cloud VLM запросом
        if (!budgetManager.tryAcquire()) {
            return Result.failure(Exception("Vision API rate limit exceeded"))
        }

        // Автоматически обогащаем промпт для специализированных запросов.
        // skipEnrichment=true когда вызов идёт из ToolRegistry (промпт уже обогащён).
        val enhancedPrompt = if (skipEnrichment) prompt else when {
            FoodAnalysisPrompts.isFoodQuery(prompt) ->
                FoodAnalysisPrompts.buildAnalysisPrompt(prompt)
            ShoppingComparisonHelper.isShoppingQuery(prompt) ->
                ShoppingComparisonHelper.buildProductAnalysisPrompt(prompt)
            AccessibilityHelper.isAccessibilityQuery(prompt) ->
                AccessibilityHelper.buildSceneDescriptionPrompt()
            PlaceIdentificationHelper.isPlaceQuery(prompt) ->
                PlaceIdentificationHelper.buildPlaceIdentificationPrompt(prompt)
            else -> prompt
        }

        return visionRepository.analyzeImage(imageBytes, enhancedPrompt)
            .map { rawResponse -> parseSceneData(rawResponse) }
            .onSuccess { sceneData ->
                val ttl = determineTtl(sceneData)
                perceptionCache.put(CACHE_KEY_SCENE, sceneData, ttl)
            }
    }

    /**
     * Analyzes a scene with on-device OCR preprocessing.
     * For text-focused queries, tries ML Kit OCR first — if successful, skips cloud VLM.
     * Otherwise falls through to standard [analyzeScene].
     */
    suspend fun analyzeSceneWithPreprocessing(
        imageBytes: ByteArray,
        prompt: String,
        forceRefresh: Boolean = false
    ): Result<SceneData> {
        // 1. MediaPipe: пакетное on-device обнаружение (1 Bitmap decode вместо 3)
        val batch = mediaPipeProcessor.detectAll(imageBytes)
        val faces = batch.faces
        val mpObjects = batch.objects
        val gestures = batch.gestures

        // 2. ML Kit OCR для текстовых запросов
        if (onDeviceProcessor.isTextQuery(prompt)) {
            val ocrResult = onDeviceProcessor.recognizeText(imageBytes)
            if (ocrResult != null && ocrResult.fullText.isNotBlank() &&
                ocrResult.confidence >= OnDeviceVisionProcessor.MIN_CONFIDENCE) {

                val sceneData = SceneData(
                    sceneId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    sceneSummary = ocrResult.fullText,
                    objects = mpObjects.map { DetectedObject(it.label, it.confidence) },
                    text = ocrResult.blocks.map { it.text },
                    faceCount = faces.size,
                    gestures = gestures,
                    stability = 0.9f,
                    ttlMs = PerceptionCache.DefaultTtl.TEXT_MS
                )
                perceptionCache.put(CACHE_KEY_SCENE_PREPROCESSED, sceneData, PerceptionCache.DefaultTtl.TEXT_MS)
                return Result.success(sceneData)
            }
        }

        // 3. CLIP pre-classification (если Edge AI доступен)
        val sceneCategory = try {
            clipEmbeddingService?.preClassifyScene(imageBytes)
        } catch (e: Exception) {
            Log.d(TAG, "CLIP pre-classification skipped: ${e.message}")
            null
        }

        // 4. Ollama object detection (Edge AI, Qwen-VL, более точное чем MediaPipe)
        val edgeObjects = try {
            ollamaObjectDetection?.detectObjects(imageBytes)?.objects
        } catch (e: Exception) {
            Log.d(TAG, "Ollama object detection skipped: ${e.message}")
            null
        }

        // 5. Если Edge AI дал достаточно объектов — можно обойтись без Cloud VLM
        if (edgeObjects != null && edgeObjects.size >= 3) {
            val combinedObjects = mergeDetections(
                mpObjects.map { DetectedObject(it.label, it.confidence) },
                edgeObjects
            )
            val sceneData = SceneData(
                sceneId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                sceneSummary = buildEdgeSummary(combinedObjects, sceneCategory),
                objects = combinedObjects,
                faceCount = faces.size,
                gestures = gestures,
                stability = computeStability(combinedObjects),
                ttlMs = PerceptionCache.DefaultTtl.OBJECTS_MS
            )
            perceptionCache.put(CACHE_KEY_SCENE_PREPROCESSED, sceneData, PerceptionCache.DefaultTtl.OBJECTS_MS)
            return Result.success(sceneData)
        }

        // 6. Cloud VLM (обогащённый MediaPipe + CLIP + Edge результатами)
        val enrichedPrompt = buildEnrichedPrompt(prompt, sceneCategory, edgeObjects)

        return analyzeScene(imageBytes, enrichedPrompt, forceRefresh).map { sceneData ->
            val allEdgeObjects = (edgeObjects ?: emptyList()) +
                mpObjects.map { DetectedObject(it.label, it.confidence) }
            sceneData.copy(
                faceCount = faces.size,
                gestures = gestures,
                objects = mergeDetections(sceneData.objects, allEdgeObjects)
            )
        }
    }

    /**
     * Returns the latest cached scene if it exists and has not expired.
     */
    fun getCachedScene(): SceneData? {
        return perceptionCache.get(CACHE_KEY_SCENE)
    }

    /**
     * Parses the raw VLM response string into a [SceneData] object.
     * The parser handles common response formats: plain text summaries
     * and structured responses that may contain object labels and OCR text.
     */
    private fun parseSceneData(raw: String): SceneData {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val objects = mutableListOf<DetectedObject>()
        val textFragments = mutableListOf<String>()
        var summary = raw.take(500)

        for (line in lines) {
            // Detect lines like "object: label (confidence)"
            val objectMatch = OBJECT_PATTERN.matchEntire(line)
            if (objectMatch != null) {
                val label = objectMatch.groupValues[1].trim()
                val confidence = objectMatch.groupValues[2].toFloatOrNull() ?: 0.8f
                objects.add(DetectedObject(label = label, confidence = confidence))
                continue
            }

            // Detect lines like "text: ..."
            val textMatch = TEXT_PATTERN.matchEntire(line)
            if (textMatch != null) {
                textFragments.add(textMatch.groupValues[1].trim())
                continue
            }

            // Detect lines like "summary: ..."
            val summaryMatch = SUMMARY_PATTERN.matchEntire(line)
            if (summaryMatch != null) {
                summary = summaryMatch.groupValues[1].trim()
            }
        }

        // If no structured data found, use the raw response as the summary
        if (objects.isEmpty() && textFragments.isEmpty()) {
            summary = raw.take(500)
        }

        val stability = if (objects.size > 3) 0.8f else 0.5f

        return SceneData(
            sceneId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            sceneSummary = summary,
            objects = objects,
            text = textFragments,
            stability = stability,
            ttlMs = DEFAULT_SCENE_TTL
        )
    }

    private fun determineTtl(sceneData: SceneData): Long {
        return when {
            sceneData.text.isNotEmpty() -> PerceptionCache.DefaultTtl.TEXT_MS
            sceneData.objects.size > 5 -> PerceptionCache.DefaultTtl.OBJECTS_MS
            else -> PerceptionCache.DefaultTtl.SCENE_DESCRIPTION_MS
        }
    }

    /**
     * Объединяет детекции из разных источников, убирая дубликаты
     * по похожим меткам (предпочитает более высокий confidence).
     */
    private fun mergeDetections(
        primary: List<DetectedObject>,
        secondary: List<DetectedObject>
    ): List<DetectedObject> {
        val merged = primary.toMutableList()
        for (obj in secondary) {
            val duplicate = merged.find {
                it.label.equals(obj.label, ignoreCase = true) ||
                    it.label.contains(obj.label, ignoreCase = true) ||
                    obj.label.contains(it.label, ignoreCase = true)
            }
            if (duplicate != null) {
                // Оставляем с более высоким confidence
                if (obj.confidence > duplicate.confidence) {
                    merged.remove(duplicate)
                    merged.add(obj)
                }
            } else {
                merged.add(obj)
            }
        }
        return merged.sortedByDescending { it.confidence }
    }

    /**
     * Строит обогащённый промпт для Cloud VLM с результатами Edge AI.
     */
    private fun buildEnrichedPrompt(
        prompt: String,
        sceneCategory: String?,
        edgeObjects: List<DetectedObject>?
    ): String {
        val hints = mutableListOf<String>()
        if (sceneCategory != null) {
            hints.add("Предварительная классификация сцены: $sceneCategory")
        }
        if (!edgeObjects.isNullOrEmpty()) {
            val labels = edgeObjects.take(5).joinToString(", ") { "${it.label} (${it.confidence})" }
            hints.add("Edge AI детекция: $labels")
        }
        return if (hints.isNotEmpty()) {
            "$prompt\n[${hints.joinToString("; ")}]"
        } else {
            prompt
        }
    }

    /**
     * Строит краткое описание сцены по Edge AI результатам (без Cloud VLM).
     */
    private fun buildEdgeSummary(objects: List<DetectedObject>, category: String?): String {
        val topObjects = objects.take(5).joinToString(", ") { it.label }
        val prefix = if (category != null) "[$category] " else ""
        return "${prefix}Обнаружены: $topObjects"
    }

    /**
     * Вычисляет stability score для Edge AI результатов.
     */
    private fun computeStability(objects: List<DetectedObject>): Float {
        if (objects.isEmpty()) return 0f
        val avgConf = objects.map { it.confidence }.average().toFloat()
        return (avgConf * 0.7f + 0.3f).coerceIn(0f, 1f)
    }

}

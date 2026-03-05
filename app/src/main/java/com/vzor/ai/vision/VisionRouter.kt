package com.vzor.ai.vision

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
    private val mediaPipeProcessor: MediaPipeVisionProcessor
) {

    companion object {
        private const val CACHE_KEY_SCENE = "scene_latest"
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
        forceRefresh: Boolean = false
    ): Result<SceneData> {
        if (!forceRefresh) {
            val cached = perceptionCache.get(CACHE_KEY_SCENE)
            if (cached != null) {
                return Result.success(cached)
            }
        }

        return visionRepository.analyzeImage(imageBytes, prompt)
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
        // 1. MediaPipe: быстрое on-device обнаружение лиц и объектов
        val faces = mediaPipeProcessor.detectFaces(imageBytes)
        val mpObjects = mediaPipeProcessor.detectObjects(imageBytes)

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
                    stability = 0.9f,
                    ttlMs = PerceptionCache.DefaultTtl.TEXT_MS
                )
                perceptionCache.put(CACHE_KEY_SCENE, sceneData, PerceptionCache.DefaultTtl.TEXT_MS)
                return Result.success(sceneData)
            }
        }

        // 3. Cloud VLM (обогащённый MediaPipe результатами)
        return analyzeScene(imageBytes, prompt, forceRefresh).map { sceneData ->
            sceneData.copy(
                faceCount = faces.size,
                objects = sceneData.objects + mpObjects.map { DetectedObject(it.label, it.confidence) }
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

}

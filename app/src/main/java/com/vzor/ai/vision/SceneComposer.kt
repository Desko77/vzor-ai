package com.vzor.ai.vision

import com.vzor.ai.domain.model.DetectedObject
import com.vzor.ai.domain.model.SceneData
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SceneComposer @Inject constructor() {

    companion object {
        /** Minimum number of objects to consider the scene "complex". */
        private const val COMPLEX_SCENE_THRESHOLD = 5

        /** TTL for complex scenes with many objects (ms). */
        private const val COMPLEX_SCENE_TTL = 30_000L

        /** TTL for simple scenes (ms). */
        private const val SIMPLE_SCENE_TTL = 10_000L

        /** TTL for text-heavy scenes (ms). */
        private const val TEXT_SCENE_TTL = 60_000L

        /** Confidence threshold below which objects are considered unreliable. */
        private const val LOW_CONFIDENCE_THRESHOLD = 0.4f
    }

    /**
     * Composes a [SceneData] instance from raw detection results.
     *
     * @param objects  List of detected objects from the vision model.
     * @param ocrText  Raw OCR text extracted from the frame, or null if none.
     * @param description  A scene-level description from the VLM, or null.
     * @return A fully populated [SceneData] with computed stability and TTL.
     */
    fun compose(
        objects: List<DetectedObject>,
        ocrText: String?,
        description: String?
    ): SceneData {
        val timestamp = System.currentTimeMillis()
        val stability = computeStability(objects)
        val ttl = computeTtl(objects, ocrText)
        val textList = parseOcrText(ocrText)
        val summary = buildSummary(objects, textList, description)

        return SceneData(
            sceneId = UUID.randomUUID().toString(),
            timestamp = timestamp,
            sceneSummary = summary,
            objects = objects,
            text = textList,
            stability = stability,
            ttlMs = ttl
        )
    }

    /**
     * Computes a stability score between 0.0 and 1.0 based on detection
     * consistency. Higher average confidence and more detections yield a
     * higher stability score.
     */
    private fun computeStability(objects: List<DetectedObject>): Float {
        if (objects.isEmpty()) return 0.0f

        val avgConfidence = objects.map { it.confidence }.average().toFloat()
        val reliableCount = objects.count { it.confidence >= LOW_CONFIDENCE_THRESHOLD }
        val reliableRatio = reliableCount.toFloat() / objects.size.toFloat()

        // Weighted combination: 60% average confidence, 40% ratio of reliable detections
        val raw = (avgConfidence * 0.6f) + (reliableRatio * 0.4f)
        return raw.coerceIn(0.0f, 1.0f)
    }

    /**
     * Determines an appropriate TTL based on scene complexity.
     * Text-heavy scenes get longer TTLs (text changes less often).
     * Complex scenes with many objects get moderate TTLs.
     * Simple scenes get short TTLs to stay fresh.
     */
    private fun computeTtl(objects: List<DetectedObject>, ocrText: String?): Long {
        return when {
            !ocrText.isNullOrBlank() && ocrText.length > 20 -> TEXT_SCENE_TTL
            objects.size >= COMPLEX_SCENE_THRESHOLD -> COMPLEX_SCENE_TTL
            else -> SIMPLE_SCENE_TTL
        }
    }

    /**
     * Splits raw OCR text into individual lines/fragments, filtering blanks.
     */
    private fun parseOcrText(ocrText: String?): List<String> {
        if (ocrText.isNullOrBlank()) return emptyList()
        return ocrText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Builds a human-readable scene summary from the available data.
     */
    private fun buildSummary(
        objects: List<DetectedObject>,
        textFragments: List<String>,
        description: String?
    ): String {
        // Prefer the VLM description if available
        if (!description.isNullOrBlank()) {
            return description
        }

        val parts = mutableListOf<String>()

        if (objects.isNotEmpty()) {
            val topObjects = objects
                .sortedByDescending { it.confidence }
                .take(5)
                .joinToString(", ") { it.label }
            parts.add("Objects detected: $topObjects")
        }

        if (textFragments.isNotEmpty()) {
            val textPreview = textFragments.take(3).joinToString("; ")
            parts.add("Text visible: $textPreview")
        }

        return if (parts.isNotEmpty()) {
            parts.joinToString(". ")
        } else {
            "No notable elements detected in scene."
        }
    }
}

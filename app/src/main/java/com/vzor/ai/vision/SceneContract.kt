package com.vzor.ai.vision

import android.graphics.PointF
import android.graphics.RectF

/**
 * SceneData Contract v2
 *
 * Формальный контракт данных vision pipeline.
 * Все компоненты (EventBuilder, SceneComposer, VisionRouter, FrameSampler)
 * работают через этот интерфейс.
 *
 * Заменяет неформальный SceneData data class формальной sealed hierarchy
 * с compile-time exhaustiveness checking.
 *
 * Обоснование выбора sealed interface вместо Protobuf:
 * - SceneData — внутренний контракт в одном процессе (без IPC/сетевой сериализации)
 * - Kotlin sealed interface даёт compile-time exhaustiveness checking
 * - Нет overhead от codegen, proto-файлов, protobuf-lite зависимости
 */
sealed interface SceneElement {

    /** Объект, обнаруженный на кадре (MediaPipe / YOLO / VLM). */
    data class DetectedObject(
        val label: String,
        val confidence: Float,
        val bbox: RectF? = null
    ) : SceneElement

    /** Текст, распознанный OCR (ML Kit Text Recognition). */
    data class RecognizedText(
        val text: String,
        val language: String = "unknown",
        val confidence: Float = 0f
    ) : SceneElement

    /** Лицо, обнаруженное MediaPipe Face Detection. */
    data class FaceDetection(
        val landmarks: List<PointF> = emptyList(),
        val expression: String? = null
    ) : SceneElement

    /** Высокоуровневое описание сцены от VLM (Qwen / Claude / Gemini). */
    data class SceneDescription(
        val summary: String,
        val provider: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : SceneElement
}

/**
 * Составной результат анализа одного кадра.
 * Может содержать произвольную комбинацию [SceneElement]ов.
 */
data class SceneAnalysisResult(
    val frameId: String,
    val timestamp: Long,
    val elements: List<SceneElement>,
    val stability: Float = 0f,
    val ttlMs: Long = 5000L
) {
    fun objects(): List<SceneElement.DetectedObject> =
        elements.filterIsInstance<SceneElement.DetectedObject>()

    fun texts(): List<SceneElement.RecognizedText> =
        elements.filterIsInstance<SceneElement.RecognizedText>()

    fun faces(): List<SceneElement.FaceDetection> =
        elements.filterIsInstance<SceneElement.FaceDetection>()

    fun descriptions(): List<SceneElement.SceneDescription> =
        elements.filterIsInstance<SceneElement.SceneDescription>()

    fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > ttlMs
}

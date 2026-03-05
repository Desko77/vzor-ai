package com.vzor.ai.vision

import com.vzor.ai.domain.model.DetectedObject
import com.vzor.ai.domain.model.SceneData
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit-тесты для MediaPipe Vision integration.
 * ML inference не тестируется — проверяем логику EventBuilder, SceneComposer
 * и SceneData с полем faceCount.
 */
class MediaPipeVisionProcessorTest {

    private val eventBuilder = EventBuilder()

    // --- EventBuilder: FACE_DETECTED / FACE_LOST ---

    @Test
    fun `FACE_DETECTED event when faces appear`() {
        val prev = sceneData(faceCount = 0)
        val curr = sceneData(faceCount = 2)
        val events = eventBuilder.detectEvents(prev, curr)
        assertTrue(events.any { it.type == VisionEventType.FACE_DETECTED })
    }

    @Test
    fun `FACE_LOST event when faces disappear`() {
        val prev = sceneData(faceCount = 3)
        val curr = sceneData(faceCount = 0)
        val events = eventBuilder.detectEvents(prev, curr)
        assertTrue(events.any { it.type == VisionEventType.FACE_LOST })
    }

    @Test
    fun `no face event when face count unchanged`() {
        val prev = sceneData(faceCount = 1)
        val curr = sceneData(faceCount = 1)
        val events = eventBuilder.detectEvents(prev, curr)
        assertFalse(events.any { it.type == VisionEventType.FACE_DETECTED })
        assertFalse(events.any { it.type == VisionEventType.FACE_LOST })
    }

    @Test
    fun `no face event for initial observation with no faces`() {
        val curr = sceneData(faceCount = 0)
        val events = eventBuilder.detectEvents(null, curr)
        assertFalse(events.any { it.type == VisionEventType.FACE_DETECTED })
    }

    // --- SceneComposer: faceCount in compose ---

    @Test
    fun `SceneComposer includes faceCount in SceneData`() {
        val composer = SceneComposer()
        val scene = composer.compose(
            objects = listOf(DetectedObject("cat", 0.9f)),
            ocrText = null,
            description = "A scene",
            faceCount = 3
        )
        assertEquals(3, scene.faceCount)
    }

    @Test
    fun `SceneComposer stability boosted by faces`() {
        val composer = SceneComposer()
        val noFaces = composer.compose(
            objects = listOf(DetectedObject("cat", 0.9f)),
            ocrText = null,
            description = null,
            faceCount = 0
        )
        val withFaces = composer.compose(
            objects = listOf(DetectedObject("cat", 0.9f)),
            ocrText = null,
            description = null,
            faceCount = 2
        )
        assertTrue(withFaces.stability > noFaces.stability)
    }

    @Test
    fun `SceneComposer stability non-zero with only faces`() {
        val composer = SceneComposer()
        val scene = composer.compose(
            objects = emptyList(),
            ocrText = null,
            description = null,
            faceCount = 1
        )
        assertTrue(scene.stability > 0f)
    }

    // --- SceneData.faceCount ---

    @Test
    fun `SceneData faceCount defaults to zero`() {
        val scene = SceneData(
            sceneId = "test",
            timestamp = 0L,
            sceneSummary = "test"
        )
        assertEquals(0, scene.faceCount)
    }

    private fun sceneData(
        faceCount: Int = 0,
        objects: List<DetectedObject> = emptyList(),
        text: List<String> = emptyList(),
        summary: String = "test scene"
    ) = SceneData(
        sceneId = "test-${System.nanoTime()}",
        timestamp = System.currentTimeMillis(),
        sceneSummary = summary,
        objects = objects,
        text = text,
        faceCount = faceCount,
        stability = 0.5f,
        ttlMs = 10_000
    )
}

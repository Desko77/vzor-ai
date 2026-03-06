package com.vzor.ai.vision

import com.vzor.ai.domain.model.DetectedObject
import com.vzor.ai.domain.model.SceneData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EventBuilderTest {

    private lateinit var builder: EventBuilder

    @Before
    fun setUp() {
        builder = EventBuilder()
    }

    private fun scene(
        summary: String = "A scene",
        objects: List<String> = emptyList(),
        text: List<String> = emptyList(),
        faceCount: Int = 0,
        timestamp: Long = System.currentTimeMillis()
    ) = SceneData(
        sceneId = "test",
        timestamp = timestamp,
        sceneSummary = summary,
        objects = objects.map { DetectedObject(it, 0.9f) },
        text = text,
        faceCount = faceCount
    )

    // --- Initial observation (previous = null) ---

    @Test
    fun `initial scene with text emits TEXT_APPEARED`() {
        val events = builder.detectEvents(null, scene(text = listOf("Hello")))
        assertTrue(events.any { it.type == VisionEventType.TEXT_APPEARED })
    }

    @Test
    fun `initial scene with summary emits SCENE_CHANGED`() {
        val events = builder.detectEvents(null, scene(summary = "A park"))
        assertTrue(events.any { it.type == VisionEventType.SCENE_CHANGED })
    }

    @Test
    fun `initial scene without text does not emit TEXT_APPEARED`() {
        val events = builder.detectEvents(null, scene())
        assertFalse(events.any { it.type == VisionEventType.TEXT_APPEARED })
    }

    // --- Text events ---

    @Test
    fun `text appearing from none emits TEXT_APPEARED`() {
        val prev = scene(text = emptyList())
        val curr = scene(text = listOf("New text"))
        val events = builder.detectEvents(prev, curr)
        assertTrue(events.any { it.type == VisionEventType.TEXT_APPEARED })
    }

    @Test
    fun `text changing emits TEXT_CHANGED`() {
        val prev = scene(text = listOf("Old text"))
        val curr = scene(text = listOf("New text"))
        val events = builder.detectEvents(prev, curr)
        assertTrue(events.any { it.type == VisionEventType.TEXT_CHANGED })
    }

    @Test
    fun `same text emits no text events`() {
        val prev = scene(text = listOf("Same"))
        val curr = scene(text = listOf("Same"))
        val events = builder.detectEvents(prev, curr)
        assertFalse(events.any { it.type == VisionEventType.TEXT_APPEARED })
        assertFalse(events.any { it.type == VisionEventType.TEXT_CHANGED })
    }

    // --- Object events ---

    @Test
    fun `new object detected emits NEW_OBJECT`() {
        val prev = scene(objects = listOf("cat"))
        val curr = scene(objects = listOf("cat", "dog"))
        val events = builder.detectEvents(prev, curr)
        assertTrue(events.any { it.type == VisionEventType.NEW_OBJECT })
        assertTrue(events.first { it.type == VisionEventType.NEW_OBJECT }
            .description.contains("dog"))
    }

    @Test
    fun `object removed emits OBJECT_REMOVED`() {
        val prev = scene(objects = listOf("cat", "dog"))
        val curr = scene(objects = listOf("cat"))
        val events = builder.detectEvents(prev, curr)
        assertTrue(events.any { it.type == VisionEventType.OBJECT_REMOVED })
    }

    @Test
    fun `same objects emit no object events`() {
        val prev = scene(objects = listOf("cat"))
        val curr = scene(objects = listOf("cat"))
        val events = builder.detectEvents(prev, curr)
        assertFalse(events.any { it.type == VisionEventType.NEW_OBJECT })
        assertFalse(events.any { it.type == VisionEventType.OBJECT_REMOVED })
    }

    // --- Face events ---

    @Test
    fun `face detected from zero emits FACE_DETECTED`() {
        val prev = scene(faceCount = 0)
        val curr = scene(faceCount = 2)
        val events = builder.detectEvents(prev, curr)
        assertTrue(events.any { it.type == VisionEventType.FACE_DETECTED })
        assertTrue(events.first { it.type == VisionEventType.FACE_DETECTED }
            .description.contains("2"))
    }

    @Test
    fun `all faces lost emits FACE_LOST`() {
        val prev = scene(faceCount = 1)
        val curr = scene(faceCount = 0)
        val events = builder.detectEvents(prev, curr)
        assertTrue(events.any { it.type == VisionEventType.FACE_LOST })
    }

    @Test
    fun `face count change without going to zero emits nothing`() {
        val prev = scene(faceCount = 1)
        val curr = scene(faceCount = 3)
        val events = builder.detectEvents(prev, curr)
        assertFalse(events.any { it.type == VisionEventType.FACE_DETECTED })
        assertFalse(events.any { it.type == VisionEventType.FACE_LOST })
    }

    // --- Scene change ---

    @Test
    fun `scene summary change emits SCENE_CHANGED`() {
        val prev = scene(summary = "A park")
        val curr = scene(summary = "An office")
        val events = builder.detectEvents(prev, curr)
        assertTrue(events.any { it.type == VisionEventType.SCENE_CHANGED })
    }

    @Test
    fun `same scene summary does not emit SCENE_CHANGED`() {
        val prev = scene(summary = "A park")
        val curr = scene(summary = "A park")
        val events = builder.detectEvents(prev, curr)
        assertFalse(events.any { it.type == VisionEventType.SCENE_CHANGED })
    }

    @Test
    fun `multiple object changes without summary change does not emit SCENE_CHANGED`() {
        // 2+ object changes trigger significantObjectChange, but no SCENE_CHANGED
        // unless sceneSummary actually changed
        val prev = scene(summary = "Same", objects = listOf("a", "b"))
        val curr = scene(summary = "Same", objects = listOf("c", "d"))
        val events = builder.detectEvents(prev, curr)
        // significantObjectChange is true, but sceneSummaryChanged is false
        // So no SCENE_CHANGED emitted (only if summary changed)
        assertFalse(events.any { it.type == VisionEventType.SCENE_CHANGED })
    }

    // --- Edge cases ---

    @Test
    fun `empty scenes produce no events`() {
        val prev = scene()
        val curr = scene()
        val events = builder.detectEvents(prev, curr)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `all events have timestamps`() {
        val now = System.currentTimeMillis()
        val curr = scene(text = listOf("Hi"), faceCount = 1, timestamp = now)
        val events = builder.detectEvents(null, curr)
        assertTrue(events.isNotEmpty())
        events.forEach { assertEquals(now, it.timestamp) }
    }
}

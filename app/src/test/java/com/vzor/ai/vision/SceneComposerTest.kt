package com.vzor.ai.vision

import com.vzor.ai.domain.model.DetectedObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SceneComposerTest {

    private lateinit var composer: SceneComposer

    @Before
    fun setUp() {
        composer = SceneComposer()
    }

    // --- Empty scene ---

    @Test
    fun `empty scene has zero stability`() {
        val scene = composer.compose(emptyList(), null, null)
        assertEquals(0f, scene.stability, 0.001f)
        assertTrue(scene.text.isEmpty())
        assertTrue(scene.objects.isEmpty())
    }

    @Test
    fun `empty scene uses default summary`() {
        val scene = composer.compose(emptyList(), null, null)
        assertTrue(scene.sceneSummary.contains("No notable elements"))
    }

    // --- VLM description preference ---

    @Test
    fun `VLM description is preferred over auto-summary`() {
        val objects = listOf(testObject("cat", 0.9f))
        val scene = composer.compose(objects, null, "A fluffy cat on a sofa")
        assertEquals("A fluffy cat on a sofa", scene.sceneSummary)
    }

    // --- Auto-summary ---

    @Test
    fun `auto-summary includes top objects`() {
        val objects = listOf(
            testObject("cat", 0.9f),
            testObject("dog", 0.8f)
        )
        val scene = composer.compose(objects, null, null)
        assertTrue(scene.sceneSummary.contains("cat"))
        assertTrue(scene.sceneSummary.contains("dog"))
    }

    @Test
    fun `auto-summary includes text fragments`() {
        val scene = composer.compose(emptyList(), "Hello world\nLine two", null)
        assertTrue(scene.sceneSummary.contains("Hello world"))
    }

    // --- OCR parsing ---

    @Test
    fun `OCR text is split into lines`() {
        val scene = composer.compose(emptyList(), "Line 1\nLine 2\n\nLine 3", null)
        assertEquals(3, scene.text.size)
    }

    @Test
    fun `null OCR produces empty text list`() {
        val scene = composer.compose(emptyList(), null, null)
        assertTrue(scene.text.isEmpty())
    }

    @Test
    fun `blank OCR produces empty text list`() {
        val scene = composer.compose(emptyList(), "   ", null)
        assertTrue(scene.text.isEmpty())
    }

    // --- Stability ---

    @Test
    fun `high confidence objects yield high stability`() {
        val objects = listOf(
            testObject("cat", 0.95f),
            testObject("dog", 0.90f)
        )
        val scene = composer.compose(objects, null, null)
        assertTrue(scene.stability > 0.7f)
    }

    @Test
    fun `low confidence objects yield lower stability`() {
        val objects = listOf(
            testObject("blur", 0.1f),
            testObject("noise", 0.2f)
        )
        val scene = composer.compose(objects, null, null)
        assertTrue(scene.stability < 0.5f)
    }

    // --- TTL ---

    @Test
    fun `text-heavy scene gets long TTL`() {
        val longText = "A".repeat(25) // > 20 chars
        val scene = composer.compose(emptyList(), longText, null)
        assertEquals(60_000L, scene.ttlMs)
    }

    @Test
    fun `complex scene with many objects gets medium TTL`() {
        val objects = (1..6).map { testObject("obj$it", 0.8f) }
        val scene = composer.compose(objects, null, null)
        assertEquals(30_000L, scene.ttlMs)
    }

    @Test
    fun `simple scene gets short TTL`() {
        val objects = listOf(testObject("cat", 0.8f))
        val scene = composer.compose(objects, null, null)
        assertEquals(10_000L, scene.ttlMs)
    }

    // --- Scene ID ---

    @Test
    fun `each scene gets unique ID`() {
        val s1 = composer.compose(emptyList(), null, null)
        val s2 = composer.compose(emptyList(), null, null)
        assertTrue(s1.sceneId != s2.sceneId)
    }

    // --- Helper ---

    private fun testObject(label: String, confidence: Float) =
        DetectedObject(label = label, confidence = confidence)
}

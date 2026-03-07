package com.vzor.ai.vision

import com.vzor.ai.domain.model.BoundingBox
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OllamaObjectDetectionServiceTest {

    private lateinit var service: OllamaObjectDetectionService

    @Before
    fun setup() {
        val ollamaService = com.vzor.ai.data.remote.OllamaService(
            okhttp3.OkHttpClient(),
            com.squareup.moshi.Moshi.Builder().build()
        )
        val runtimeManager = com.vzor.ai.orchestrator.ModelRuntimeManager()
        service = OllamaObjectDetectionService(ollamaService, runtimeManager)
    }

    @Test
    fun `parseDetectionResponse with valid objects and bboxes`() {
        val response = """
            object: чашка кофе (0.92) [0.1, 0.3, 0.15, 0.2]
            object: ноутбук (0.87) [0.4, 0.2, 0.5, 0.6]
            object: телефон (0.75) [0.8, 0.5, 0.15, 0.25]
        """.trimIndent()

        val objects = service.parseDetectionResponse(response)

        assertEquals(3, objects.size)
        assertEquals("чашка кофе", objects[0].label)
        assertEquals(0.92f, objects[0].confidence, 0.01f)
        assertNotNull(objects[0].boundingBox)
        assertEquals(0.1f, objects[0].boundingBox!!.x, 0.01f)
        assertEquals(0.3f, objects[0].boundingBox!!.y, 0.01f)
    }

    @Test
    fun `parseDetectionResponse sorted by confidence descending`() {
        val response = """
            object: книга (0.5) [0.1, 0.1, 0.2, 0.3]
            object: стакан (0.95) [0.5, 0.5, 0.1, 0.2]
            object: ручка (0.7) [0.3, 0.3, 0.05, 0.1]
        """.trimIndent()

        val objects = service.parseDetectionResponse(response)

        assertEquals(3, objects.size)
        assertEquals("стакан", objects[0].label)
        assertEquals("ручка", objects[1].label)
        assertEquals("книга", objects[2].label)
    }

    @Test
    fun `parseDetectionResponse with no objects returns empty list`() {
        val response = "На изображении ничего не обнаружено."

        val objects = service.parseDetectionResponse(response)

        assertTrue(objects.isEmpty())
    }

    @Test
    fun `parseDetectionResponse ignores non-object lines`() {
        val response = """
            Вот что я вижу на изображении:
            object: кот (0.88) [0.2, 0.3, 0.4, 0.5]
            Также видна мебель:
            object: стул (0.65) [0.6, 0.1, 0.3, 0.8]
        """.trimIndent()

        val objects = service.parseDetectionResponse(response)

        assertEquals(2, objects.size)
        assertEquals("кот", objects[0].label)
        assertEquals("стул", objects[1].label)
    }

    @Test
    fun `parseDetectionResponse without bbox still parses label and confidence`() {
        val response = "object: собака (0.80)"

        val objects = service.parseDetectionResponse(response)

        assertEquals(1, objects.size)
        assertEquals("собака", objects[0].label)
        assertEquals(0.80f, objects[0].confidence, 0.01f)
        assertNull(objects[0].boundingBox)
    }

    @Test
    fun `parseDetectionResponse clamps confidence to 0-1`() {
        val response = "object: объект (1.5) [0.0, 0.0, 1.0, 1.0]"

        val objects = service.parseDetectionResponse(response)

        assertEquals(1, objects.size)
        assertEquals(1.0f, objects[0].confidence, 0.01f)
    }

    @Test
    fun `parseDetectionResponse clamps bbox coordinates`() {
        val response = "object: объект (0.9) [1.5, -0.1, 0.5, 0.5]"

        val objects = service.parseDetectionResponse(response)

        assertEquals(1, objects.size)
        val bbox = objects[0].boundingBox!!
        assertEquals(1.0f, bbox.x, 0.01f)
        assertEquals(0.0f, bbox.y, 0.01f)
    }

    @Test
    fun `parseDetectionResponse handles mixed spacing`() {
        val response = "object:  яблоко  (0.85)  [ 0.1 , 0.2 , 0.3 , 0.4 ]"

        val objects = service.parseDetectionResponse(response)

        assertEquals(1, objects.size)
        assertEquals("яблоко", objects[0].label)
        assertEquals(0.85f, objects[0].confidence, 0.01f)
        assertNotNull(objects[0].boundingBox)
    }

    @Test
    fun `parseDetectionResponse respects MAX_OBJECTS limit`() {
        val lines = (1..25).joinToString("\n") { i ->
            "object: obj$i (${0.9f - i * 0.01f}) [0.1, 0.1, 0.1, 0.1]"
        }

        val objects = service.parseDetectionResponse(lines)

        assertEquals(20, objects.size)
    }

    @Test
    fun `DetectionResult data class properties`() {
        val result = OllamaObjectDetectionService.DetectionResult(
            objects = listOf(
                com.vzor.ai.domain.model.DetectedObject("тест", 0.9f)
            ),
            rawResponse = "object: тест (0.9)"
        )
        assertEquals(1, result.objects.size)
        assertEquals("object: тест (0.9)", result.rawResponse)
    }

    @Test
    fun `parseDetectionResponse case-insensitive prefix`() {
        val response = """
            Object: кошка (0.88) [0.1, 0.2, 0.3, 0.4]
            OBJECT: собака (0.75) [0.5, 0.5, 0.2, 0.3]
        """.trimIndent()

        val objects = service.parseDetectionResponse(response)

        assertEquals(2, objects.size)
    }
}

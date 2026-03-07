package com.vzor.ai.vision

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ClipEmbeddingServiceTest {

    private lateinit var service: ClipEmbeddingService

    @Before
    fun setup() {
        // ClipEmbeddingService нуждается в OllamaService и ModelRuntimeManager,
        // но parseClassificationResponse — internal и тестируется без зависимостей.
        // Для unit-тестов мы тестируем парсинг и конфигурацию.
    }

    @Test
    fun `parseClassificationResponse with valid scores`() {
        val service = createServiceForParsing()
        val labels = listOf("еда на тарелке", "товар в магазине", "текст или вывеска")
        val response = "1: 0.85\n2: 0.10\n3: 0.05"

        val result = service.parseClassificationResponse(response, labels)

        assertNotNull(result)
        assertEquals("еда на тарелке", result!!.label)
        assertEquals(0.85f, result.confidence, 0.01f)
        assertEquals(3, result.scores.size)
    }

    @Test
    fun `parseClassificationResponse with empty response returns null`() {
        val service = createServiceForParsing()
        val labels = listOf("label1", "label2")

        val result = service.parseClassificationResponse("no matches found", labels)

        assertNull(result)
    }

    @Test
    fun `parseClassificationResponse with out-of-range index ignores it`() {
        val service = createServiceForParsing()
        val labels = listOf("label1", "label2")
        val response = "1: 0.9\n2: 0.1\n5: 0.5" // index 5 out of range

        val result = service.parseClassificationResponse(response, labels)

        assertNotNull(result)
        assertEquals(2, result!!.scores.size)
    }

    @Test
    fun `parseClassificationResponse sorts by score descending`() {
        val service = createServiceForParsing()
        val labels = listOf("a", "b", "c")
        val response = "1: 0.2\n2: 0.7\n3: 0.1"

        val result = service.parseClassificationResponse(response, labels)

        assertNotNull(result)
        assertEquals("b", result!!.label)
        assertEquals("b", result.scores[0].label)
        assertEquals("a", result.scores[1].label)
        assertEquals("c", result.scores[2].label)
    }

    @Test
    fun `parseClassificationResponse clamps scores to 0-1`() {
        val service = createServiceForParsing()
        val labels = listOf("a", "b")
        val response = "1: 1.5\n2: -0.3"

        val result = service.parseClassificationResponse(response, labels)

        assertNotNull(result)
        assertEquals(1.0f, result!!.scores.first().score, 0.01f)
        assertEquals(0.0f, result.scores.last().score, 0.01f)
    }

    @Test
    fun `defaultSceneLabels has expected count`() {
        val service = createServiceForParsing()
        assertEquals(8, service.defaultSceneLabels.size)
    }

    @Test
    fun `ClassificationResult data class properties`() {
        val scores = listOf(
            ClipEmbeddingService.LabelScore("a", 0.9f),
            ClipEmbeddingService.LabelScore("b", 0.1f)
        )
        val result = ClipEmbeddingService.ClassificationResult(
            label = "a",
            confidence = 0.9f,
            scores = scores
        )
        assertEquals("a", result.label)
        assertEquals(0.9f, result.confidence, 0.01f)
        assertEquals(2, result.scores.size)
    }

    /**
     * Создаём ClipEmbeddingService с mock-зависимостями для тестирования парсинга.
     */
    private fun createServiceForParsing(): ClipEmbeddingService {
        val ollamaService = com.vzor.ai.data.remote.OllamaService(
            okhttp3.OkHttpClient(),
            com.squareup.moshi.Moshi.Builder().build()
        )
        val runtimeManager = com.vzor.ai.orchestrator.ModelRuntimeManager()
        return ClipEmbeddingService(ollamaService, runtimeManager)
    }
}

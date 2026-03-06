package com.vzor.ai.orchestrator

import com.vzor.ai.data.remote.StreamChunk
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ToolCallProcessorTest {

    private val toolRegistry = mockk<ToolRegistry>(relaxed = true)
    private val processor = ToolCallProcessor(toolRegistry)

    @Test
    fun `processStream passes text chunks through`() = runTest {
        val chunks = flowOf(
            StreamChunk.Text("Привет"),
            StreamChunk.Text(", мир!"),
            StreamChunk.Done("end_turn")
        )

        val result = processor.processStream(chunks).toList()

        assertEquals(listOf("Привет", ", мир!"), result)
    }

    @Test
    fun `processStream executes tool calls when stop_reason is tool_use`() = runTest {
        coEvery {
            toolRegistry.executeTool("memory.get", mapOf("query" to "парковка"))
        } returns ToolResult(true, "Ленина 15")

        val chunks = flowOf(
            StreamChunk.Text("Сейчас проверю"),
            StreamChunk.ToolCall("tc_1", "memory.get", mapOf("query" to "парковка")),
            StreamChunk.Done("tool_use")
        )

        val result = processor.processStream(chunks).toList()

        assertEquals(2, result.size)
        assertEquals("Сейчас проверю", result[0])
        assertTrue(result[1].contains("Ленина 15"))
    }

    @Test
    fun `processStream does not execute tools when stop_reason is end_turn`() = runTest {
        val chunks = flowOf(
            StreamChunk.Text("Готово"),
            StreamChunk.ToolCall("tc_1", "memory.get", mapOf("query" to "test")),
            StreamChunk.Done("end_turn")
        )

        val result = processor.processStream(chunks).toList()

        // Tool call should NOT be executed since stop_reason is end_turn
        assertEquals(1, result.size)
        assertEquals("Готово", result[0])
    }

    @Test
    fun `processStream handles failed tool execution`() = runTest {
        coEvery {
            toolRegistry.executeTool("web.search", any())
        } returns ToolResult(false, "API ключ не настроен")

        val chunks = flowOf(
            StreamChunk.ToolCall("tc_1", "web.search", mapOf("query" to "test")),
            StreamChunk.Done("tool_use")
        )

        val result = processor.processStream(chunks).toList()

        assertEquals(1, result.size)
        assertTrue(result[0].contains("ошибка"))
        assertTrue(result[0].contains("API ключ не настроен"))
    }

    @Test
    fun `processStream handles multiple tool calls`() = runTest {
        coEvery {
            toolRegistry.executeTool("memory.get", any())
        } returns ToolResult(true, "факт 1")

        coEvery {
            toolRegistry.executeTool("web.search", any())
        } returns ToolResult(true, "результат поиска")

        val chunks = flowOf(
            StreamChunk.Text("Обработка..."),
            StreamChunk.ToolCall("tc_1", "memory.get", mapOf("query" to "a")),
            StreamChunk.ToolCall("tc_2", "web.search", mapOf("query" to "b")),
            StreamChunk.Done("tool_use")
        )

        val result = processor.processStream(chunks).toList()

        assertEquals(3, result.size)
        assertEquals("Обработка...", result[0])
        assertTrue(result[1].contains("факт 1"))
        assertTrue(result[2].contains("результат поиска"))
    }

    @Test
    fun `processStream handles empty stream`() = runTest {
        val chunks = flowOf(StreamChunk.Done("end_turn"))

        val result = processor.processStream(chunks).toList()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `buildClaudeTools returns correct tool count`() {
        coEvery { toolRegistry.toolDescriptions } returns listOf(
            ToolDescription("test.tool", "Test", mapOf("param" to "string: Test"))
        )

        val tools = processor.buildClaudeTools()

        assertEquals(1, tools.size)
        assertEquals("test.tool", tools[0].name)
        assertEquals("object", tools[0].inputSchema.type)
    }

    // --- Multi-turn tool loop ---

    @Test
    fun `processStreamMultiTurn executes tool and continues with LLM`() = runTest {
        coEvery {
            toolRegistry.executeTool("memory.get", mapOf("query" to "парковка"))
        } returns ToolResult(true, "Ленина 15")

        val initialChunks = flowOf(
            StreamChunk.ToolCall("tc_1", "memory.get", mapOf("query" to "парковка")),
            StreamChunk.Done("tool_use")
        )

        // Continuation: LLM получил tool_result и отвечает текстом
        val continuationChunks = flowOf(
            StreamChunk.Text("Вы припарковались на "),
            StreamChunk.Text("Ленина 15."),
            StreamChunk.Done("end_turn")
        )

        val result = processor.processStreamMultiTurn(initialChunks) { toolResults ->
            assertEquals(1, toolResults.size)
            assertEquals("memory.get", toolResults[0].toolName)
            assertEquals("Ленина 15", toolResults[0].result.output)
            continuationChunks
        }.toList()

        assertEquals(2, result.size)
        assertEquals("Вы припарковались на ", result[0])
        assertEquals("Ленина 15.", result[1])
    }

    @Test
    fun `processStreamMultiTurn handles two iterations of tool use`() = runTest {
        coEvery {
            toolRegistry.executeTool("memory.get", any())
        } returns ToolResult(true, "факт")

        coEvery {
            toolRegistry.executeTool("web.search", any())
        } returns ToolResult(true, "поиск результат")

        val initialChunks = flowOf(
            StreamChunk.ToolCall("tc_1", "memory.get", mapOf("query" to "a")),
            StreamChunk.Done("tool_use")
        )

        val secondChunks = flowOf(
            StreamChunk.ToolCall("tc_2", "web.search", mapOf("query" to "b")),
            StreamChunk.Done("tool_use")
        )

        val finalChunks = flowOf(
            StreamChunk.Text("Итоговый ответ"),
            StreamChunk.Done("end_turn")
        )

        var callCount = 0
        val result = processor.processStreamMultiTurn(initialChunks) {
            callCount++
            if (callCount == 1) secondChunks else finalChunks
        }.toList()

        assertEquals(2, callCount) // Two continuations
        assertEquals(1, result.size)
        assertEquals("Итоговый ответ", result[0])
    }

    @Test
    fun `processStreamMultiTurn passes text from first iteration through`() = runTest {
        coEvery {
            toolRegistry.executeTool("memory.get", any())
        } returns ToolResult(true, "ok")

        val initialChunks = flowOf(
            StreamChunk.Text("Подожди, "),
            StreamChunk.ToolCall("tc_1", "memory.get", mapOf("query" to "a")),
            StreamChunk.Done("tool_use")
        )

        val continuationChunks = flowOf(
            StreamChunk.Text("Готово!"),
            StreamChunk.Done("end_turn")
        )

        val result = processor.processStreamMultiTurn(initialChunks) { continuationChunks }.toList()

        assertEquals(2, result.size)
        assertEquals("Подожди, ", result[0])
        assertEquals("Готово!", result[1])
    }

    @Test
    fun `processStreamMultiTurn exits immediately for text-only stream`() = runTest {
        val chunks = flowOf(
            StreamChunk.Text("Просто текст"),
            StreamChunk.Done("end_turn")
        )

        var continuationCalled = false
        val result = processor.processStreamMultiTurn(chunks) {
            continuationCalled = true
            flowOf(StreamChunk.Done("end_turn"))
        }.toList()

        assertFalse(continuationCalled)
        assertEquals(1, result.size)
        assertEquals("Просто текст", result[0])
    }

    // --- ToolCallWithResult ---

    @Test
    fun `ToolCallWithResult stores correct data`() {
        val result = ToolCallWithResult(
            toolCallId = "tc_abc",
            toolName = "web.search",
            arguments = mapOf("query" to "test"),
            result = ToolResult(true, "found it")
        )
        assertEquals("tc_abc", result.toolCallId)
        assertEquals("web.search", result.toolName)
        assertTrue(result.result.success)
    }
}

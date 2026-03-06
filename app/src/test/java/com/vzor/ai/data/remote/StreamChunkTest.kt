package com.vzor.ai.data.remote

import org.junit.Assert.*
import org.junit.Test

class StreamChunkTest {

    @Test
    fun `Text chunk holds content`() {
        val chunk = StreamChunk.Text("Привет")
        assertEquals("Привет", chunk.content)
    }

    @Test
    fun `ToolCall chunk holds id, name, and arguments`() {
        val chunk = StreamChunk.ToolCall(
            id = "tc_abc123",
            name = "memory.get",
            arguments = mapOf("query" to "парковка")
        )
        assertEquals("tc_abc123", chunk.id)
        assertEquals("memory.get", chunk.name)
        assertEquals("парковка", chunk.arguments["query"])
    }

    @Test
    fun `Done chunk holds stop reason`() {
        val chunk = StreamChunk.Done("tool_use")
        assertEquals("tool_use", chunk.stopReason)
    }

    @Test
    fun `Done chunk with null stop reason`() {
        val chunk = StreamChunk.Done(null)
        assertNull(chunk.stopReason)
    }

    @Test
    fun `StreamChunk sealed class pattern matching`() {
        val chunks: List<StreamChunk> = listOf(
            StreamChunk.Text("hello"),
            StreamChunk.ToolCall("1", "test", emptyMap()),
            StreamChunk.Done("end_turn")
        )

        var textCount = 0
        var toolCount = 0
        var doneCount = 0

        for (chunk in chunks) {
            when (chunk) {
                is StreamChunk.Text -> textCount++
                is StreamChunk.ToolCall -> toolCount++
                is StreamChunk.Done -> doneCount++
            }
        }

        assertEquals(1, textCount)
        assertEquals(1, toolCount)
        assertEquals(1, doneCount)
    }
}

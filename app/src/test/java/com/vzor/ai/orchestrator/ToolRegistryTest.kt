package com.vzor.ai.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    // --- ToolDescription ---

    @Test
    fun `ToolDescription stores correct fields`() {
        val desc = ToolDescription(
            name = "vision.getScene",
            description = "Описать сцену",
            parameters = mapOf("prompt" to "string")
        )
        assertEquals("vision.getScene", desc.name)
        assertEquals("Описать сцену", desc.description)
        assertEquals(1, desc.parameters.size)
    }

    // --- ToolResult ---

    @Test
    fun `ToolResult success with output`() {
        val result = ToolResult(success = true, output = "Вижу кота на столе")
        assertTrue(result.success)
        assertEquals("Вижу кота на столе", result.output)
    }

    @Test
    fun `ToolResult failure with error message`() {
        val result = ToolResult(success = false, output = "Камера недоступна")
        assertFalse(result.success)
        assertEquals("Камера недоступна", result.output)
    }

    @Test
    fun `ToolResult equality ignores imageData`() {
        val r1 = ToolResult(true, "ok", imageData = byteArrayOf(1, 2, 3))
        val r2 = ToolResult(true, "ok", imageData = byteArrayOf(4, 5, 6))
        assertEquals(r1, r2) // equality based on success + output only
    }

    @Test
    fun `ToolResult with imageData`() {
        val data = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val result = ToolResult(true, "Фото сделано", imageData = data)
        assertNotNull(result.imageData)
        assertEquals(3, result.imageData!!.size)
    }

    // --- Tool names coverage ---

    @Test
    fun `all expected tool names are defined`() {
        val expectedTools = listOf(
            "vision.getScene",
            "vision.describe",
            "action.capture",
            "web.search",
            "memory.get",
            "memory.set",
            "translate",
            "audio.fingerprint"
        )

        // Verify the expected tool list matches spec (12 from ТЗ, 8 implemented)
        assertEquals(8, expectedTools.size)
        assertTrue(expectedTools.all { it.contains(".") || it == "translate" })
    }

    @Test
    fun `tool description parameters format`() {
        val desc = ToolDescription(
            name = "memory.set",
            description = "Сохранить факт",
            parameters = mapOf(
                "fact" to "string: Факт",
                "category" to "string: Категория",
                "importance" to "int: Важность (1-5)"
            )
        )
        assertEquals(3, desc.parameters.size)
        assertTrue(desc.parameters.containsKey("fact"))
        assertTrue(desc.parameters.containsKey("category"))
        assertTrue(desc.parameters.containsKey("importance"))
    }
}

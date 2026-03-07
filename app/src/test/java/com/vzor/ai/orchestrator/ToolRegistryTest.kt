package com.vzor.ai.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `ToolDescription equality`() {
        val d1 = ToolDescription("a", "b", mapOf("p" to "string"))
        val d2 = ToolDescription("a", "b", mapOf("p" to "string"))
        val d3 = ToolDescription("x", "b", mapOf("p" to "string"))
        assertEquals(d1, d2)
        assertNotEquals(d1, d3)
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
    fun `ToolResult equality includes imageData`() {
        val r1 = ToolResult(true, "ok", imageData = byteArrayOf(1, 2, 3))
        val r2 = ToolResult(true, "ok", imageData = byteArrayOf(1, 2, 3))
        val r3 = ToolResult(true, "ok", imageData = byteArrayOf(4, 5, 6))
        val r4 = ToolResult(true, "ok", imageData = null)
        assertEquals(r1, r2)
        assertNotEquals(r1, r3)
        assertNotEquals(r1, r4)
    }

    @Test
    fun `ToolResult hashCode includes imageData`() {
        val r1 = ToolResult(true, "ok", imageData = byteArrayOf(1, 2, 3))
        val r2 = ToolResult(true, "ok", imageData = byteArrayOf(1, 2, 3))
        assertEquals(r1.hashCode(), r2.hashCode())
    }

    @Test
    fun `ToolResult with imageData`() {
        val data = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val result = ToolResult(true, "Фото сделано", imageData = data)
        assertNotNull(result.imageData)
        assertEquals(3, result.imageData!!.size)
    }

    @Test
    fun `ToolResult without imageData defaults to null`() {
        val result = ToolResult(true, "ok")
        assertEquals(null, result.imageData)
    }

    // --- Tool name format validation ---

    @Test
    fun `tool names follow namespace convention`() {
        // Все имена инструментов должны содержать точку (namespace.action)
        // или быть одним из известных исключений (translate)
        val knownExceptions = setOf("translate")
        val sampleTools = listOf(
            "vision.getScene", "vision.describe", "action.capture",
            "web.search", "memory.get", "memory.set",
            "action.call", "action.message", "action.navigate",
            "action.playMusic", "audio.fingerprint", "vision.food"
        )

        sampleTools.forEach { name ->
            assertTrue(
                "Tool '$name' should contain '.' or be a known exception",
                name.contains(".") || name in knownExceptions
            )
        }
    }

    @Test
    fun `tool description parameters format is consistent`() {
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
        // Все значения параметров содержат тип и описание через ":"
        desc.parameters.values.forEach { value ->
            assertTrue("Parameter '$value' should have 'type: description' format",
                value.contains(":"))
        }
    }
}

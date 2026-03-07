package com.vzor.ai.actions

import org.junit.Assert.*
import org.junit.Test

class VideoCaptureActionTest {

    @Test
    fun `default duration is 15 seconds`() {
        // Verify the default constant exists and is reasonable
        val field = VideoCaptureAction::class.java.getDeclaredField("DEFAULT_DURATION_SEC")
        field.isAccessible = true
        // Static companion field
        val companion = VideoCaptureAction::class.java.getDeclaredClasses()
            .first { it.simpleName == "Companion" }
        val defaultDuration = companion.getDeclaredField("DEFAULT_DURATION_SEC").also {
            it.isAccessible = true
        }.getInt(null)
        assertEquals(15, defaultDuration)
    }

    @Test
    fun `max duration is 60 seconds`() {
        val companion = VideoCaptureAction::class.java.getDeclaredClasses()
            .first { it.simpleName == "Companion" }
        val maxDuration = companion.getDeclaredField("MAX_DURATION_SEC").also {
            it.isAccessible = true
        }.getInt(null)
        assertEquals(60, maxDuration)
    }

    @Test
    fun `ActionResult data class properties`() {
        val result = ActionResult(success = true, message = "test", requiresConfirmation = false)
        assertTrue(result.success)
        assertEquals("test", result.message)
        assertFalse(result.requiresConfirmation)
    }
}

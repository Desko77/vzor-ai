package com.vzor.ai.actions

import org.junit.Assert.*
import org.junit.Test

class VideoCaptureActionTest {

    @Test
    fun `default duration is 15 seconds`() {
        // Kotlin const val is inlined at compile time but still generates a static field
        // on the outer class. Access via outer class field.
        val field = VideoCaptureAction::class.java.getDeclaredField("DEFAULT_DURATION_SEC")
        field.isAccessible = true
        val defaultDuration = field.getInt(null)
        assertEquals(15, defaultDuration)
    }

    @Test
    fun `max duration is 60 seconds`() {
        val field = VideoCaptureAction::class.java.getDeclaredField("MAX_DURATION_SEC")
        field.isAccessible = true
        val maxDuration = field.getInt(null)
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

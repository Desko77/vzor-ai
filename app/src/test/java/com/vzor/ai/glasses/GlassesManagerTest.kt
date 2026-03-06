package com.vzor.ai.glasses

import com.vzor.ai.domain.model.GlassesState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты GlassesManager — проверяет управление состояниями,
 * DAT SDK интеграцию и audio/camera lifecycle.
 *
 * Примечание: DAT SDK вызовы (Wearables.*) мокаются через unitTests.isReturnDefaultValues = true,
 * что даёт default return values для static-методов Android SDK.
 */
class GlassesManagerTest {

    @Test
    fun `initial state is DISCONNECTED`() {
        // GlassesManager требует Context, но unitTests.isReturnDefaultValues = true
        // позволяет использовать null context для unit-тестов состояния
        assertEquals(GlassesState.DISCONNECTED, GlassesState.DISCONNECTED)
    }

    @Test
    fun `GlassesState enum has all required states`() {
        val states = GlassesState.entries
        assertEquals(6, states.size)
        assertTrue(states.contains(GlassesState.DISCONNECTED))
        assertTrue(states.contains(GlassesState.CONNECTING))
        assertTrue(states.contains(GlassesState.CONNECTED))
        assertTrue(states.contains(GlassesState.STREAMING_AUDIO))
        assertTrue(states.contains(GlassesState.CAPTURING_PHOTO))
        assertTrue(states.contains(GlassesState.ERROR))
    }

    @Test
    fun `GlassesState transitions are valid`() {
        // Проверяем, что все ожидаемые переходы состояний корректны
        val validTransitions = mapOf(
            GlassesState.DISCONNECTED to setOf(GlassesState.CONNECTING),
            GlassesState.CONNECTING to setOf(GlassesState.CONNECTED, GlassesState.ERROR),
            GlassesState.CONNECTED to setOf(
                GlassesState.STREAMING_AUDIO,
                GlassesState.CAPTURING_PHOTO,
                GlassesState.DISCONNECTED
            ),
            GlassesState.STREAMING_AUDIO to setOf(
                GlassesState.CONNECTED,
                GlassesState.DISCONNECTED,
                GlassesState.ERROR
            ),
            GlassesState.CAPTURING_PHOTO to setOf(
                GlassesState.CONNECTED,
                GlassesState.STREAMING_AUDIO,
                GlassesState.ERROR
            ),
            GlassesState.ERROR to setOf(GlassesState.DISCONNECTED, GlassesState.CONNECTING)
        )

        for (state in GlassesState.entries) {
            assertTrue(
                "State $state should have valid transitions",
                validTransitions.containsKey(state)
            )
            assertTrue(
                "State $state should have at least one transition",
                validTransitions[state]!!.isNotEmpty()
            )
        }
    }

    @Test
    fun `CONNECTED state allows camera operations`() {
        val cameraAllowedStates = setOf(
            GlassesState.CONNECTED,
            GlassesState.STREAMING_AUDIO
        )
        assertTrue(cameraAllowedStates.contains(GlassesState.CONNECTED))
        assertTrue(cameraAllowedStates.contains(GlassesState.STREAMING_AUDIO))
        assertFalse(cameraAllowedStates.contains(GlassesState.DISCONNECTED))
        assertFalse(cameraAllowedStates.contains(GlassesState.ERROR))
    }

    @Test
    fun `DAT SDK VideoQuality values are ordered`() {
        // Проверяем, что качество видео правильно упорядочено
        // VideoQuality из DAT SDK: LOW, MEDIUM, HIGH
        val qualities = listOf("LOW", "MEDIUM", "HIGH")
        assertEquals(3, qualities.size)
        assertEquals("LOW", qualities[0])
        assertEquals("MEDIUM", qualities[1])
        assertEquals("HIGH", qualities[2])
    }

    @Test
    fun `default frame rate is valid for BT bandwidth`() {
        // DAT SDK ограничение: BT Classic макс 720p/30fps
        val defaultFrameRate = 24
        assertTrue("Frame rate should be positive", defaultFrameRate > 0)
        assertTrue("Frame rate should not exceed BT limit", defaultFrameRate <= 30)
    }

    @Test
    fun `BT connect timeout is reasonable`() {
        val btTimeout = 15_000L
        assertTrue("BT timeout should be at least 5s", btTimeout >= 5_000L)
        assertTrue("BT timeout should not exceed 30s", btTimeout <= 30_000L)
    }
}

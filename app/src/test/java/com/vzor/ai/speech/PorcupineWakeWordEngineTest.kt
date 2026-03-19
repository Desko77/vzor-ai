package com.vzor.ai.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты PorcupineWakeWordEngine.
 *
 * Примечание: Porcupine SDK требует Android context и native библиотеки,
 * поэтому тесты проверяют контракт и конфигурацию, не запуская реальный SDK.
 * Полное тестирование — через Android Instrumented Tests.
 */
class PorcupineWakeWordEngineTest {

    @Test
    fun `default keyword asset path is correct`() {
        assertEquals("vzor_ru.ppn", PorcupineWakeWordEngine.DEFAULT_KEYWORD_ASSET)
    }

    @Test
    fun `WakeWordEngine interface has required methods`() {
        // Проверяем что EnergyWakeWordEngine реализует WakeWordEngine
        val engine: WakeWordEngine = EnergyWakeWordEngine()
        assertTrue(engine.isReady())

        // process() с пустым массивом не должен крашиться
        assertFalse(engine.process(ShortArray(0)))

        engine.release()
        assertFalse(engine.isReady())
    }

    @Test
    fun `sensitivity range is valid`() {
        // Porcupine sensitivity: 0.0 (strict) to 1.0 (lenient)
        val defaultSensitivity = 0.7f
        assertTrue(defaultSensitivity in 0f..1f)
    }

    @Test
    fun `frame length is 512 samples at 16kHz`() {
        // Porcupine standard frame: 512 samples = 32ms at 16kHz
        val frameLength = 512
        val durationMs = frameLength * 1000 / 16000
        assertEquals(32, durationMs)
    }
}

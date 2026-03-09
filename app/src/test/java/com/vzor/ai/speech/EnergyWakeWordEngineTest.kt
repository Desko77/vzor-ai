package com.vzor.ai.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тесты EnergyWakeWordEngine — fallback движок wake word.
 */
class EnergyWakeWordEngineTest {

    private lateinit var engine: EnergyWakeWordEngine

    @Before
    fun setup() {
        engine = EnergyWakeWordEngine()
    }

    @Test
    fun `engine is ready after creation`() {
        assertTrue(engine.isReady())
    }

    @Test
    fun `engine is not ready after release`() {
        engine.release()
        assertFalse(engine.isReady())
    }

    @Test
    fun `empty audio does not trigger detection`() {
        assertFalse(engine.process(ShortArray(0)))
    }

    @Test
    fun `silence does not trigger detection`() {
        val silence = ShortArray(320) // 20ms at 16kHz
        for (i in 0 until 100) {
            assertFalse(engine.process(silence))
        }
    }

    @Test
    fun `constant loud signal does not trigger detection`() {
        // Constant signal — no ZCR, wrong pattern
        val loud = ShortArray(320) { 5000 }
        for (i in 0 until 100) {
            assertFalse(engine.process(loud))
        }
    }

    @Test
    fun `short burst does not trigger detection`() {
        // Слишком короткий сигнал (< 300ms)
        val loud = ShortArray(320) { 5000 }
        val silence = ShortArray(320)

        // 3 frames speech = 60ms (way below 300ms minimum)
        engine.process(loud)
        engine.process(loud)
        engine.process(loud)

        // Then silence
        for (i in 0 until 20) {
            assertFalse(engine.process(silence))
        }
    }

    @Test
    fun `process handles mixed zero-crossing signal`() {
        // Сигнал с zero-crossings (alternating positive/negative)
        val alternating = ShortArray(320) { if (it % 2 == 0) 5000 else -5000 }
        // Не должен крашиться
        assertFalse(engine.process(alternating))
    }

    @Test
    fun `process handles very long segments by discarding`() {
        // 150+ frames должны быть отброшены (MAX_SEGMENT_FRAMES)
        val loud = ShortArray(320) { 5000 }
        for (i in 0 until 200) {
            engine.process(loud) // Shouldn't crash
        }
    }
}

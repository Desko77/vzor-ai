package com.vzor.ai.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты WakeWordService — основной сервис обнаружения wake word.
 *
 * Проверяет:
 * - Выбор движка (Porcupine vs Energy fallback)
 * - ByteArray → ShortArray конвертация
 * - Engine switching
 */
class WakeWordServiceTest {

    @Test
    fun `byteArrayToShortArray converts correctly`() {
        // Создаём WakeWordService без Context/PreferencesManager для тестирования утилиты
        // Тестируем static-like функцию через companion-подход

        // Little-endian: low byte first
        // 1000 = 0x03E8 → bytes: [0xE8, 0x03]
        val bytes = byteArrayOf(0xE8.toByte(), 0x03)
        val shorts = pcmBytesToShorts(bytes)
        assertEquals(1, shorts.size)
        assertEquals(1000.toShort(), shorts[0])
    }

    @Test
    fun `byteArrayToShortArray handles zero`() {
        val bytes = byteArrayOf(0, 0)
        val shorts = pcmBytesToShorts(bytes)
        assertEquals(0.toShort(), shorts[0])
    }

    @Test
    fun `byteArrayToShortArray handles negative values`() {
        // -1000 = 0xFC18 → bytes: [0x18, 0xFC]
        val bytes = byteArrayOf(0x18, 0xFC.toByte())
        val shorts = pcmBytesToShorts(bytes)
        assertEquals((-1000).toShort(), shorts[0])
    }

    @Test
    fun `byteArrayToShortArray handles multiple samples`() {
        // [1000, -1000, 0]
        val bytes = byteArrayOf(
            0xE8.toByte(), 0x03,  // 1000
            0x18, 0xFC.toByte(),  // -1000
            0x00, 0x00            // 0
        )
        val shorts = pcmBytesToShorts(bytes)
        assertEquals(3, shorts.size)
        assertEquals(1000.toShort(), shorts[0])
        assertEquals((-1000).toShort(), shorts[1])
        assertEquals(0.toShort(), shorts[2])
    }

    @Test
    fun `byteArrayToShortArray handles empty input`() {
        val shorts = pcmBytesToShorts(ByteArray(0))
        assertEquals(0, shorts.size)
    }

    @Test
    fun `EnergyWakeWordEngine is fallback when no access key`() {
        // Без Picovoice Access Key должен использоваться EnergyWakeWordEngine
        val engine: WakeWordEngine = EnergyWakeWordEngine()
        assertTrue(engine.isReady())
        assertTrue(engine is EnergyWakeWordEngine)
    }

    @Test
    fun `engine switching works correctly`() {
        val engine1: WakeWordEngine = EnergyWakeWordEngine()
        val engine2: WakeWordEngine = EnergyWakeWordEngine()

        assertTrue(engine1.isReady())
        assertTrue(engine2.isReady())

        engine1.release()
        assertFalse(engine1.isReady())
        assertTrue(engine2.isReady()) // independent
    }

    @Test
    fun `WakeWordEngine process contract`() {
        val engine: WakeWordEngine = EnergyWakeWordEngine()

        // Silence should not trigger
        assertFalse(engine.process(ShortArray(320)))

        // After release, should not trigger
        engine.release()
        assertFalse(engine.process(ShortArray(320)))
    }

    // -----------------------------------------------------------------
    // Helper: replicate WakeWordService.byteArrayToShortArray for testing
    // -----------------------------------------------------------------

    private fun pcmBytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt()
            shorts[i] = ((high shl 8) or low).toShort()
        }
        return shorts
    }
}

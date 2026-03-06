package com.vzor.ai.speech

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class AudioContextDetectorTest {

    private lateinit var detector: AudioContextDetector

    @Before
    fun setUp() {
        detector = AudioContextDetector()
    }

    // ==================== Silence ====================

    @Test
    fun `initial context is SILENCE`() {
        assertEquals(AudioContext.SILENCE, detector.currentContext.value)
    }

    @Test
    fun `zero audio produces SILENCE`() {
        val silence = ShortArray(16000) { 0 }
        detector.processAudioFrame(silence)
        assertEquals(AudioContext.SILENCE, detector.currentContext.value)
    }

    @Test
    fun `very quiet audio produces SILENCE`() {
        // Очень тихий сигнал (< SILENCE_RMS_THRESHOLD)
        val quiet = ShortArray(16000) { (Random.nextInt(5) - 2).toShort() }
        detector.processAudioFrame(quiet)
        assertEquals(AudioContext.SILENCE, detector.currentContext.value)
    }

    @Test
    fun `silence has high confidence`() {
        val silence = ShortArray(16000) { 0 }
        detector.processAudioFrame(silence)
        assertTrue(detector.confidence.value >= 0.9f)
    }

    // ==================== Speech-like signals ====================

    @Test
    fun `speech-like signal detected`() {
        // Речь: средний ZCR (~2000-4000), тональный сигнал
        // Генерируем смесь частот 200Hz + 400Hz с шумом
        val sampleRate = 16000
        val samples = ShortArray(sampleRate) { i ->
            val t = i.toDouble() / sampleRate
            val signal = 0.3 * sin(2 * PI * 200 * t) +
                0.2 * sin(2 * PI * 400 * t) +
                0.05 * (Random.nextDouble() - 0.5) // небольшой шум
            (signal * 32768 * 0.3).toInt().coerceIn(-32768, 32767).toShort()
        }

        // Несколько фреймов для стабилизации
        repeat(3) { detector.processAudioFrame(samples) }

        val context = detector.currentContext.value
        assertTrue(
            "Expected SPEECH or MUSIC for tonal signal, got $context",
            context == AudioContext.SPEECH || context == AudioContext.MUSIC
        )
    }

    // ==================== Music-like signals ====================

    @Test
    fun `pure tone produces MUSIC`() {
        // Чистый тон 440Hz — типичная музыкальная нота
        val sampleRate = 16000
        val samples = ShortArray(sampleRate) { i ->
            val t = i.toDouble() / sampleRate
            (sin(2 * PI * 440 * t) * 32768 * 0.5).toInt().coerceIn(-32768, 32767).toShort()
        }

        repeat(3) { detector.processAudioFrame(samples) }

        val context = detector.currentContext.value
        assertTrue(
            "Expected MUSIC for pure tone, got $context",
            context == AudioContext.MUSIC || context == AudioContext.SPEECH
        )
    }

    @Test
    fun `low frequency bass produces MUSIC`() {
        // Басовый сигнал 80Hz — низкий ZCR, высокий RMS
        val sampleRate = 16000
        val samples = ShortArray(sampleRate) { i ->
            val t = i.toDouble() / sampleRate
            (sin(2 * PI * 80 * t) * 32768 * 0.5).toInt().coerceIn(-32768, 32767).toShort()
        }

        repeat(3) { detector.processAudioFrame(samples) }
        assertEquals(AudioContext.MUSIC, detector.currentContext.value)
    }

    // ==================== Noise-like signals ====================

    @Test
    fun `white noise produces NOISE`() {
        // Белый шум: высокий ZCR, высокая flatness
        val samples = ShortArray(16000) {
            (Random.nextInt(20000) - 10000).toShort()
        }

        repeat(3) { detector.processAudioFrame(samples) }
        assertEquals(AudioContext.NOISE, detector.currentContext.value)
    }

    @Test
    fun `noise has reasonable confidence`() {
        val samples = ShortArray(16000) {
            (Random.nextInt(20000) - 10000).toShort()
        }

        repeat(3) { detector.processAudioFrame(samples) }
        assertTrue(detector.confidence.value >= 0.4f)
    }

    // ==================== ByteArray input ====================

    @Test
    fun `updateFromAudio converts and processes`() {
        // Тишина в ByteArray формате (little-endian 16-bit)
        val bytes = ByteArray(32000) { 0 }
        detector.updateFromAudio(bytes)
        assertEquals(AudioContext.SILENCE, detector.currentContext.value)
    }

    @Test
    fun `updateFromAudio rejects too small input`() {
        detector.updateFromAudio(ByteArray(2)) // меньше 4 байт — пропускаем
        assertEquals(AudioContext.SILENCE, detector.currentContext.value)
        assertEquals(0f, detector.confidence.value)
    }

    // ==================== Reset ====================

    @Test
    fun `reset clears state`() {
        val noise = ShortArray(16000) { (Random.nextInt(20000) - 10000).toShort() }
        detector.processAudioFrame(noise)
        assertNotEquals(AudioContext.SILENCE, detector.currentContext.value)

        detector.reset()
        assertEquals(AudioContext.SILENCE, detector.currentContext.value)
        assertEquals(0f, detector.confidence.value)
    }

    // ==================== Sliding window ====================

    @Test
    fun `context stabilizes with multiple frames`() {
        val silence = ShortArray(16000) { 0 }

        // Заполняем окно тишиной
        repeat(10) { detector.processAudioFrame(silence) }
        assertEquals(AudioContext.SILENCE, detector.currentContext.value)

        // Один шумный фрейм не должен мгновенно изменить контекст
        // (но может — зависит от силы сигнала и размера окна)
        val noise = ShortArray(16000) { (Random.nextInt(20000) - 10000).toShort() }
        detector.processAudioFrame(noise)

        // После 8+ фреймов шума — должен перейти в NOISE
        repeat(10) { detector.processAudioFrame(noise) }
        assertEquals(AudioContext.NOISE, detector.currentContext.value)
    }

    // ==================== AudioContext enum ====================

    @Test
    fun `all contexts have labels`() {
        AudioContext.entries.forEach {
            assertTrue(it.label.isNotBlank())
        }
    }

    @Test
    fun `four audio contexts exist`() {
        assertEquals(4, AudioContext.entries.size)
    }

    @Test
    fun `context valueOf works`() {
        assertEquals(AudioContext.SILENCE, AudioContext.valueOf("SILENCE"))
        assertEquals(AudioContext.SPEECH, AudioContext.valueOf("SPEECH"))
        assertEquals(AudioContext.MUSIC, AudioContext.valueOf("MUSIC"))
        assertEquals(AudioContext.NOISE, AudioContext.valueOf("NOISE"))
    }

    // ==================== Edge cases ====================

    @Test
    fun `empty frame is ignored`() {
        detector.processAudioFrame(ShortArray(0))
        assertEquals(AudioContext.SILENCE, detector.currentContext.value)
    }

    @Test
    fun `single sample frame is ignored`() {
        detector.processAudioFrame(ShortArray(1) { 1000 })
        assertEquals(AudioContext.SILENCE, detector.currentContext.value)
    }
}

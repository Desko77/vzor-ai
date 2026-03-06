package com.vzor.ai.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Классификатор аудио-контекста: определяет тип аудио (речь, музыка, шум, тишина)
 * по спектральным характеристикам PCM-фреймов.
 *
 * Используется BackendRouter'ом и VoiceOrchestrator'ом для адаптации поведения:
 * - Музыка → подавление wake word, приоритет действий над музыкой
 * - Речь → высокий приоритет STT
 * - Шум → повышенный gain, адаптивный VAD threshold
 * - Тишина → idle mode, снижение энергопотребления
 *
 * Метод: Zero Crossing Rate (ZCR) + RMS + Spectral Flatness (приближение).
 * - Речь: высокий ZCR (2000-5000), средний RMS, низкая flatness
 * - Музыка: средний ZCR (500-2000), высокий RMS, высокая flatness
 * - Шум: очень высокий ZCR (>5000), средний RMS, очень высокая flatness
 * - Тишина: любой ZCR, низкий RMS (<0.01)
 */
@Singleton
class AudioContextDetector @Inject constructor() {

    companion object {
        /** Размер скользящего окна для усреднения. */
        private const val WINDOW_SIZE = 8

        /** 16-bit PCM нормализация. */
        private const val SHORT_REF = 32768.0

        // RMS пороги
        private const val SILENCE_RMS_THRESHOLD = 0.01

        // ZCR пороги (на 16kHz, 1 секунда аудио)
        private const val ZCR_SPEECH_LOW = 1500
        private const val ZCR_SPEECH_HIGH = 5000
        private const val ZCR_NOISE_THRESHOLD = 5000

        // Spectral flatness пороги (0.0 = тональный, 1.0 = белый шум)
        private const val FLATNESS_MUSIC_THRESHOLD = 0.5
        private const val FLATNESS_NOISE_THRESHOLD = 0.8
    }

    private val _currentContext = MutableStateFlow(AudioContext.SILENCE)
    /** Текущий определённый аудио-контекст. */
    val currentContext: StateFlow<AudioContext> = _currentContext.asStateFlow()

    private val _confidence = MutableStateFlow(0f)
    /** Уверенность классификации (0.0 — 1.0). */
    val confidence: StateFlow<Float> = _confidence.asStateFlow()

    // Скользящие окна для усреднения
    private val rmsWindow = ArrayDeque<Double>(WINDOW_SIZE + 1)
    private val zcrWindow = ArrayDeque<Int>(WINDOW_SIZE + 1)
    private val flatnessWindow = ArrayDeque<Double>(WINDOW_SIZE + 1)

    /**
     * Анализирует PCM-фрейм и обновляет текущий аудио-контекст.
     *
     * @param pcmData 16-bit signed PCM mono samples (16kHz)
     */
    fun processAudioFrame(pcmData: ShortArray) {
        if (pcmData.size < 2) return

        val rms = calculateRms(pcmData)
        val zcr = calculateZcr(pcmData)
        val flatness = calculateSpectralFlatness(pcmData)

        synchronized(this) {
            addToWindow(rmsWindow, rms)
            addToWindow(zcrWindow, zcr)
            addToWindow(flatnessWindow, flatness)
        }

        val avgRms = synchronized(this) { rmsWindow.average() }
        val avgZcr = synchronized(this) { zcrWindow.average().toInt() }
        val avgFlatness = synchronized(this) { flatnessWindow.average() }

        val (context, conf) = classify(avgRms, avgZcr, avgFlatness)
        _currentContext.value = context
        _confidence.value = conf
    }

    /**
     * Анализирует PCM-фрейм в формате ByteArray.
     * Конвертирует в ShortArray и делегирует в [processAudioFrame].
     */
    fun updateFromAudio(pcmData: ByteArray) {
        if (pcmData.size < 4) return
        val shorts = ShortArray(pcmData.size / 2)
        for (i in shorts.indices) {
            val low = pcmData[i * 2].toInt() and 0xFF
            val high = pcmData[i * 2 + 1].toInt()
            shorts[i] = ((high shl 8) or low).toShort()
        }
        processAudioFrame(shorts)
    }

    /**
     * Сбрасывает состояние детектора.
     */
    fun reset() {
        synchronized(this) {
            rmsWindow.clear()
            zcrWindow.clear()
            flatnessWindow.clear()
        }
        _currentContext.value = AudioContext.SILENCE
        _confidence.value = 0f
    }

    // ==================== Classification ====================

    private fun classify(rms: Double, zcr: Int, flatness: Double): Pair<AudioContext, Float> {
        // Тишина: низкий уровень сигнала
        if (rms < SILENCE_RMS_THRESHOLD) {
            return AudioContext.SILENCE to 0.95f
        }

        // Шум: высокий ZCR + высокая flatness
        if (zcr > ZCR_NOISE_THRESHOLD && flatness > FLATNESS_NOISE_THRESHOLD) {
            return AudioContext.NOISE to 0.8f
        }

        // Речь: средний ZCR (1500-5000) + низкая flatness (тональная)
        if (zcr in ZCR_SPEECH_LOW..ZCR_SPEECH_HIGH && flatness < FLATNESS_MUSIC_THRESHOLD) {
            val conf = 0.7f + 0.2f * (1.0f - flatness.toFloat())
            return AudioContext.SPEECH to conf.coerceAtMost(0.9f)
        }

        // Музыка: средний/низкий ZCR + средняя flatness + высокий RMS
        if (zcr < ZCR_SPEECH_HIGH && flatness in FLATNESS_MUSIC_THRESHOLD..FLATNESS_NOISE_THRESHOLD) {
            return AudioContext.MUSIC to 0.7f
        }

        // Музыка: низкий ZCR + высокий RMS (чистые тоны, басы)
        if (zcr < ZCR_SPEECH_LOW && rms > 0.05) {
            return AudioContext.MUSIC to 0.6f
        }

        // Речь как fallback при среднем сигнале
        if (rms > 0.02 && flatness < FLATNESS_NOISE_THRESHOLD) {
            return AudioContext.SPEECH to 0.5f
        }

        return AudioContext.NOISE to 0.4f
    }

    // ==================== Signal analysis ====================

    private fun calculateRms(samples: ShortArray): Double {
        var sum = 0.0
        for (sample in samples) {
            val normalized = sample.toDouble() / SHORT_REF
            sum += normalized * normalized
        }
        return sqrt(sum / samples.size)
    }

    /**
     * Zero Crossing Rate — количество пересечений нуля за фрейм.
     * Нормализуется к 1 секунде (16kHz).
     */
    private fun calculateZcr(samples: ShortArray): Int {
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0 && samples[i - 1] < 0) ||
                (samples[i] < 0 && samples[i - 1] >= 0)
            ) {
                crossings++
            }
        }
        // Нормализация к 1 секунде: 16000 сэмплов = 1 сек
        val durationSec = samples.size / 16000.0
        return if (durationSec > 0) (crossings / durationSec).toInt() else 0
    }

    /**
     * Приближение спектральной плоскости через отношение
     * геометрического среднего |x| к арифметическому среднему |x|.
     *
     * 0.0 → чистый тон, 1.0 → белый шум.
     */
    private fun calculateSpectralFlatness(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0

        var logSum = 0.0
        var linearSum = 0.0
        var count = 0

        for (sample in samples) {
            val magnitude = abs(sample.toDouble() / SHORT_REF)
            if (magnitude > 1e-10) {
                logSum += ln(magnitude)
                linearSum += magnitude
                count++
            }
        }

        if (count == 0 || linearSum <= 0) return 0.0

        val geometricMean = kotlin.math.exp(logSum / count)
        val arithmeticMean = linearSum / count

        return (geometricMean / arithmeticMean).coerceIn(0.0, 1.0)
    }

    // ==================== Window helpers ====================

    private fun <T : Number> addToWindow(window: ArrayDeque<T>, value: T) {
        window.addLast(value)
        if (window.size > WINDOW_SIZE) {
            window.removeFirst()
        }
    }

    private fun <T : Number> ArrayDeque<T>.average(): Double {
        if (isEmpty()) return 0.0
        return sumOf { it.toDouble() } / size
    }
}

/**
 * Тип аудио-контекста.
 */
enum class AudioContext(val label: String) {
    /** Тишина — нет значимого аудио-сигнала. */
    SILENCE("Тишина"),

    /** Речь — голос человека (средний ZCR, тональный сигнал). */
    SPEECH("Речь"),

    /** Музыка — музыкальное содержание (низкий ZCR, высокий RMS). */
    MUSIC("Музыка"),

    /** Шум — окружающий/фоновый шум (высокий ZCR, высокая flatness). */
    NOISE("Шум")
}

package com.vzor.ai.speech

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Speaker diarization — определение кто говорит (speaker segmentation).
 *
 * Необходим для Translation Mode C (bidirectional): разделяет речь
 * пользователя и собеседника для корректного перевода в обе стороны.
 *
 * Реализация: energy + spectral features для классификации говорящих.
 * - Канал 1 (glasses mic): направленный микрофон → вероятнее пользователь
 * - Канал 2 (phone mic): всенаправленный → вероятнее собеседник
 * - При одном канале: VAD + pitch tracking + spectral centroid для
 *   разделения и профилирования говорящих.
 *
 * Спикерное профилирование:
 * - Каждый говорящий получает VoiceProfile (средний pitch, spectral centroid, RMS)
 * - Новый сегмент сравнивается с существующими профилями
 * - Если профиль совпадает — продолжение того же говорящего
 * - Если не совпадает — переключение на другого говорящего
 */
@Singleton
class SpeakerDiarizer @Inject constructor() {

    companion object {
        private const val TAG = "SpeakerDiarizer"
        private const val SAMPLE_RATE = 16_000

        /** Минимальная длительность сегмента речи одного говорящего (ms). */
        private const val MIN_SEGMENT_DURATION_MS = 300L

        /** Порог тишины для разделения говорящих (RMS amplitude). */
        private const val SILENCE_RMS_THRESHOLD = 200

        /** Минимальная пауза между говорящими для переключения (ms). */
        private const val SPEAKER_SWITCH_PAUSE_MS = 500L

        /** Порог расстояния между профилями для определения смены говорящего. */
        private const val PROFILE_DISTANCE_THRESHOLD = 0.4f

        /** Максимальное количество фреймов для накопления профиля сегмента. */
        private const val MAX_PROFILE_FRAMES = 50
    }

    /**
     * Идентификатор говорящего.
     */
    enum class Speaker {
        /** Пользователь (владелец очков). */
        USER,
        /** Собеседник. */
        INTERLOCUTOR,
        /** Не определён. */
        UNKNOWN
    }

    /**
     * Голосовой профиль говорящего на основе спектральных характеристик.
     */
    data class VoiceProfile(
        val avgPitchHz: Float = 0f,
        val avgSpectralCentroid: Float = 0f,
        val avgRms: Float = 0f,
        val sampleCount: Int = 0
    ) {
        /**
         * Расстояние между двумя профилями (нормализованное 0..1+).
         */
        fun distanceTo(other: VoiceProfile): Float {
            if (sampleCount == 0 || other.sampleCount == 0) return 1f
            // Нормализуем каждый параметр по типичному диапазону
            val pitchDiff = abs(avgPitchHz - other.avgPitchHz) / 200f // 80-300 Hz range
            val centroidDiff = abs(avgSpectralCentroid - other.avgSpectralCentroid) / 4000f
            val rmsDiff = abs(avgRms - other.avgRms) / 5000f
            // Взвешенная сумма: pitch самый информативный
            return pitchDiff * 0.5f + centroidDiff * 0.3f + rmsDiff * 0.2f
        }

        /**
         * Обновляет профиль новым фреймом (exponential moving average).
         */
        fun update(pitchHz: Float, spectralCentroid: Float, rms: Float): VoiceProfile {
            if (sampleCount == 0) {
                return VoiceProfile(pitchHz, spectralCentroid, rms, 1)
            }
            val alpha = 0.2f // Скорость адаптации
            return VoiceProfile(
                avgPitchHz = avgPitchHz * (1 - alpha) + pitchHz * alpha,
                avgSpectralCentroid = avgSpectralCentroid * (1 - alpha) + spectralCentroid * alpha,
                avgRms = avgRms * (1 - alpha) + rms * alpha,
                sampleCount = sampleCount + 1
            )
        }
    }

    /**
     * Сегмент речи с привязкой к говорящему.
     */
    data class SpeechSegment(
        val speaker: Speaker,
        val startMs: Long,
        val endMs: Long,
        val text: String = "",
        val confidence: Float = 0.5f
    )

    private val _currentSpeaker = MutableStateFlow(Speaker.UNKNOWN)

    /** Текущий говорящий (обновляется в реальном времени). */
    val currentSpeaker: StateFlow<Speaker> = _currentSpeaker.asStateFlow()

    private val mutex = Mutex()
    private val segments = mutableListOf<SpeechSegment>()
    private var lastSpeechEndMs: Long = 0L
    private var currentSegmentStartMs: Long = 0L
    private var isInSpeech = false

    // Голосовые профили говорящих
    private var userProfile = VoiceProfile()
    private var interlocutorProfile = VoiceProfile()

    // Текущий профиль накапливаемого сегмента
    private var currentSegmentProfile = VoiceProfile()
    private var segmentFrameCount = 0

    /**
     * Обрабатывает аудио-фрейм и определяет текущего говорящего.
     *
     * @param pcmFrame PCM 16-bit mono 16kHz данные.
     * @param timestampMs Временная метка начала фрейма.
     * @param isFromGlassesMic true если фрейм с микрофона очков (направленный).
     */
    suspend fun processFrame(pcmFrame: ByteArray, timestampMs: Long, isFromGlassesMic: Boolean = true) = mutex.withLock {
        val rms = calculateRms(pcmFrame)

        if (rms > SILENCE_RMS_THRESHOLD) {
            // Речь обнаружена — обновляем спектральный профиль сегмента
            if (segmentFrameCount < MAX_PROFILE_FRAMES) {
                val pitch = estimatePitch(pcmFrame)
                val centroid = calculateSpectralCentroid(pcmFrame)
                if (pitch > 0f) {
                    currentSegmentProfile = currentSegmentProfile.update(pitch, centroid, rms.toFloat())
                    segmentFrameCount++
                }
            }

            if (!isInSpeech) {
                // Начало нового сегмента речи
                val pauseDuration = timestampMs - lastSpeechEndMs

                val newSpeaker = determineSpeaker(pauseDuration, isFromGlassesMic)

                isInSpeech = true
                currentSegmentStartMs = timestampMs
                _currentSpeaker.value = newSpeaker
            }
        } else {
            // Тишина
            if (isInSpeech) {
                val segmentDuration = timestampMs - currentSegmentStartMs
                if (segmentDuration >= MIN_SEGMENT_DURATION_MS) {
                    val speaker = _currentSpeaker.value
                    val confidence = calculateConfidence()

                    // Обновляем профиль говорящего
                    updateSpeakerProfile(speaker)

                    segments.add(SpeechSegment(
                        speaker = speaker,
                        startMs = currentSegmentStartMs,
                        endMs = timestampMs,
                        confidence = confidence
                    ))
                    Log.d(TAG, "Segment closed: $speaker " +
                        "(${currentSegmentStartMs}ms - ${timestampMs}ms, " +
                        "pitch=${currentSegmentProfile.avgPitchHz.toInt()}Hz, " +
                        "conf=${"%.2f".format(confidence)})")
                }
                isInSpeech = false
                lastSpeechEndMs = timestampMs
                currentSegmentProfile = VoiceProfile()
                segmentFrameCount = 0
            }
        }
    }

    /**
     * Определяет говорящего на основе паузы, микрофона и голосового профиля.
     */
    private fun determineSpeaker(pauseDuration: Long, isFromGlassesMic: Boolean): Speaker {
        if (segments.isEmpty()) {
            // Первый сегмент: определяем по источнику микрофона
            return if (isFromGlassesMic) Speaker.USER else Speaker.INTERLOCUTOR
        }

        if (pauseDuration <= SPEAKER_SWITCH_PAUSE_MS) {
            // Короткая пауза — продолжение того же говорящего
            return segments.lastOrNull()?.speaker ?: Speaker.UNKNOWN
        }

        // Длинная пауза — проверяем профиль для определения говорящего
        if (currentSegmentProfile.sampleCount >= 3) {
            val distToUser = currentSegmentProfile.distanceTo(userProfile)
            val distToInterlocutor = currentSegmentProfile.distanceTo(interlocutorProfile)

            // Если оба профиля есть — сравниваем расстояния
            if (userProfile.sampleCount > 0 && interlocutorProfile.sampleCount > 0) {
                return if (distToUser < distToInterlocutor && distToUser < PROFILE_DISTANCE_THRESHOLD) {
                    Speaker.USER
                } else if (distToInterlocutor < distToUser && distToInterlocutor < PROFILE_DISTANCE_THRESHOLD) {
                    Speaker.INTERLOCUTOR
                } else {
                    // Профили слишком далеки — используем чередование
                    alternateFromLast()
                }
            }
        }

        // Fallback: чередуем говорящих после паузы
        return alternateFromLast()
    }

    /**
     * Чередует говорящего от последнего сегмента.
     */
    private fun alternateFromLast(): Speaker {
        return when (segments.lastOrNull()?.speaker) {
            Speaker.USER -> Speaker.INTERLOCUTOR
            Speaker.INTERLOCUTOR -> Speaker.USER
            else -> Speaker.UNKNOWN
        }
    }

    /**
     * Обновляет профиль соответствующего говорящего.
     */
    private fun updateSpeakerProfile(speaker: Speaker) {
        if (currentSegmentProfile.sampleCount == 0) return
        when (speaker) {
            Speaker.USER -> userProfile = if (userProfile.sampleCount == 0) {
                currentSegmentProfile
            } else {
                userProfile.update(
                    currentSegmentProfile.avgPitchHz,
                    currentSegmentProfile.avgSpectralCentroid,
                    currentSegmentProfile.avgRms
                )
            }
            Speaker.INTERLOCUTOR -> interlocutorProfile = if (interlocutorProfile.sampleCount == 0) {
                currentSegmentProfile
            } else {
                interlocutorProfile.update(
                    currentSegmentProfile.avgPitchHz,
                    currentSegmentProfile.avgSpectralCentroid,
                    currentSegmentProfile.avgRms
                )
            }
            Speaker.UNKNOWN -> { /* не обновляем */ }
        }
    }

    /**
     * Вычисляет confidence на основе качества спектрального профиля.
     */
    private fun calculateConfidence(): Float {
        val profileQuality = (segmentFrameCount.coerceAtMost(10) / 10f) * 0.3f
        val profileMatch = if (currentSegmentProfile.sampleCount >= 3) {
            val distUser = currentSegmentProfile.distanceTo(userProfile)
            val distInterlocutor = currentSegmentProfile.distanceTo(interlocutorProfile)
            val minDist = minOf(distUser, distInterlocutor)
            (1f - minDist).coerceIn(0f, 1f) * 0.5f
        } else {
            0.2f
        }
        return (0.2f + profileQuality + profileMatch).coerceIn(0.3f, 0.95f)
    }

    /**
     * Возвращает все накопленные сегменты речи.
     */
    suspend fun getSegments(): List<SpeechSegment> = mutex.withLock {
        segments.toList()
    }

    /**
     * Возвращает текущие голосовые профили для диагностики.
     */
    suspend fun getProfiles(): Pair<VoiceProfile, VoiceProfile> = mutex.withLock {
        userProfile to interlocutorProfile
    }

    /**
     * Очищает накопленные сегменты (начало новой сессии).
     */
    suspend fun reset() = mutex.withLock {
        segments.clear()
        _currentSpeaker.value = Speaker.UNKNOWN
        lastSpeechEndMs = 0L
        currentSegmentStartMs = 0L
        isInSpeech = false
        userProfile = VoiceProfile()
        interlocutorProfile = VoiceProfile()
        currentSegmentProfile = VoiceProfile()
        segmentFrameCount = 0
        Log.d(TAG, "Diarization reset")
    }

    /**
     * Привязывает текст к последнему сегменту текущего говорящего.
     */
    suspend fun attachText(text: String) = mutex.withLock {
        val last = segments.lastOrNull() ?: return@withLock
        segments[segments.lastIndex] = last.copy(text = text)
    }

    // =================================================================
    // Спектральный анализ
    // =================================================================

    /**
     * Оценивает основную частоту (pitch) методом автокорреляции.
     *
     * @return Частота в Hz или 0 если pitch не определён.
     */
    internal fun estimatePitch(pcm: ByteArray): Float {
        val samples = pcmToSamples(pcm)
        if (samples.size < 64) return 0f

        // Диапазон поиска pitch: 80 Hz (мужской бас) — 400 Hz (детский голос)
        val minLag = SAMPLE_RATE / 400 // 40 samples
        val maxLag = SAMPLE_RATE / 80  // 200 samples

        if (maxLag >= samples.size) return 0f

        var bestLag = 0
        var bestCorrelation = 0.0

        for (lag in minLag..minOf(maxLag, samples.size - 1)) {
            var correlation = 0.0
            var energy = 0.0
            val length = samples.size - lag
            for (i in 0 until length) {
                correlation += samples[i].toDouble() * samples[i + lag]
                energy += samples[i].toDouble() * samples[i]
            }
            // Нормализованная автокорреляция
            if (energy > 0) {
                val normalized = correlation / energy
                if (normalized > bestCorrelation) {
                    bestCorrelation = normalized
                    bestLag = lag
                }
            }
        }

        // Минимальный порог корреляции для уверенного pitch
        if (bestCorrelation < 0.3 || bestLag == 0) return 0f

        return SAMPLE_RATE.toFloat() / bestLag
    }

    /**
     * Вычисляет спектральный центроид — "центр масс" частотного спектра.
     * Более высокий центроид → более яркий/резкий голос.
     *
     * Используем упрощённый расчёт через zero-crossing rate (коррелирует
     * со спектральным центроидом для речевых сигналов).
     */
    internal fun calculateSpectralCentroid(pcm: ByteArray): Float {
        val samples = pcmToSamples(pcm)
        if (samples.size < 2) return 0f

        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i - 1] >= 0 && samples[i] < 0) ||
                (samples[i - 1] < 0 && samples[i] >= 0)) {
                crossings++
            }
        }

        // ZCR → приблизительная частота в Hz
        val zcr = crossings.toFloat() / (samples.size - 1)
        return zcr * SAMPLE_RATE / 2f
    }

    /**
     * Вычисляет RMS амплитуду PCM 16-bit буфера.
     */
    internal fun calculateRms(pcm: ByteArray): Int {
        if (pcm.size < 2) return 0
        var sum = 0L
        var sampleCount = 0
        var i = 0
        while (i < pcm.size - 1) {
            val sample = (pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)
            sum += sample.toLong() * sample
            sampleCount++
            i += 2
        }
        if (sampleCount == 0) return 0
        return sqrt((sum.toDouble() / sampleCount)).toInt()
    }

    /**
     * Конвертирует PCM 16-bit байты в массив семплов.
     */
    private fun pcmToSamples(pcm: ByteArray): IntArray {
        val count = pcm.size / 2
        val samples = IntArray(count)
        for (i in 0 until count) {
            samples[i] = (pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xFF)
        }
        return samples
    }
}

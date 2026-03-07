package com.vzor.ai.speech

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaker diarization — определение кто говорит (speaker segmentation).
 *
 * Необходим для Translation Mode C (bidirectional): разделяет речь
 * пользователя и собеседника для корректного перевода в обе стороны.
 *
 * Текущая реализация: energy-based двухканальная классификация.
 * - Канал 1 (glasses mic): направленный микрофон → вероятнее пользователь
 * - Канал 2 (phone mic): всенаправленный → вероятнее собеседник
 *
 * При наличии одного канала: используем VAD + pitch tracking для
 * сегментации пауз между говорящими.
 *
 * TODO: Интеграция с pyannote-audio или ONNX-модель для точной диаризации.
 */
@Singleton
class SpeakerDiarizer @Inject constructor() {

    companion object {
        private const val TAG = "SpeakerDiarizer"

        /** Минимальная длительность сегмента речи одного говорящего (ms). */
        private const val MIN_SEGMENT_DURATION_MS = 300L

        /** Порог тишины для разделения говорящих (RMS amplitude). */
        private const val SILENCE_RMS_THRESHOLD = 200

        /** Минимальная пауза между говорящими для переключения (ms). */
        private const val SPEAKER_SWITCH_PAUSE_MS = 500L
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

    private val segments = mutableListOf<SpeechSegment>()
    private var lastSpeechEndMs: Long = 0L
    private var currentSegmentStartMs: Long = 0L
    private var isInSpeech = false

    /**
     * Обрабатывает аудио-фрейм и определяет текущего говорящего.
     *
     * @param pcmFrame PCM 16-bit mono 16kHz данные.
     * @param timestampMs Временная метка начала фрейма.
     * @param isFromGlassesMic true если фрейм с микрофона очков (направленный).
     */
    fun processFrame(pcmFrame: ByteArray, timestampMs: Long, isFromGlassesMic: Boolean = true) {
        val rms = calculateRms(pcmFrame)

        if (rms > SILENCE_RMS_THRESHOLD) {
            // Речь обнаружена
            if (!isInSpeech) {
                // Начало нового сегмента речи
                val pauseDuration = timestampMs - lastSpeechEndMs

                // Если пауза достаточно длинная — потенциальная смена говорящего
                val newSpeaker = if (pauseDuration > SPEAKER_SWITCH_PAUSE_MS && segments.isNotEmpty()) {
                    // Простая эвристика: чередуем говорящих после паузы
                    val lastSpeaker = segments.lastOrNull()?.speaker ?: Speaker.UNKNOWN
                    when (lastSpeaker) {
                        Speaker.USER -> Speaker.INTERLOCUTOR
                        Speaker.INTERLOCUTOR -> Speaker.USER
                        Speaker.UNKNOWN -> if (isFromGlassesMic) Speaker.USER else Speaker.INTERLOCUTOR
                    }
                } else if (segments.isEmpty()) {
                    // Первый сегмент: определяем по источнику микрофона
                    if (isFromGlassesMic) Speaker.USER else Speaker.INTERLOCUTOR
                } else {
                    // Продолжение текущего говорящего
                    segments.lastOrNull()?.speaker ?: Speaker.UNKNOWN
                }

                isInSpeech = true
                currentSegmentStartMs = timestampMs
                _currentSpeaker.value = newSpeaker
            }
        } else {
            // Тишина
            if (isInSpeech) {
                val segmentDuration = timestampMs - currentSegmentStartMs
                if (segmentDuration >= MIN_SEGMENT_DURATION_MS) {
                    // Завершаем сегмент
                    segments.add(SpeechSegment(
                        speaker = _currentSpeaker.value,
                        startMs = currentSegmentStartMs,
                        endMs = timestampMs,
                        confidence = 0.6f // Energy-based heuristic
                    ))
                    Log.d(TAG, "Segment closed: ${_currentSpeaker.value} " +
                        "(${currentSegmentStartMs}ms - ${timestampMs}ms, ${segmentDuration}ms)")
                }
                isInSpeech = false
                lastSpeechEndMs = timestampMs
            }
        }
    }

    /**
     * Возвращает все накопленные сегменты речи.
     */
    fun getSegments(): List<SpeechSegment> = segments.toList()

    /**
     * Очищает накопленные сегменты (начало новой сессии).
     */
    fun reset() {
        segments.clear()
        _currentSpeaker.value = Speaker.UNKNOWN
        lastSpeechEndMs = 0L
        currentSegmentStartMs = 0L
        isInSpeech = false
        Log.d(TAG, "Diarization reset")
    }

    /**
     * Привязывает текст к последнему сегменту текущего говорящего.
     */
    fun attachText(text: String) {
        val last = segments.lastOrNull() ?: return
        segments[segments.lastIndex] = last.copy(text = text)
    }

    /**
     * Вычисляет RMS амплитуду PCM 16-bit буфера.
     */
    private fun calculateRms(pcm: ByteArray): Int {
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
        return kotlin.math.sqrt((sum.toDouble() / sampleCount)).toInt()
    }
}

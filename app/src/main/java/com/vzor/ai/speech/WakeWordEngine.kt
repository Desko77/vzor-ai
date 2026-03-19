package com.vzor.ai.speech

/**
 * Абстракция движка распознавания wake word.
 *
 * Позволяет подменять реализацию:
 * - [PorcupineWakeWordEngine] — Picovoice Porcupine SDK (точный, нужен Access Key)
 * - [EnergyWakeWordEngine] — energy-based VAD + ZCR heuristic (fallback, без зависимостей)
 */
interface WakeWordEngine {

    /**
     * Обрабатывает аудио фрейм и проверяет наличие wake word.
     *
     * @param pcmData PCM 16-bit mono 16kHz данные как ShortArray.
     * @return true если wake word обнаружен в данном фрейме.
     */
    fun process(pcmData: ShortArray): Boolean

    /**
     * Освобождает нативные ресурсы движка.
     * После вызова `release()` объект нельзя использовать повторно.
     */
    fun release()

    /**
     * Проверяет, инициализирован ли движок и готов к работе.
     */
    fun isReady(): Boolean
}

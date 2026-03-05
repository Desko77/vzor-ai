package com.vzor.ai.tts

/**
 * Высокоуровневый интерфейс TTS-сервиса.
 * Реализация: [TtsManager] — оркестрирует несколько TTS-провайдеров.
 */
interface TtsService {
    fun stop()
    fun onToken(token: String)
    fun onStreamEnd()
    suspend fun speak(text: String)
}

/**
 * TTS provider interface for synthesizing speech from text.
 * Implementations handle different TTS backends (Yandex, Google, etc.)
 */
interface TtsProvider {
    /**
     * Synthesize text to audio bytes.
     * @param text The text to synthesize.
     * @param voice Voice identifier (provider-specific, e.g. "alena" for Yandex).
     * @param lang Language code (e.g. "ru-RU", "en-US").
     * @return Audio bytes (PCM or provider-specific format), or null on failure.
     */
    suspend fun synthesize(text: String, voice: String = "", lang: String = "ru-RU"): ByteArray?

    /**
     * Stop any ongoing synthesis or playback.
     */
    fun stop()
}

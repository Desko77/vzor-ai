package com.vzor.ai.domain.model

enum class AiProvider(val displayName: String) {
    GEMINI("Google Gemini"),
    CLAUDE("Anthropic Claude"),
    OPENAI("OpenAI GPT-4o"),
    GLM_5("Zhipu GLM-5"),
    LOCAL_QWEN("Local AI (Qwen)"),
    OFFLINE_QWEN("Offline (On-Device)");

    companion object {
        val DEFAULT = GEMINI

        /** Cloud-only providers selectable in Settings */
        val CLOUD_PROVIDERS = listOf(GEMINI, CLAUDE, OPENAI, GLM_5)
    }
}

enum class SttProvider(val displayName: String) {
    WHISPER("OpenAI Whisper"),
    YANDEX("Yandex SpeechKit"),
    GOOGLE("Google STT")
}

enum class TtsProvider(val displayName: String) {
    YANDEX("Yandex SpeechKit"),
    GOOGLE("Google TTS")
}

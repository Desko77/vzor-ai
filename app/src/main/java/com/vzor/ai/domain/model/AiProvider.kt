package com.vzor.ai.domain.model

enum class AiProvider(val displayName: String) {
    GEMINI("Google Gemini"),
    CLAUDE("Anthropic Claude"),
    OPENAI("OpenAI GPT-4o");

    companion object {
        val DEFAULT = GEMINI
    }
}

enum class SttProvider(val displayName: String) {
    WHISPER("OpenAI Whisper"),
    GOOGLE("Google STT")
}

enum class TtsProvider(val displayName: String) {
    GOOGLE("Google TTS"),
    YANDEX("Yandex SpeechKit")
}

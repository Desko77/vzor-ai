package com.vzor.ai.translation

/**
 * Translation mode scenarios:
 * - LISTEN (A): Listen to foreign speech -> translate -> show text + TTS in native language
 * - SPEAK (B): User speaks native -> translate -> TTS in foreign language
 * - BIDIRECTIONAL (C): Auto-detect language -> translate both ways
 */
enum class TranslationMode {
    LISTEN,
    SPEAK,
    BIDIRECTIONAL
}

/**
 * Represents the current state of the translation pipeline.
 */
data class TranslationState(
    val mode: TranslationMode? = null,
    val isActive: Boolean = false,
    val status: String = "idle"
)

/**
 * A single translation result with timing metadata.
 */
data class TranslationResult(
    val sourceText: String,
    val translatedText: String,
    val sourceLang: String,
    val targetLang: String,
    val timestamp: Long = System.currentTimeMillis(),
    val latencyMs: Long = 0L
)

/**
 * Holds a full translation session with history of all translations performed.
 */
data class TranslationSession(
    val sessionId: String,
    val mode: TranslationMode,
    val sourceLang: String,
    val targetLang: String,
    val startedAt: Long = System.currentTimeMillis(),
    val translations: MutableList<TranslationResult> = mutableListOf()
)

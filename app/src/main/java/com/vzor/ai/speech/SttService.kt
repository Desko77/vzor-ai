package com.vzor.ai.speech

import kotlinx.coroutines.flow.Flow

/**
 * Speech-to-Text service interface.
 * Implementations handle different STT backends (Whisper, Yandex, etc.)
 */
interface SttService {
    fun startListening(): Flow<SttResult>
    fun stopListening()
    val isListening: Boolean
}

data class SttResult(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float = 1.0f,
    val language: String = "ru"
)

package com.vzor.ai.domain.model

/**
 * Events that trigger FSM transitions in VoiceOrchestrator.
 *
 * Transition table:
 * - WAKE_WORD_DETECTED, BUTTON_PRESSED:     IDLE → LISTENING
 * - SILENCE_TIMEOUT (8s):                    LISTENING → IDLE
 * - SPEECH_END:                              LISTENING → PROCESSING
 * - INTENT_READY:                            PROCESSING → GENERATING
 * - FIRST_AUDIO_CHUNK:                       GENERATING → RESPONDING
 * - CONFIRM_REQUIRED:                        GENERATING → CONFIRMING
 * - BARGE_IN:                                GENERATING/RESPONDING → LISTENING, CONFIRMING → IDLE
 * - TTS_COMPLETE:                            RESPONDING → IDLE
 * - USER_CONFIRMED/USER_CANCELLED/CONFIRM_TIMEOUT: CONFIRMING → IDLE
 * - SYSTEM_INTERRUPT:                        GENERATING/RESPONDING → SUSPENDED
 * - AUDIO_FOCUS_GAINED:                      SUSPENDED → IDLE
 * - HARD_RESET:                              any → IDLE
 * - ERROR_OCCURRED:                          PROCESSING/GENERATING/RESPONDING → ERROR
 * - ERROR_TIMEOUT (3s):                      ERROR → IDLE
 */
sealed class VoiceEvent {

    /** Wake word detected by keyword spotter. */
    data class WakeWordDetected(
        val keyword: String = "vzor",
        val confidence: Float = 0f
    ) : VoiceEvent()

    /** Physical button or UI tap to start listening. */
    data object ButtonPressed : VoiceEvent()

    /** No speech detected for 8 seconds while in LISTENING state. */
    data class SilenceTimeout(
        val durationMs: Long = 8000L
    ) : VoiceEvent()

    /** End-of-speech detected by VAD. */
    data class SpeechEnd(
        val transcript: String,
        val confidence: Float = 0f
    ) : VoiceEvent()

    /** Intent classification completed. */
    data class IntentReady(
        val intent: VzorIntent
    ) : VoiceEvent()

    /** First audio chunk of TTS response is ready for playback. */
    data object FirstAudioChunk : VoiceEvent()

    /** The generated response requires user confirmation before execution. */
    data class ConfirmRequired(
        val action: String,
        val description: String
    ) : VoiceEvent()

    /** User interrupts during GENERATING, RESPONDING, or CONFIRMING. */
    data object BargeIn : VoiceEvent()

    /** TTS playback finished. */
    data object TtsComplete : VoiceEvent()

    /** User confirmed a pending action in CONFIRMING state. */
    data object UserConfirmed : VoiceEvent()

    /** User cancelled a pending action in CONFIRMING state. */
    data object UserCancelled : VoiceEvent()

    /** Confirmation timed out after 10 seconds. */
    data class ConfirmTimeout(
        val durationMs: Long = 10000L
    ) : VoiceEvent()

    /** System interrupt — incoming call, AudioFocus loss, etc. */
    data class SystemInterrupt(
        val reason: String
    ) : VoiceEvent()

    /** AudioFocus regained after system interrupt. */
    data object AudioFocusGained : VoiceEvent()

    /** Force reset to IDLE from any state. */
    data object HardReset : VoiceEvent()

    /** An error occurred during processing, generation, or response. */
    data class ErrorOccurred(
        val error: Throwable,
        val message: String = error.localizedMessage ?: "Unknown error"
    ) : VoiceEvent()

    /** Auto-recovery timeout after 3 seconds in ERROR state. */
    data class ErrorTimeout(
        val durationMs: Long = 3000L
    ) : VoiceEvent()
}

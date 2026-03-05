package com.vzor.ai.orchestrator

import android.util.Log
import com.vzor.ai.domain.model.VoiceEvent
import com.vzor.ai.domain.model.VoiceState
import com.vzor.ai.speech.SttService
import com.vzor.ai.tts.TtsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central Finite State Machine for the Vzor voice assistant.
 *
 * Maintains current [VoiceState] via [StateFlow], accepts [VoiceEvent]s,
 * enforces valid transitions, handles barge-in, system interrupts,
 * and auto-recovery from ERROR state after 3 seconds.
 */
@Singleton
class VoiceOrchestrator @Inject constructor(
    private val sttService: SttService,
    private val ttsService: TtsService,
    private val intentClassifier: IntentClassifier
) : Closeable {
    companion object {
        private const val TAG = "VoiceOrchestrator"
        private const val ERROR_RECOVERY_DELAY_MS = 3000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var currentSession: ConversationSession? = null
    private var errorRecoveryJob: Job? = null

    /** Listeners for state transitions (telemetry, UI, etc.). */
    private val transitionListeners = CopyOnWriteArrayList<(VoiceState, VoiceState, VoiceEvent) -> Unit>()

    /**
     * Submit a [VoiceEvent] to the FSM for processing.
     * Must be called from the main thread.
     */
    fun onEvent(event: VoiceEvent) {
        handleTransition(event)
    }

    /**
     * Start a new conversation session.
     * If a session is already active it is replaced.
     */
    fun startSession(): ConversationSession {
        val session = ConversationSession()
        currentSession = session
        Log.d(TAG, "Session started: ${session.sessionId}")
        return session
    }

    /** Return the active session, or null if none. */
    fun currentSession(): ConversationSession? = currentSession

    /** End the current session and return to IDLE. */
    fun endSession() {
        currentSession = null
        onEvent(VoiceEvent.HardReset)
    }

    /** Register a listener that is called on every valid state transition. */
    fun addTransitionListener(listener: (from: VoiceState, to: VoiceState, event: VoiceEvent) -> Unit) {
        transitionListeners.add(listener)
    }

    /** Release resources and cancel the internal coroutine scope. */
    override fun close() {
        errorRecoveryJob?.cancel()
        scope.cancel()
    }

    // ---- FSM core ----

    private fun handleTransition(event: VoiceEvent) {
        val currentState = _state.value
        val newState = resolveTransition(currentState, event)

        if (newState != null && newState != currentState) {
            performSideEffects(currentState, newState, event)
            _state.value = newState
            logTransition(currentState, newState, event)
            notifyListeners(currentState, newState, event)

            // Schedule auto-recovery when entering ERROR
            if (newState == VoiceState.ERROR) {
                scheduleErrorRecovery()
            }
        } else if (newState == null) {
            Log.w(TAG, "Invalid transition: $currentState + $event — ignored")
        }
    }

    /**
     * Resolve the next state given the current state and incoming event.
     * Returns null if the transition is not valid (event is ignored).
     */
    private fun resolveTransition(current: VoiceState, event: VoiceEvent): VoiceState? {
        // HARD_RESET is valid from any state
        if (event is VoiceEvent.HardReset) return VoiceState.IDLE

        return when (current) {
            VoiceState.IDLE -> when (event) {
                is VoiceEvent.WakeWordDetected -> VoiceState.LISTENING
                is VoiceEvent.ButtonPressed -> VoiceState.LISTENING
                else -> null
            }

            VoiceState.LISTENING -> when (event) {
                is VoiceEvent.SilenceTimeout -> VoiceState.IDLE
                is VoiceEvent.SpeechEnd -> VoiceState.PROCESSING
                is VoiceEvent.ErrorOccurred -> VoiceState.ERROR
                else -> null
            }

            VoiceState.PROCESSING -> when (event) {
                is VoiceEvent.IntentReady -> VoiceState.GENERATING
                is VoiceEvent.ErrorOccurred -> VoiceState.ERROR
                else -> null
            }

            VoiceState.GENERATING -> when (event) {
                is VoiceEvent.FirstAudioChunk -> VoiceState.RESPONDING
                is VoiceEvent.ConfirmRequired -> VoiceState.CONFIRMING
                is VoiceEvent.BargeIn -> VoiceState.LISTENING
                is VoiceEvent.SystemInterrupt -> VoiceState.SUSPENDED
                is VoiceEvent.ErrorOccurred -> VoiceState.ERROR
                else -> null
            }

            VoiceState.RESPONDING -> when (event) {
                is VoiceEvent.TtsComplete -> VoiceState.IDLE
                is VoiceEvent.BargeIn -> VoiceState.LISTENING
                is VoiceEvent.SystemInterrupt -> VoiceState.SUSPENDED
                is VoiceEvent.ErrorOccurred -> VoiceState.ERROR
                else -> null
            }

            VoiceState.CONFIRMING -> when (event) {
                is VoiceEvent.UserConfirmed -> VoiceState.IDLE
                is VoiceEvent.UserCancelled -> VoiceState.IDLE
                is VoiceEvent.ConfirmTimeout -> VoiceState.IDLE
                is VoiceEvent.BargeIn -> VoiceState.IDLE
                else -> null
            }

            VoiceState.SUSPENDED -> when (event) {
                is VoiceEvent.AudioFocusGained -> VoiceState.IDLE
                else -> null
            }

            VoiceState.ERROR -> when (event) {
                is VoiceEvent.ErrorTimeout -> VoiceState.IDLE
                else -> null
            }
        }
    }

    /**
     * Perform side effects when transitioning between states.
     * This includes cancelling ongoing STT/TTS/LLM operations on barge-in,
     * stopping services on system interrupt, etc.
     */
    private fun performSideEffects(from: VoiceState, to: VoiceState, event: VoiceEvent) {
        // Cancel error recovery job if leaving ERROR by any means
        if (from == VoiceState.ERROR) {
            errorRecoveryJob?.cancel()
            errorRecoveryJob = null
        }

        when (event) {
            is VoiceEvent.BargeIn -> {
                // Cancel LLM stream + TTS + clear audio queue
                ttsService.stop()
                sttService.stopListening()
                Log.d(TAG, "Barge-in: cancelled TTS and cleared audio queue")
            }

            is VoiceEvent.SystemInterrupt -> {
                // Stop all audio output
                ttsService.stop()
                Log.d(TAG, "System interrupt: ${event.reason}")
            }

            is VoiceEvent.HardReset -> {
                // Full cleanup
                ttsService.stop()
                sttService.stopListening()
                errorRecoveryJob?.cancel()
                errorRecoveryJob = null
                Log.d(TAG, "Hard reset: all services stopped")
            }

            is VoiceEvent.SilenceTimeout -> {
                sttService.stopListening()
                Log.d(TAG, "Silence timeout: stopped listening")
            }

            else -> { /* No special side effects */ }
        }
    }

    /** Schedule auto-recovery from ERROR state after 3 seconds. */
    private fun scheduleErrorRecovery() {
        errorRecoveryJob?.cancel()
        errorRecoveryJob = scope.launch {
            delay(ERROR_RECOVERY_DELAY_MS)
            handleTransition(VoiceEvent.ErrorTimeout())
        }
    }

    private fun logTransition(from: VoiceState, to: VoiceState, event: VoiceEvent) {
        Log.i(TAG, "Transition: $from -> $to [${event::class.simpleName}]")
    }

    private fun notifyListeners(from: VoiceState, to: VoiceState, event: VoiceEvent) {
        transitionListeners.forEach { listener ->
            try {
                listener(from, to, event)
            } catch (e: Exception) {
                Log.e(TAG, "Transition listener error", e)
            }
        }
    }
}

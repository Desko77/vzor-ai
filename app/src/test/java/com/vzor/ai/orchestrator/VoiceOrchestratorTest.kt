package com.vzor.ai.orchestrator

import app.cash.turbine.test
import com.vzor.ai.domain.model.VoiceEvent
import com.vzor.ai.domain.model.VoiceState
import com.vzor.ai.domain.model.VzorIntent
import com.vzor.ai.domain.model.IntentType
import com.vzor.ai.speech.SttService
import com.vzor.ai.tts.TtsService
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceOrchestratorTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var sttService: SttService
    private lateinit var ttsService: TtsService
    private lateinit var intentClassifier: IntentClassifier
    private lateinit var orchestrator: VoiceOrchestrator

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sttService = mockk(relaxed = true)
        ttsService = mockk(relaxed = true)
        intentClassifier = mockk(relaxed = true)
        orchestrator = VoiceOrchestrator(sttService, ttsService, intentClassifier)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- 1. Happy path ---

    @Test
    fun `happy path IDLE to RESPONDING to IDLE`() = runTest {
        orchestrator.state.test {
            assertEquals(VoiceState.IDLE, awaitItem())

            orchestrator.onEvent(VoiceEvent.WakeWordDetected())
            assertEquals(VoiceState.LISTENING, awaitItem())

            orchestrator.onEvent(VoiceEvent.SpeechEnd("Привет", 0.9f))
            assertEquals(VoiceState.PROCESSING, awaitItem())

            val intent = VzorIntent(IntentType.GENERAL_QUESTION, 0.8f)
            orchestrator.onEvent(VoiceEvent.IntentReady(intent))
            assertEquals(VoiceState.GENERATING, awaitItem())

            orchestrator.onEvent(VoiceEvent.FirstAudioChunk)
            assertEquals(VoiceState.RESPONDING, awaitItem())

            orchestrator.onEvent(VoiceEvent.TtsComplete)
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `button press starts listening`() = runTest {
        orchestrator.state.test {
            assertEquals(VoiceState.IDLE, awaitItem())

            orchestrator.onEvent(VoiceEvent.ButtonPressed)
            assertEquals(VoiceState.LISTENING, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- 2. Barge-in from GENERATING ---

    @Test
    fun `barge-in from GENERATING transitions to LISTENING`() = runTest {
        orchestrator.state.test {
            skipItems(1) // IDLE
            driveToState(VoiceState.GENERATING)
            skipItems(3) // LISTENING, PROCESSING, GENERATING

            orchestrator.onEvent(VoiceEvent.BargeIn)
            assertEquals(VoiceState.LISTENING, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        verify { ttsService.stop() }
        verify { sttService.stopListening() }
    }

    // --- 3. Barge-in from RESPONDING ---

    @Test
    fun `barge-in from RESPONDING transitions to LISTENING`() = runTest {
        orchestrator.state.test {
            skipItems(1) // IDLE
            driveToState(VoiceState.RESPONDING)
            skipItems(4) // LISTENING, PROCESSING, GENERATING, RESPONDING

            orchestrator.onEvent(VoiceEvent.BargeIn)
            assertEquals(VoiceState.LISTENING, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        verify { ttsService.stop() }
    }

    // --- 4. Barge-in from CONFIRMING ---

    @Test
    fun `barge-in from CONFIRMING transitions to IDLE`() = runTest {
        orchestrator.state.test {
            skipItems(1) // IDLE
            driveToState(VoiceState.CONFIRMING)
            skipItems(4) // LISTENING, PROCESSING, GENERATING, CONFIRMING

            orchestrator.onEvent(VoiceEvent.BargeIn)
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- 5. Invalid transitions ---

    @Test
    fun `invalid event in IDLE is ignored`() = runTest {
        orchestrator.state.test {
            assertEquals(VoiceState.IDLE, awaitItem())

            // SpeechEnd is not valid from IDLE
            orchestrator.onEvent(VoiceEvent.SpeechEnd("test", 0.9f))
            // IntentReady is not valid from IDLE
            orchestrator.onEvent(VoiceEvent.IntentReady(VzorIntent(IntentType.GENERAL_QUESTION, 0.5f)))
            // TtsComplete is not valid from IDLE
            orchestrator.onEvent(VoiceEvent.TtsComplete)

            // State should remain IDLE — no new emissions
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- 6. HardReset from any state ---

    @Test
    fun `hard reset from LISTENING returns to IDLE`() = runTest {
        orchestrator.state.test {
            skipItems(1) // IDLE
            orchestrator.onEvent(VoiceEvent.WakeWordDetected())
            assertEquals(VoiceState.LISTENING, awaitItem())

            orchestrator.onEvent(VoiceEvent.HardReset)
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        verify { ttsService.stop() }
        verify { sttService.stopListening() }
    }

    @Test
    fun `hard reset from GENERATING returns to IDLE`() = runTest {
        orchestrator.state.test {
            skipItems(1) // IDLE
            driveToState(VoiceState.GENERATING)
            skipItems(3) // LISTENING, PROCESSING, GENERATING

            orchestrator.onEvent(VoiceEvent.HardReset)
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hard reset from SUSPENDED returns to IDLE`() = runTest {
        orchestrator.state.test {
            skipItems(1) // IDLE
            driveToState(VoiceState.GENERATING)
            skipItems(3) // LISTENING, PROCESSING, GENERATING

            orchestrator.onEvent(VoiceEvent.SystemInterrupt("incoming_call"))
            assertEquals(VoiceState.SUSPENDED, awaitItem())

            orchestrator.onEvent(VoiceEvent.HardReset)
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hard reset from ERROR returns to IDLE`() = runTest {
        orchestrator.state.test {
            skipItems(1) // IDLE
            orchestrator.onEvent(VoiceEvent.WakeWordDetected())
            assertEquals(VoiceState.LISTENING, awaitItem())

            orchestrator.onEvent(VoiceEvent.ErrorOccurred(RuntimeException("test")))
            assertEquals(VoiceState.ERROR, awaitItem())

            orchestrator.onEvent(VoiceEvent.HardReset)
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- 7. System interrupt and audio focus ---

    @Test
    fun `system interrupt from GENERATING to SUSPENDED`() = runTest {
        orchestrator.state.test {
            skipItems(1)
            driveToState(VoiceState.GENERATING)
            skipItems(3)

            orchestrator.onEvent(VoiceEvent.SystemInterrupt("incoming_call"))
            assertEquals(VoiceState.SUSPENDED, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        verify { ttsService.stop() }
    }

    @Test
    fun `audio focus gained from SUSPENDED returns to IDLE`() = runTest {
        orchestrator.state.test {
            skipItems(1)
            driveToState(VoiceState.GENERATING)
            skipItems(3)

            orchestrator.onEvent(VoiceEvent.SystemInterrupt("incoming_call"))
            assertEquals(VoiceState.SUSPENDED, awaitItem())

            orchestrator.onEvent(VoiceEvent.AudioFocusGained)
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `system interrupt from RESPONDING to SUSPENDED`() = runTest {
        orchestrator.state.test {
            skipItems(1)
            driveToState(VoiceState.RESPONDING)
            skipItems(4)

            orchestrator.onEvent(VoiceEvent.SystemInterrupt("audio_focus_loss"))
            assertEquals(VoiceState.SUSPENDED, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- 8. ERROR and auto-recovery ---

    @Test
    fun `error from LISTENING transitions to ERROR`() = runTest {
        orchestrator.state.test {
            skipItems(1)
            orchestrator.onEvent(VoiceEvent.WakeWordDetected())
            assertEquals(VoiceState.LISTENING, awaitItem())

            orchestrator.onEvent(VoiceEvent.ErrorOccurred(RuntimeException("mic failed")))
            assertEquals(VoiceState.ERROR, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `error from PROCESSING transitions to ERROR`() = runTest {
        orchestrator.state.test {
            skipItems(1)
            orchestrator.onEvent(VoiceEvent.WakeWordDetected())
            skipItems(1)
            orchestrator.onEvent(VoiceEvent.SpeechEnd("test", 0.9f))
            assertEquals(VoiceState.PROCESSING, awaitItem())

            orchestrator.onEvent(VoiceEvent.ErrorOccurred(RuntimeException("stt failed")))
            assertEquals(VoiceState.ERROR, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `error timeout returns to IDLE`() = runTest {
        orchestrator.state.test {
            skipItems(1)
            orchestrator.onEvent(VoiceEvent.WakeWordDetected())
            skipItems(1)
            orchestrator.onEvent(VoiceEvent.ErrorOccurred(RuntimeException("test")))
            assertEquals(VoiceState.ERROR, awaitItem())

            orchestrator.onEvent(VoiceEvent.ErrorTimeout())
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- 9. CONFIRMING exit paths ---

    @Test
    fun `user confirmed from CONFIRMING returns to IDLE`() = runTest {
        orchestrator.state.test {
            skipItems(1)
            driveToState(VoiceState.CONFIRMING)
            skipItems(4)

            orchestrator.onEvent(VoiceEvent.UserConfirmed)
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `user cancelled from CONFIRMING returns to IDLE`() = runTest {
        orchestrator.state.test {
            skipItems(1)
            driveToState(VoiceState.CONFIRMING)
            skipItems(4)

            orchestrator.onEvent(VoiceEvent.UserCancelled)
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `confirm timeout from CONFIRMING returns to IDLE`() = runTest {
        orchestrator.state.test {
            skipItems(1)
            driveToState(VoiceState.CONFIRMING)
            skipItems(4)

            orchestrator.onEvent(VoiceEvent.ConfirmTimeout())
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- 10. Silence timeout ---

    @Test
    fun `silence timeout from LISTENING returns to IDLE`() = runTest {
        orchestrator.state.test {
            skipItems(1)
            orchestrator.onEvent(VoiceEvent.WakeWordDetected())
            assertEquals(VoiceState.LISTENING, awaitItem())

            orchestrator.onEvent(VoiceEvent.SilenceTimeout())
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        verify { sttService.stopListening() }
    }

    // --- 11. Transition listener ---

    @Test
    fun `transition listener is called with correct arguments`() = runTest {
        val transitions = mutableListOf<Triple<VoiceState, VoiceState, VoiceEvent>>()
        orchestrator.addTransitionListener { from, to, event ->
            transitions.add(Triple(from, to, event))
        }

        orchestrator.onEvent(VoiceEvent.ButtonPressed)

        // Give time for event processing
        testScheduler.advanceUntilIdle()

        assertTrue(transitions.isNotEmpty())
        val (from, to, _) = transitions[0]
        assertEquals(VoiceState.IDLE, from)
        assertEquals(VoiceState.LISTENING, to)
    }

    // --- 12. Session management ---

    @Test
    fun `start session creates new session`() {
        val session = orchestrator.startSession()
        assertNotNull(session)
        assertNotNull(session.sessionId)
    }

    @Test
    fun `current session returns active session`() {
        assertNull(orchestrator.currentSession())
        val session = orchestrator.startSession()
        assertEquals(session, orchestrator.currentSession())
    }

    @Test
    fun `end session clears session and resets to IDLE`() = runTest {
        orchestrator.startSession()
        assertNotNull(orchestrator.currentSession())

        orchestrator.endSession()

        testScheduler.advanceUntilIdle()

        assertNull(orchestrator.currentSession())
        assertEquals(VoiceState.IDLE, orchestrator.state.value)
    }

    // --- 13. ErrorOccurred from GENERATING and RESPONDING ---

    @Test
    fun `error from GENERATING transitions to ERROR`() = runTest {
        orchestrator.state.test {
            skipItems(1) // IDLE
            driveToState(VoiceState.GENERATING)
            skipItems(3) // LISTENING, PROCESSING, GENERATING

            orchestrator.onEvent(VoiceEvent.ErrorOccurred(RuntimeException("LLM timeout")))
            assertEquals(VoiceState.ERROR, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `error from RESPONDING transitions to ERROR`() = runTest {
        orchestrator.state.test {
            skipItems(1) // IDLE
            driveToState(VoiceState.RESPONDING)
            skipItems(4) // LISTENING, PROCESSING, GENERATING, RESPONDING

            orchestrator.onEvent(VoiceEvent.ErrorOccurred(RuntimeException("TTS playback failed")))
            assertEquals(VoiceState.ERROR, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- 14. HardReset from remaining states ---

    @Test
    fun `hard reset from IDLE stays IDLE`() = runTest {
        orchestrator.state.test {
            assertEquals(VoiceState.IDLE, awaitItem())

            orchestrator.onEvent(VoiceEvent.HardReset)
            // HardReset resolves to IDLE, but since currentState == newState, no emission
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hard reset from PROCESSING returns to IDLE`() = runTest {
        orchestrator.state.test {
            skipItems(1) // IDLE
            driveToState(VoiceState.PROCESSING)
            skipItems(2) // LISTENING, PROCESSING

            orchestrator.onEvent(VoiceEvent.HardReset)
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        verify { ttsService.stop() }
        verify { sttService.stopListening() }
    }

    @Test
    fun `hard reset from RESPONDING returns to IDLE`() = runTest {
        orchestrator.state.test {
            skipItems(1) // IDLE
            driveToState(VoiceState.RESPONDING)
            skipItems(4) // LISTENING, PROCESSING, GENERATING, RESPONDING

            orchestrator.onEvent(VoiceEvent.HardReset)
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        verify { ttsService.stop() }
    }

    @Test
    fun `hard reset from CONFIRMING returns to IDLE`() = runTest {
        orchestrator.state.test {
            skipItems(1) // IDLE
            driveToState(VoiceState.CONFIRMING)
            skipItems(4) // LISTENING, PROCESSING, GENERATING, CONFIRMING

            orchestrator.onEvent(VoiceEvent.HardReset)
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- 15. Auto-recovery ErrorTimeout via delay ---

    @Test
    fun `error auto-recovery fires after 3 seconds`() = runTest {
        orchestrator.state.test {
            skipItems(1) // IDLE
            orchestrator.onEvent(VoiceEvent.WakeWordDetected())
            assertEquals(VoiceState.LISTENING, awaitItem())

            orchestrator.onEvent(VoiceEvent.ErrorOccurred(RuntimeException("test")))
            assertEquals(VoiceState.ERROR, awaitItem())

            // Auto-recovery should fire after 3000ms
            testScheduler.advanceTimeBy(3100)

            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- 16. Race conditions ---

    @Test
    fun `double-tap ButtonPressed stays in LISTENING`() = runTest {
        orchestrator.state.test {
            skipItems(1) // IDLE

            orchestrator.onEvent(VoiceEvent.ButtonPressed)
            assertEquals(VoiceState.LISTENING, awaitItem())

            // Second ButtonPressed — invalid from LISTENING, should be ignored
            orchestrator.onEvent(VoiceEvent.ButtonPressed)
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `rapid BargeIn then HardReset from GENERATING`() = runTest {
        orchestrator.state.test {
            skipItems(1) // IDLE
            driveToState(VoiceState.GENERATING)
            skipItems(3) // LISTENING, PROCESSING, GENERATING

            // Rapid sequence: BargeIn → HardReset
            orchestrator.onEvent(VoiceEvent.BargeIn)
            orchestrator.onEvent(VoiceEvent.HardReset)

            // BargeIn: GENERATING → LISTENING
            assertEquals(VoiceState.LISTENING, awaitItem())
            // HardReset: LISTENING → IDLE
            assertEquals(VoiceState.IDLE, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Helpers ---

    /**
     * Drive the FSM to a target state via valid transitions.
     * Must be called inside a Turbine test block — caller is responsible
     * for skipping the emitted intermediate states.
     */
    private fun driveToState(target: VoiceState) {
        val intent = VzorIntent(IntentType.GENERAL_QUESTION, 0.8f)
        when (target) {
            VoiceState.LISTENING -> {
                orchestrator.onEvent(VoiceEvent.WakeWordDetected())
            }
            VoiceState.PROCESSING -> {
                orchestrator.onEvent(VoiceEvent.WakeWordDetected())
                orchestrator.onEvent(VoiceEvent.SpeechEnd("тест", 0.9f))
            }
            VoiceState.GENERATING -> {
                orchestrator.onEvent(VoiceEvent.WakeWordDetected())
                orchestrator.onEvent(VoiceEvent.SpeechEnd("тест", 0.9f))
                orchestrator.onEvent(VoiceEvent.IntentReady(intent))
            }
            VoiceState.RESPONDING -> {
                orchestrator.onEvent(VoiceEvent.WakeWordDetected())
                orchestrator.onEvent(VoiceEvent.SpeechEnd("тест", 0.9f))
                orchestrator.onEvent(VoiceEvent.IntentReady(intent))
                orchestrator.onEvent(VoiceEvent.FirstAudioChunk)
            }
            VoiceState.CONFIRMING -> {
                orchestrator.onEvent(VoiceEvent.WakeWordDetected())
                orchestrator.onEvent(VoiceEvent.SpeechEnd("тест", 0.9f))
                orchestrator.onEvent(VoiceEvent.IntentReady(intent))
                orchestrator.onEvent(VoiceEvent.ConfirmRequired("call", "Позвонить маме?"))
            }
            else -> error("driveToState not implemented for $target")
        }
    }
}

package com.vzor.ai.orchestrator

import android.util.Log
import com.vzor.ai.domain.model.Message
import com.vzor.ai.domain.model.MessageRole
import com.vzor.ai.domain.repository.AiRepository
import com.vzor.ai.speech.SttResult
import com.vzor.ai.speech.SttService
import com.vzor.ai.tts.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Режим «Фокус на разговоре» (Use Case #13).
 *
 * AI слушает живой разговор через микрофон очков, собирает транскрипт
 * и по запросу пользователя (tap / voice command) предоставляет:
 * - Краткое саммари разговора
 * - Ключевые факты и имена
 * - Подсказки для ответа
 * - Перевод непонятных фраз
 *
 * Поток: Mic → STT (streaming) → Transcript buffer → LLM (on-demand) → TTS/UI
 */
@Singleton
class ConversationFocusManager @Inject constructor(
    private val sttService: SttService,
    private val aiRepository: AiRepository,
    private val ttsManager: TtsManager
) {
    companion object {
        private const val TAG = "ConversationFocus"

        /** Максимальное количество реплик в буфере транскрипта. */
        private const val MAX_TRANSCRIPT_ENTRIES = 100

        /** Минимальная длина реплики для добавления в транскрипт. */
        private const val MIN_UTTERANCE_LENGTH = 3

        private val TIME_FORMAT = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT)
    }

    private val _state = MutableStateFlow(ConversationFocusState())
    val state: StateFlow<ConversationFocusState> = _state.asStateFlow()

    private var focusScope: CoroutineScope? = null
    private var listeningJob: Job? = null

    // Буфер транскрипта разговора (доступ только через synchronized(lock))
    private val transcript = mutableListOf<TranscriptEntry>()
    private val lock = Any()

    /**
     * Начать режим фокуса — AI начинает слушать разговор.
     */
    fun startFocus() {
        stopFocus()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        focusScope = scope
        synchronized(lock) { transcript.clear() }

        _state.update {
            ConversationFocusState(isActive = true, status = FocusStatus.LISTENING)
        }

        listeningJob = scope.launch {
            try {
                collectTranscript()
            } catch (e: Exception) {
                Log.e(TAG, "Focus listening error", e)
                _state.update { it.copy(status = FocusStatus.ERROR, error = e.message) }
            }
        }

        Log.d(TAG, "Conversation Focus started")
    }

    /**
     * Остановить режим фокуса.
     */
    fun stopFocus() {
        listeningJob?.cancel()
        listeningJob = null
        sttService.stopListening()
        focusScope?.cancel()
        focusScope = null

        _state.update {
            ConversationFocusState(isActive = false, status = FocusStatus.IDLE)
        }
    }

    /**
     * Запросить саммари текущего разговора.
     */
    fun requestSummary() {
        val scope = focusScope ?: return
        scope.launch {
            _state.update { it.copy(status = FocusStatus.PROCESSING) }
            try {
                val summary = generateSummary()
                _state.update {
                    it.copy(
                        status = FocusStatus.LISTENING,
                        lastInsight = summary,
                        insightType = InsightType.SUMMARY
                    )
                }
                ttsManager.speak(summary)
            } catch (e: Exception) {
                Log.e(TAG, "Summary generation failed", e)
                _state.update { it.copy(status = FocusStatus.LISTENING) }
            }
        }
    }

    /**
     * Запросить ключевые факты из разговора.
     */
    fun requestKeyFacts() {
        val scope = focusScope ?: return
        scope.launch {
            _state.update { it.copy(status = FocusStatus.PROCESSING) }
            try {
                val facts = generateKeyFacts()
                _state.update {
                    it.copy(
                        status = FocusStatus.LISTENING,
                        lastInsight = facts,
                        insightType = InsightType.KEY_FACTS
                    )
                }
                ttsManager.speak(facts)
            } catch (e: Exception) {
                Log.e(TAG, "Key facts generation failed", e)
                _state.update { it.copy(status = FocusStatus.LISTENING) }
            }
        }
    }

    /**
     * Запросить подсказку для ответа в разговоре.
     */
    fun requestSuggestion(userHint: String? = null) {
        val scope = focusScope ?: return
        scope.launch {
            _state.update { it.copy(status = FocusStatus.PROCESSING) }
            try {
                val suggestion = generateSuggestion(userHint)
                _state.update {
                    it.copy(
                        status = FocusStatus.LISTENING,
                        lastInsight = suggestion,
                        insightType = InsightType.SUGGESTION
                    )
                }
                ttsManager.speak(suggestion)
            } catch (e: Exception) {
                Log.e(TAG, "Suggestion generation failed", e)
                _state.update { it.copy(status = FocusStatus.LISTENING) }
            }
        }
    }

    /**
     * Получить текущий транскрипт.
     */
    fun getTranscript(): List<TranscriptEntry> = synchronized(lock) { transcript.toList() }

    /**
     * Количество собранных реплик.
     */
    fun getTranscriptSize(): Int = synchronized(lock) { transcript.size }

    private suspend fun collectTranscript() {
        sttService.startListening().collect { result: SttResult ->
            if (result.isFinal && result.text.length >= MIN_UTTERANCE_LENGTH) {
                val entry = TranscriptEntry(
                    text = result.text.trim(),
                    timestamp = System.currentTimeMillis(),
                    language = result.language,
                    confidence = result.confidence
                )

                synchronized(lock) {
                    transcript.add(entry)
                    if (transcript.size > MAX_TRANSCRIPT_ENTRIES) {
                        transcript.removeAt(0)
                    }
                }

                _state.update {
                    it.copy(transcriptCount = transcript.size)
                }

                Log.d(TAG, "Transcript [${transcript.size}]: ${entry.text}")
            }
        }
    }

    private fun buildTranscriptText(): String {
        return synchronized(lock) {
            transcript.joinToString("\n") { entry ->
                "[${formatTime(entry.timestamp)}] ${entry.text}"
            }
        }
    }

    private suspend fun generateSummary(): String {
        val transcriptText = buildTranscriptText()
        if (transcriptText.isBlank()) return "Разговор пока не начался."

        val messages = listOf(
            Message(
                role = MessageRole.SYSTEM,
                content = "Ты помощник, который слушает разговор и делает краткие саммари. " +
                    "Отвечай на русском языке. Будь краток (2-3 предложения). " +
                    "Выдели главную тему и ключевые решения."
            ),
            Message(
                role = MessageRole.USER,
                content = "Вот транскрипт разговора:\n\n$transcriptText\n\nСделай краткое саммари."
            )
        )

        return aiRepository.sendMessage(messages).getOrThrow().trim()
    }

    private suspend fun generateKeyFacts(): String {
        val transcriptText = buildTranscriptText()
        if (transcriptText.isBlank()) return "Разговор пока не начался."

        val messages = listOf(
            Message(
                role = MessageRole.SYSTEM,
                content = "Ты помощник, который выделяет ключевые факты из разговора. " +
                    "Отвечай на русском. Формат: пронумерованный список. " +
                    "Включи: имена, даты, числа, решения, задачи."
            ),
            Message(
                role = MessageRole.USER,
                content = "Вот транскрипт разговора:\n\n$transcriptText\n\nВыдели ключевые факты."
            )
        )

        return aiRepository.sendMessage(messages).getOrThrow().trim()
    }

    private suspend fun generateSuggestion(userHint: String?): String {
        val transcriptText = buildTranscriptText()
        if (transcriptText.isBlank()) return "Разговор пока не начался."

        val hintPart = if (userHint != null) " Подсказка пользователя: $userHint" else ""

        val messages = listOf(
            Message(
                role = MessageRole.SYSTEM,
                content = "Ты помощник, который подсказывает что ответить в разговоре. " +
                    "Отвечай на русском. Предложи 1-2 варианта ответа, " +
                    "основываясь на контексте разговора. Будь кратким и естественным."
            ),
            Message(
                role = MessageRole.USER,
                content = "Вот транскрипт разговора:\n\n$transcriptText\n\n" +
                    "Что мне ответить?$hintPart"
            )
        )

        return aiRepository.sendMessage(messages).getOrThrow().trim()
    }

    private fun formatTime(timestamp: Long): String {
        return synchronized(TIME_FORMAT) { TIME_FORMAT.format(java.util.Date(timestamp)) }
    }
}

/**
 * Запись транскрипта разговора.
 */
data class TranscriptEntry(
    val text: String,
    val timestamp: Long,
    val language: String = "ru",
    val confidence: Float = 1.0f
)

/**
 * Статус режима фокуса.
 */
enum class FocusStatus {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR
}

/**
 * Тип полученного insight.
 */
enum class InsightType {
    SUMMARY,
    KEY_FACTS,
    SUGGESTION
}

/**
 * UI state для режима Conversation Focus.
 */
data class ConversationFocusState(
    val isActive: Boolean = false,
    val status: FocusStatus = FocusStatus.IDLE,
    val transcriptCount: Int = 0,
    val lastInsight: String? = null,
    val insightType: InsightType? = null,
    val error: String? = null
)

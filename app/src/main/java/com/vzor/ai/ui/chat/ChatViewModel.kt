package com.vzor.ai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vzor.ai.actions.ActionConfirmation
import com.vzor.ai.actions.ActionExecutor
import com.vzor.ai.actions.PendingAction
import com.vzor.ai.context.ContextManager
import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.domain.model.Conversation
import com.vzor.ai.domain.model.GlassesState
import com.vzor.ai.domain.model.Message
import com.vzor.ai.domain.model.MessageRole
import com.vzor.ai.domain.model.VoiceEvent
import com.vzor.ai.domain.model.VoiceState
import com.vzor.ai.domain.model.IntentType
import com.vzor.ai.domain.model.VzorIntent
import com.vzor.ai.domain.repository.AiRepository
import com.vzor.ai.domain.repository.ConversationRepository
import com.vzor.ai.domain.repository.VisionRepository
import com.vzor.ai.glasses.GlassesManager
import com.vzor.ai.vision.SharedImageHandler
import com.vzor.ai.vision.LiveCommentaryService
import com.vzor.ai.orchestrator.ConversationFocusManager
import com.vzor.ai.orchestrator.IntentClassifier
import com.vzor.ai.orchestrator.ToolCallProcessor
import com.vzor.ai.orchestrator.VoiceOrchestrator
import com.vzor.ai.speech.SttService
import com.vzor.ai.tts.TtsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val currentInput: String = "",
    val isRecording: Boolean = false,
    val glassesState: GlassesState = GlassesState.DISCONNECTED,
    val voiceState: VoiceState = VoiceState.IDLE,
    val currentConversation: Conversation? = null,
    val pendingAction: PendingAction? = null,
    val isLiveCommentaryActive: Boolean = false,
    val isConversationFocusActive: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val aiRepository: AiRepository,
    private val visionRepository: VisionRepository,
    private val conversationRepository: ConversationRepository,
    private val glassesManager: GlassesManager,
    private val voiceOrchestrator: VoiceOrchestrator,
    private val sttService: SttService,
    private val ttsManager: TtsManager,
    private val prefs: PreferencesManager,
    private val actionConfirmation: ActionConfirmation,
    private val actionExecutor: ActionExecutor,
    private val intentClassifier: IntentClassifier,
    private val contextManager: ContextManager,
    private val sharedImageHandler: SharedImageHandler,
    private val liveCommentaryService: LiveCommentaryService,
    private val conversationFocusManager: ConversationFocusManager,
    private val toolCallProcessor: ToolCallProcessor
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            glassesManager.state.collect { state ->
                _uiState.update { it.copy(glassesState = state) }
            }
        }
        viewModelScope.launch {
            voiceOrchestrator.state.collect { voiceState ->
                _uiState.update { it.copy(voiceState = voiceState) }
            }
        }
        // Наблюдаем за pending actions для показа диалога подтверждения
        viewModelScope.launch {
            actionConfirmation.pendingAction.collect { pending ->
                _uiState.update { it.copy(pendingAction = pending) }
            }
        }
        // Обработка фото, расшаренных через Android Share (Meta View → Vzor)
        viewModelScope.launch {
            sharedImageHandler.sharedImages.collect { imageBytes ->
                sendImageMessage(imageBytes)
            }
        }
        // Live commentary state
        viewModelScope.launch {
            liveCommentaryService.isActive.collect { active ->
                _uiState.update { it.copy(isLiveCommentaryActive = active) }
            }
        }
        // Conversation focus state
        viewModelScope.launch {
            conversationFocusManager.state.collect { focusState ->
                _uiState.update { it.copy(isConversationFocusActive = focusState.isActive) }
            }
        }
    }

    fun startNewConversation() {
        viewModelScope.launch {
            val conversation = conversationRepository.createConversation("Новый диалог")
            contextManager.clearSession()
            _uiState.update {
                it.copy(
                    messages = emptyList(),
                    currentConversation = conversation,
                    error = null
                )
            }
        }
    }

    fun loadConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepository.getMessages(conversationId).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }

    fun updateInput(text: String) {
        _uiState.update { it.copy(currentInput = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.currentInput.trim()
        if (text.isBlank()) return

        val conversationId = _uiState.value.currentConversation?.id ?: run {
            startNewConversation()
            _uiState.value.currentConversation?.id ?: return
        }

        val userMessage = Message(
            role = MessageRole.USER,
            content = text,
            conversationId = conversationId
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                currentInput = "",
                isLoading = true,
                error = null
            )
        }

        viewModelScope.launch {
            conversationRepository.saveMessage(userMessage)
            contextManager.addToSession(userMessage)

            // Классификация intent — если действие, выполняем без LLM
            val intent = intentClassifier.classify(text)
            if (intent.type != IntentType.GENERAL_QUESTION &&
                intent.type != IntentType.UNKNOWN) {
                handleActionIntent(intent, conversationId)
                return@launch
            }

            // Обычный LLM запрос с контекстом из ContextManager
            val systemPrompt = prefs.systemPrompt.first()
            val messagesForApi = buildList {
                add(Message(role = MessageRole.SYSTEM, content = systemPrompt))
                addAll(contextManager.getSessionContext())
            }

            voiceOrchestrator.onEvent(VoiceEvent.IntentReady(
                VzorIntent(type = IntentType.GENERAL_QUESTION, confidence = 1.0f)
            ))

            val responseBuilder = StringBuilder()

            // Tool-augmented streaming: Claude получает описания инструментов,
            // ToolCallProcessor перехватывает tool_use и выполняет через ToolRegistry
            val tools = toolCallProcessor.buildClaudeTools()
            val chunksFlow = aiRepository.streamWithTools(messagesForApi, tools)
            val textFlow = toolCallProcessor.processStream(chunksFlow)

            textFlow
                .catch { e ->
                    voiceOrchestrator.onEvent(VoiceEvent.ErrorOccurred(e))
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message ?: "Ошибка")
                    }
                }
                .collect { chunk ->
                    responseBuilder.append(chunk)

                    // Streaming TTS: feed tokens during generation
                    if (_uiState.value.glassesState == GlassesState.CONNECTED) {
                        ttsManager.onToken(chunk)
                    }

                    val assistantMessage = Message(
                        role = MessageRole.ASSISTANT,
                        content = responseBuilder.toString(),
                        conversationId = conversationId
                    )

                    _uiState.update { state ->
                        val messages = state.messages.toMutableList()
                        val existingIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
                        if (existingIndex >= 0 && messages[existingIndex].conversationId == conversationId) {
                            messages[existingIndex] = assistantMessage
                        } else {
                            messages.add(assistantMessage)
                        }
                        state.copy(messages = messages, isLoading = false)
                    }
                }

            // Flush remaining TTS buffer after streaming completes
            if (_uiState.value.glassesState == GlassesState.CONNECTED) {
                ttsManager.onStreamEnd()
                voiceOrchestrator.onEvent(VoiceEvent.TtsComplete)
            }

            // Save final assistant message + context
            val finalMessage = _uiState.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
            if (finalMessage != null) {
                conversationRepository.saveMessage(finalMessage)
                contextManager.addToSession(finalMessage)
            }
        }
    }

    /**
     * Обработка action intent (звонок, сообщение, навигация и т.д.).
     * Если требуется подтверждение — показывает диалог.
     */
    private suspend fun handleActionIntent(intent: VzorIntent, conversationId: String) {
        // Специальные интенты обрабатываем напрямую
        when (intent.type) {
            IntentType.CAPTURE_PHOTO -> {
                handleCapturePhotoIntent(conversationId)
                return
            }
            IntentType.LIVE_COMMENTARY -> {
                handleLiveCommentaryIntent(conversationId)
                return
            }
            IntentType.CONVERSATION_FOCUS -> {
                handleConversationFocusIntent(conversationId)
                return
            }
            else -> { /* продолжаем стандартную обработку */ }
        }

        if (actionConfirmation.requiresConfirmation(intent)) {
            voiceOrchestrator.onEvent(VoiceEvent.ConfirmRequired(
                action = intent.type.name,
                description = "Подтвердите действие"
            ))

            val confirmed = actionConfirmation.requestConfirmation(intent)
            if (!confirmed) {
                voiceOrchestrator.onEvent(VoiceEvent.UserCancelled)
                addAssistantMessage("Действие отменено.", conversationId)
                return
            }
            voiceOrchestrator.onEvent(VoiceEvent.UserConfirmed)
        }

        val result = actionExecutor.execute(intent)
        addAssistantMessage(result.message, conversationId)

        if (_uiState.value.glassesState == GlassesState.CONNECTED) {
            ttsManager.speak(result.message)
        }
    }

    /** UC#11: Фото hands-free — голосовая команда «сфотографируй». */
    private suspend fun handleCapturePhotoIntent(conversationId: String) {
        if (_uiState.value.glassesState != GlassesState.CONNECTED) {
            addAssistantMessage("Очки не подключены. Подключите для съёмки.", conversationId)
            return
        }
        addAssistantMessage("Фотографирую...", conversationId)
        val imageBytes = glassesManager.capturePhoto()
        if (imageBytes != null) {
            sendImageMessage(imageBytes, "Что ты видишь на этом фото? Опиши подробно на русском.")
        } else {
            addAssistantMessage("Не удалось сделать фото.", conversationId)
        }
    }

    /** UC#6: Live AI commentary — включить/выключить режим комментария. */
    private suspend fun handleLiveCommentaryIntent(conversationId: String) {
        if (liveCommentaryService.isActive.value) {
            liveCommentaryService.stop()
            addAssistantMessage("Режим комментария выключен.", conversationId)
        } else {
            if (_uiState.value.glassesState != GlassesState.CONNECTED) {
                addAssistantMessage("Очки не подключены. Подключите для режима комментария.", conversationId)
                return
            }
            liveCommentaryService.start(
                captureFrame = { glassesManager.capturePhoto() }
            )
            addAssistantMessage("Режим живого комментария включён. Скажите «выключи комментарий» для остановки.", conversationId)
        }
    }

    /** UC#13: Conversation Focus — включить/выключить режим фокуса на разговоре. */
    private suspend fun handleConversationFocusIntent(conversationId: String) {
        if (conversationFocusManager.state.value.isActive) {
            // Если уже активен — запросить саммари перед остановкой
            val transcriptSize = conversationFocusManager.getTranscriptSize()
            if (transcriptSize > 0) {
                addAssistantMessage("Подготавливаю саммари разговора...", conversationId)
                conversationFocusManager.requestSummary()
            }
            conversationFocusManager.stopFocus()
            addAssistantMessage("Режим фокуса выключен.", conversationId)
        } else {
            conversationFocusManager.startFocus()
            addAssistantMessage(
                "Режим фокуса включён — слушаю разговор. " +
                "Скажите «саммари разговора» или «ключевые моменты» для получения информации.",
                conversationId
            )
        }
    }

    private suspend fun addAssistantMessage(text: String, conversationId: String) {
        val msg = Message(
            role = MessageRole.ASSISTANT,
            content = text,
            conversationId = conversationId
        )
        _uiState.update { it.copy(messages = it.messages + msg, isLoading = false) }
        conversationRepository.saveMessage(msg)
        contextManager.addToSession(msg)
    }

    fun sendImageMessage(imageBytes: ByteArray, prompt: String = "Что ты видишь на этом изображении? Опиши подробно на русском.") {
        val conversationId = _uiState.value.currentConversation?.id ?: return

        val userMessage = Message(
            role = MessageRole.USER,
            content = prompt,
            imageData = imageBytes,
            conversationId = conversationId
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                isLoading = true,
                error = null
            )
        }

        viewModelScope.launch {
            conversationRepository.saveMessage(userMessage)

            visionRepository.analyzeImage(imageBytes, prompt)
                .onSuccess { response ->
                    val assistantMessage = Message(
                        role = MessageRole.ASSISTANT,
                        content = response,
                        conversationId = conversationId
                    )
                    _uiState.update {
                        it.copy(
                            messages = it.messages + assistantMessage,
                            isLoading = false
                        )
                    }
                    conversationRepository.saveMessage(assistantMessage)

                    if (_uiState.value.glassesState == GlassesState.CONNECTED) {
                        ttsManager.speak(response)
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = e.message)
                    }
                }
        }
    }

    fun toggleRecording() {
        if (_uiState.value.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        _uiState.update { it.copy(isRecording = true) }
        voiceOrchestrator.onEvent(VoiceEvent.ButtonPressed)

        viewModelScope.launch {
            sttService.startListening()
                .catch { e ->
                    voiceOrchestrator.onEvent(VoiceEvent.ErrorOccurred(e))
                    _uiState.update { it.copy(isRecording = false, error = e.message) }
                }
                .collect { result ->
                    _uiState.update { it.copy(currentInput = result.text) }
                }
        }
    }

    private fun stopRecording() {
        sttService.stopListening()
        _uiState.update { it.copy(isRecording = false) }

        val text = _uiState.value.currentInput
        if (text.isNotBlank()) {
            voiceOrchestrator.onEvent(VoiceEvent.SpeechEnd(transcript = text))
            sendMessage()
        } else {
            voiceOrchestrator.onEvent(VoiceEvent.SilenceTimeout())
        }
    }

    /** Подтвердить pending action из UI. */
    fun confirmAction() {
        actionConfirmation.confirm()
    }

    /** Отклонить pending action из UI. */
    fun denyAction() {
        actionConfirmation.deny()
    }

    fun connectGlasses() {
        viewModelScope.launch {
            glassesManager.connect()
        }
    }

    fun disconnectGlasses() {
        viewModelScope.launch {
            glassesManager.disconnect()
        }
    }

    fun capturePhoto() {
        viewModelScope.launch {
            glassesManager.capturePhoto()?.let { imageBytes ->
                sendImageMessage(imageBytes)
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }
}

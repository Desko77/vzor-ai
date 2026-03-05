package com.vzor.ai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.domain.model.Conversation
import com.vzor.ai.domain.model.GlassesState
import com.vzor.ai.domain.model.Message
import com.vzor.ai.domain.model.MessageRole
import com.vzor.ai.domain.model.VoiceState
import com.vzor.ai.domain.repository.AiRepository
import com.vzor.ai.domain.repository.ConversationRepository
import com.vzor.ai.domain.repository.VisionRepository
import com.vzor.ai.glasses.GlassesManager
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
    val currentConversation: Conversation? = null
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
    private val prefs: PreferencesManager
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
    }

    fun startNewConversation() {
        viewModelScope.launch {
            val conversation = conversationRepository.createConversation("Новый диалог")
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

            val systemPrompt = prefs.systemPrompt.first()
            val messagesForApi = buildList {
                add(Message(role = MessageRole.SYSTEM, content = systemPrompt))
                addAll(_uiState.value.messages)
            }

            val responseBuilder = StringBuilder()

            aiRepository.streamMessage(messagesForApi)
                .catch { e ->
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
            }

            // Save final assistant message
            val finalMessage = _uiState.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
            if (finalMessage != null) {
                conversationRepository.saveMessage(finalMessage)
            }
        }
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
        viewModelScope.launch {
            sttService.startListening()
                .catch { e ->
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
        if (_uiState.value.currentInput.isNotBlank()) {
            sendMessage()
        }
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

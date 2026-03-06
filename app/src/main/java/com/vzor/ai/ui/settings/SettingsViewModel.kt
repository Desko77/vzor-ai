package com.vzor.ai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.domain.model.AiProvider
import com.vzor.ai.domain.model.SttProvider
import com.vzor.ai.domain.model.TtsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val aiProvider: AiProvider = AiProvider.DEFAULT,
    val geminiApiKey: String = "",
    val claudeApiKey: String = "",
    val openAiApiKey: String = "",
    val glmApiKey: String = "",
    val localAiHost: String = "",
    val tavilyApiKey: String = "",
    val sttProvider: SttProvider = SttProvider.WHISPER,
    val ttsProvider: TtsProvider = TtsProvider.GOOGLE,
    val yandexApiKey: String = "",
    val systemPrompt: String = "",
    val developerMode: Boolean = false,
    val homeSsid: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                prefs.aiProvider,
                prefs.geminiApiKey,
                prefs.claudeApiKey,
                prefs.openAiApiKey,
                prefs.sttProvider
            ) { values ->
                values
            }.collect { values ->
                _uiState.update { current ->
                    current.copy(
                        aiProvider = values[0] as AiProvider,
                        geminiApiKey = values[1] as String,
                        claudeApiKey = values[2] as String,
                        openAiApiKey = values[3] as String,
                        sttProvider = values[4] as SttProvider
                    )
                }
            }
        }

        viewModelScope.launch {
            combine(
                prefs.ttsProvider,
                prefs.yandexApiKey,
                prefs.systemPrompt
            ) { ttsProvider, yandexKey, systemPrompt ->
                Triple(ttsProvider, yandexKey, systemPrompt)
            }.collect { (ttsProvider, yandexKey, systemPrompt) ->
                _uiState.update {
                    it.copy(
                        ttsProvider = ttsProvider,
                        yandexApiKey = yandexKey,
                        systemPrompt = systemPrompt
                    )
                }
            }
        }

        viewModelScope.launch {
            combine(
                prefs.glmApiKey,
                prefs.localAiHostOverride,
                prefs.tavilyApiKey,
                prefs.developerMode,
                prefs.homeSsid
            ) { values ->
                values
            }.collect { values ->
                _uiState.update { current ->
                    current.copy(
                        glmApiKey = values[0] as String,
                        localAiHost = values[1] as String,
                        tavilyApiKey = values[2] as String,
                        developerMode = values[3] as Boolean,
                        homeSsid = values[4] as String
                    )
                }
            }
        }
    }

    fun setAiProvider(provider: AiProvider) {
        viewModelScope.launch { prefs.setAiProvider(provider) }
    }

    fun setGeminiApiKey(key: String) {
        viewModelScope.launch { prefs.setGeminiApiKey(key) }
    }

    fun setClaudeApiKey(key: String) {
        viewModelScope.launch { prefs.setClaudeApiKey(key) }
    }

    fun setOpenAiApiKey(key: String) {
        viewModelScope.launch { prefs.setOpenAiApiKey(key) }
    }

    fun setSttProvider(provider: SttProvider) {
        viewModelScope.launch { prefs.setSttProvider(provider) }
    }

    fun setTtsProvider(provider: TtsProvider) {
        viewModelScope.launch { prefs.setTtsProvider(provider) }
    }

    fun setYandexApiKey(key: String) {
        viewModelScope.launch { prefs.setYandexApiKey(key) }
    }

    fun setSystemPrompt(prompt: String) {
        viewModelScope.launch { prefs.setSystemPrompt(prompt) }
    }

    fun setGlmApiKey(key: String) {
        viewModelScope.launch { prefs.setGlmApiKey(key) }
    }

    fun setLocalAiHost(host: String) {
        viewModelScope.launch { prefs.setLocalAiHost(host) }
    }

    fun setTavilyApiKey(key: String) {
        viewModelScope.launch { prefs.setTavilyApiKey(key) }
    }

    fun setDeveloperMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setDeveloperMode(enabled) }
    }

    fun setHomeSsid(ssid: String) {
        viewModelScope.launch { prefs.setHomeSsid(ssid) }
    }
}

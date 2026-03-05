package com.vzor.ai.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vzor.ai.domain.model.AiProvider
import com.vzor.ai.domain.model.SttProvider
import com.vzor.ai.domain.model.TtsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vzor_settings")

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    companion object {
        private val KEY_AI_PROVIDER = stringPreferencesKey("ai_provider")
        private val KEY_GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        private val KEY_CLAUDE_API_KEY = stringPreferencesKey("claude_api_key")
        private val KEY_OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        private val KEY_STT_PROVIDER = stringPreferencesKey("stt_provider")
        private val KEY_TTS_PROVIDER = stringPreferencesKey("tts_provider")
        private val KEY_YANDEX_API_KEY = stringPreferencesKey("yandex_api_key")
        private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        private val KEY_LOCAL_AI_HOST = stringPreferencesKey("local_ai_host")
        private val KEY_GLM_API_KEY = stringPreferencesKey("glm_api_key")
        private val KEY_TAVILY_API_KEY = stringPreferencesKey("tavily_api_key")
    }

    val aiProvider: Flow<AiProvider> = dataStore.data.map { prefs ->
        prefs[KEY_AI_PROVIDER]?.let { AiProvider.valueOf(it) } ?: AiProvider.DEFAULT
    }

    val geminiApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_GEMINI_API_KEY] ?: ""
    }

    val claudeApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_CLAUDE_API_KEY] ?: ""
    }

    val openAiApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_OPENAI_API_KEY] ?: ""
    }

    val sttProvider: Flow<SttProvider> = dataStore.data.map { prefs ->
        prefs[KEY_STT_PROVIDER]?.let { SttProvider.valueOf(it) } ?: SttProvider.WHISPER
    }

    val ttsProvider: Flow<TtsProvider> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_PROVIDER]?.let { TtsProvider.valueOf(it) } ?: TtsProvider.GOOGLE
    }

    val yandexApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_YANDEX_API_KEY] ?: ""
    }

    val systemPrompt: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_SYSTEM_PROMPT] ?: "Ты — полезный AI-ассистент. Отвечай на русском языке."
    }

    val localAiHostOverride: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_LOCAL_AI_HOST] ?: ""
    }

    val glmApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_GLM_API_KEY] ?: ""
    }

    val tavilyApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_TAVILY_API_KEY] ?: ""
    }

    suspend fun setAiProvider(provider: AiProvider) {
        dataStore.edit { it[KEY_AI_PROVIDER] = provider.name }
    }

    suspend fun setGeminiApiKey(key: String) {
        dataStore.edit { it[KEY_GEMINI_API_KEY] = key }
    }

    suspend fun setClaudeApiKey(key: String) {
        dataStore.edit { it[KEY_CLAUDE_API_KEY] = key }
    }

    suspend fun setOpenAiApiKey(key: String) {
        dataStore.edit { it[KEY_OPENAI_API_KEY] = key }
    }

    suspend fun setSttProvider(provider: SttProvider) {
        dataStore.edit { it[KEY_STT_PROVIDER] = provider.name }
    }

    suspend fun setTtsProvider(provider: TtsProvider) {
        dataStore.edit { it[KEY_TTS_PROVIDER] = provider.name }
    }

    suspend fun setYandexApiKey(key: String) {
        dataStore.edit { it[KEY_YANDEX_API_KEY] = key }
    }

    suspend fun setSystemPrompt(prompt: String) {
        dataStore.edit { it[KEY_SYSTEM_PROMPT] = prompt }
    }

    suspend fun setLocalAiHost(host: String) {
        dataStore.edit { it[KEY_LOCAL_AI_HOST] = host }
    }

    suspend fun setGlmApiKey(key: String) {
        dataStore.edit { it[KEY_GLM_API_KEY] = key }
    }

    suspend fun setTavilyApiKey(key: String) {
        dataStore.edit { it[KEY_TAVILY_API_KEY] = key }
    }
}

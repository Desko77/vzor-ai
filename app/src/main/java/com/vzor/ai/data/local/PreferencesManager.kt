package com.vzor.ai.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.vzor.ai.domain.model.AiProvider
import com.vzor.ai.domain.model.SttProvider
import com.vzor.ai.domain.model.TtsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vzor_settings")

// TODO: Migrate to SQLCipher when:
// - MemoryFact starts storing PII (medical, financial data)
// - Regulatory requirements (HIPAA, PCI-DSS) apply
// - User data export/portability is implemented

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    /**
     * Зашифрованное хранилище для API-ключей.
     * Использует Android Keystore для шифрования — ключи не читаются без root.
     */
    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "vzor_secure_prefs",
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private val KEY_AI_PROVIDER = stringPreferencesKey("ai_provider")
        private val KEY_STT_PROVIDER = stringPreferencesKey("stt_provider")
        private val KEY_TTS_PROVIDER = stringPreferencesKey("tts_provider")
        private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        private val KEY_LOCAL_AI_HOST = stringPreferencesKey("local_ai_host")

        // Ключи для EncryptedSharedPreferences (API keys)
        private const val ENCRYPTED_GEMINI_KEY = "gemini_api_key"
        private const val ENCRYPTED_CLAUDE_KEY = "claude_api_key"
        private const val ENCRYPTED_OPENAI_KEY = "openai_api_key"
        private const val ENCRYPTED_YANDEX_KEY = "yandex_api_key"
        private const val ENCRYPTED_GLM_KEY = "glm_api_key"
        private const val ENCRYPTED_TAVILY_KEY = "tavily_api_key"
    }

    // --- Настройки (DataStore, не чувствительные) ---

    val aiProvider: Flow<AiProvider> = dataStore.data.map { prefs ->
        prefs[KEY_AI_PROVIDER]?.let { AiProvider.valueOf(it) } ?: AiProvider.DEFAULT
    }

    val sttProvider: Flow<SttProvider> = dataStore.data.map { prefs ->
        prefs[KEY_STT_PROVIDER]?.let { SttProvider.valueOf(it) } ?: SttProvider.WHISPER
    }

    val ttsProvider: Flow<TtsProvider> = dataStore.data.map { prefs ->
        prefs[KEY_TTS_PROVIDER]?.let { TtsProvider.valueOf(it) } ?: TtsProvider.GOOGLE
    }

    val systemPrompt: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_SYSTEM_PROMPT] ?: "Ты — полезный AI-ассистент. Отвечай на русском языке."
    }

    val localAiHostOverride: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_LOCAL_AI_HOST] ?: ""
    }

    suspend fun setAiProvider(provider: AiProvider) {
        dataStore.edit { it[KEY_AI_PROVIDER] = provider.name }
    }

    suspend fun setSttProvider(provider: SttProvider) {
        dataStore.edit { it[KEY_STT_PROVIDER] = provider.name }
    }

    suspend fun setTtsProvider(provider: TtsProvider) {
        dataStore.edit { it[KEY_TTS_PROVIDER] = provider.name }
    }

    suspend fun setSystemPrompt(prompt: String) {
        dataStore.edit { it[KEY_SYSTEM_PROMPT] = prompt }
    }

    suspend fun setLocalAiHost(host: String) {
        dataStore.edit { it[KEY_LOCAL_AI_HOST] = host }
    }

    // --- API keys (EncryptedSharedPreferences, чувствительные) ---

    val geminiApiKey: Flow<String> = dataStore.data.map {
        encryptedPrefs.getString(ENCRYPTED_GEMINI_KEY, "") ?: ""
    }

    val claudeApiKey: Flow<String> = dataStore.data.map {
        encryptedPrefs.getString(ENCRYPTED_CLAUDE_KEY, "") ?: ""
    }

    val openAiApiKey: Flow<String> = dataStore.data.map {
        encryptedPrefs.getString(ENCRYPTED_OPENAI_KEY, "") ?: ""
    }

    val yandexApiKey: Flow<String> = dataStore.data.map {
        encryptedPrefs.getString(ENCRYPTED_YANDEX_KEY, "") ?: ""
    }

    val glmApiKey: Flow<String> = dataStore.data.map {
        encryptedPrefs.getString(ENCRYPTED_GLM_KEY, "") ?: ""
    }

    val tavilyApiKey: Flow<String> = dataStore.data.map {
        encryptedPrefs.getString(ENCRYPTED_TAVILY_KEY, "") ?: ""
    }

    suspend fun setGeminiApiKey(key: String) {
        encryptedPrefs.edit().putString(ENCRYPTED_GEMINI_KEY, key).apply()
    }

    suspend fun setClaudeApiKey(key: String) {
        encryptedPrefs.edit().putString(ENCRYPTED_CLAUDE_KEY, key).apply()
    }

    suspend fun setOpenAiApiKey(key: String) {
        encryptedPrefs.edit().putString(ENCRYPTED_OPENAI_KEY, key).apply()
    }

    suspend fun setYandexApiKey(key: String) {
        encryptedPrefs.edit().putString(ENCRYPTED_YANDEX_KEY, key).apply()
    }

    suspend fun setGlmApiKey(key: String) {
        encryptedPrefs.edit().putString(ENCRYPTED_GLM_KEY, key).apply()
    }

    suspend fun setTavilyApiKey(key: String) {
        encryptedPrefs.edit().putString(ENCRYPTED_TAVILY_KEY, key).apply()
    }
}

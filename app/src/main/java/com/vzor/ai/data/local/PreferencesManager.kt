package com.vzor.ai.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.vzor.ai.domain.model.AiProvider
import com.vzor.ai.domain.model.SttProvider
import com.vzor.ai.domain.model.TtsProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
        private val KEY_DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        private val KEY_HOME_SSID = stringPreferencesKey("home_ssid")

        // Ключи для EncryptedSharedPreferences (API keys)
        private const val ENCRYPTED_GEMINI_KEY = "gemini_api_key"
        private const val ENCRYPTED_CLAUDE_KEY = "claude_api_key"
        private const val ENCRYPTED_OPENAI_KEY = "openai_api_key"
        private const val ENCRYPTED_YANDEX_KEY = "yandex_api_key"
        private const val ENCRYPTED_GLM_KEY = "glm_api_key"
        private const val ENCRYPTED_TAVILY_KEY = "tavily_api_key"
        private const val ENCRYPTED_ACRCLOUD_KEY = "acrcloud_access_key"
        private const val ENCRYPTED_ACRCLOUD_SECRET = "acrcloud_access_secret"
        private const val ENCRYPTED_ACRCLOUD_HOST = "acrcloud_host"
        private const val ENCRYPTED_PICOVOICE_KEY = "picovoice_access_key"
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

    val developerMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_DEVELOPER_MODE] ?: false
    }

    /** SSID домашней Wi-Fi сети (для автопереключения на локальный AI). */
    val homeSsid: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_HOME_SSID] ?: ""
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

    suspend fun setDeveloperMode(enabled: Boolean) {
        dataStore.edit { it[KEY_DEVELOPER_MODE] = enabled }
    }

    suspend fun setHomeSsid(ssid: String) {
        dataStore.edit { it[KEY_HOME_SSID] = ssid }
    }

    // --- API keys (EncryptedSharedPreferences, чувствительные) ---
    // Используем MutableStateFlow для реактивности: запись в EncryptedSP + обновление Flow.

    private val _geminiApiKey = MutableStateFlow(
        encryptedPrefs.getString(ENCRYPTED_GEMINI_KEY, "") ?: ""
    )
    val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

    private val _claudeApiKey = MutableStateFlow(
        encryptedPrefs.getString(ENCRYPTED_CLAUDE_KEY, "") ?: ""
    )
    val claudeApiKey: StateFlow<String> = _claudeApiKey.asStateFlow()

    private val _openAiApiKey = MutableStateFlow(
        encryptedPrefs.getString(ENCRYPTED_OPENAI_KEY, "") ?: ""
    )
    val openAiApiKey: StateFlow<String> = _openAiApiKey.asStateFlow()

    private val _yandexApiKey = MutableStateFlow(
        encryptedPrefs.getString(ENCRYPTED_YANDEX_KEY, "") ?: ""
    )
    val yandexApiKey: StateFlow<String> = _yandexApiKey.asStateFlow()

    private val _glmApiKey = MutableStateFlow(
        encryptedPrefs.getString(ENCRYPTED_GLM_KEY, "") ?: ""
    )
    val glmApiKey: StateFlow<String> = _glmApiKey.asStateFlow()

    private val _tavilyApiKey = MutableStateFlow(
        encryptedPrefs.getString(ENCRYPTED_TAVILY_KEY, "") ?: ""
    )
    val tavilyApiKey: StateFlow<String> = _tavilyApiKey.asStateFlow()

    private val _acrCloudAccessKey = MutableStateFlow(
        encryptedPrefs.getString(ENCRYPTED_ACRCLOUD_KEY, "") ?: ""
    )
    val acrCloudAccessKey: StateFlow<String> = _acrCloudAccessKey.asStateFlow()

    private val _acrCloudAccessSecret = MutableStateFlow(
        encryptedPrefs.getString(ENCRYPTED_ACRCLOUD_SECRET, "") ?: ""
    )
    val acrCloudAccessSecret: StateFlow<String> = _acrCloudAccessSecret.asStateFlow()

    private val _acrCloudHost = MutableStateFlow(
        encryptedPrefs.getString(ENCRYPTED_ACRCLOUD_HOST, "") ?: ""
    )
    val acrCloudHost: StateFlow<String> = _acrCloudHost.asStateFlow()

    private val _picovoiceAccessKey = MutableStateFlow(
        encryptedPrefs.getString(ENCRYPTED_PICOVOICE_KEY, "") ?: ""
    )
    val picovoiceAccessKey: StateFlow<String> = _picovoiceAccessKey.asStateFlow()

    suspend fun setGeminiApiKey(key: String) {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString(ENCRYPTED_GEMINI_KEY, key).commit()
        }
        _geminiApiKey.value = key
    }

    suspend fun setClaudeApiKey(key: String) {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString(ENCRYPTED_CLAUDE_KEY, key).commit()
        }
        _claudeApiKey.value = key
    }

    suspend fun setOpenAiApiKey(key: String) {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString(ENCRYPTED_OPENAI_KEY, key).commit()
        }
        _openAiApiKey.value = key
    }

    suspend fun setYandexApiKey(key: String) {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString(ENCRYPTED_YANDEX_KEY, key).commit()
        }
        _yandexApiKey.value = key
    }

    suspend fun setGlmApiKey(key: String) {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString(ENCRYPTED_GLM_KEY, key).commit()
        }
        _glmApiKey.value = key
    }

    suspend fun setTavilyApiKey(key: String) {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString(ENCRYPTED_TAVILY_KEY, key).commit()
        }
        _tavilyApiKey.value = key
    }

    suspend fun setPicovoiceAccessKey(key: String) {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit().putString(ENCRYPTED_PICOVOICE_KEY, key).commit()
        }
        _picovoiceAccessKey.value = key
    }

    suspend fun setAcrCloudCredentials(accessKey: String, accessSecret: String, host: String) {
        withContext(Dispatchers.IO) {
            encryptedPrefs.edit()
                .putString(ENCRYPTED_ACRCLOUD_KEY, accessKey)
                .putString(ENCRYPTED_ACRCLOUD_SECRET, accessSecret)
                .putString(ENCRYPTED_ACRCLOUD_HOST, host)
                .commit()
        }
        _acrCloudAccessKey.value = accessKey
        _acrCloudAccessSecret.value = accessSecret
        _acrCloudHost.value = host
    }
}

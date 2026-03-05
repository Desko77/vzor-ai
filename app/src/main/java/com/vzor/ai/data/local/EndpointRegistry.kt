package com.vzor.ai.data.local

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads endpoint configuration from assets/endpoints.json and provides
 * runtime-overridable endpoint URLs for local AI, cloud, and offline backends.
 *
 * The local_ai_host can be overridden at runtime via [PreferencesManager]
 * to support dynamic EVO X2 IP discovery.
 */
@Singleton
class EndpointRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager
) {
    companion object {
        private const val TAG = "EndpointRegistry"
        private const val ENDPOINTS_FILE = "endpoints.json"

        private const val KEY_LOCAL_AI = "local_ai"
        private const val KEY_CLOUD = "cloud"
        private const val KEY_OFFLINE = "offline"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_STT_PATH = "stt_path"
        private const val KEY_TTS_PATH = "tts_path"
        private const val KEY_LLM_PATH = "llm_path"
        private const val KEY_VISION_PATH = "vision_path"
        private const val KEY_HEALTH_PATH = "health_path"
    }

    private var config: EndpointConfig? = null

    init {
        loadFromAssets()
    }

    /** Load and parse endpoints.json from the app's assets folder. */
    private fun loadFromAssets() {
        try {
            val json = context.assets.open(ENDPOINTS_FILE)
                .bufferedReader()
                .use { it.readText() }

            val root = JSONObject(json)
            config = parseConfig(root)
            Log.d(TAG, "Endpoints loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load endpoints.json, using defaults", e)
            config = defaultConfig()
        }
    }

    private fun parseConfig(root: JSONObject): EndpointConfig {
        val localAi = root.optJSONObject(KEY_LOCAL_AI)
        val cloud = root.optJSONObject(KEY_CLOUD)
        val offline = root.optJSONObject(KEY_OFFLINE)

        return EndpointConfig(
            localAi = EndpointGroup(
                host = localAi?.optString(KEY_HOST, "192.168.1.100") ?: "192.168.1.100",
                port = localAi?.optInt(KEY_PORT, 8080) ?: 8080,
                sttPath = localAi?.optString(KEY_STT_PATH, "/v1/stt") ?: "/v1/stt",
                ttsPath = localAi?.optString(KEY_TTS_PATH, "/v1/tts") ?: "/v1/tts",
                llmPath = localAi?.optString(KEY_LLM_PATH, "/v1/chat/completions") ?: "/v1/chat/completions",
                visionPath = localAi?.optString(KEY_VISION_PATH, "/v1/vision") ?: "/v1/vision",
                healthPath = localAi?.optString(KEY_HEALTH_PATH, "/health") ?: "/health"
            ),
            cloud = CloudEndpoints(
                claudeBaseUrl = cloud?.optString("claude_base_url", "https://api.anthropic.com") ?: "https://api.anthropic.com",
                openaiBaseUrl = cloud?.optString("openai_base_url", "https://api.openai.com") ?: "https://api.openai.com",
                geminiBaseUrl = cloud?.optString("gemini_base_url", "https://generativelanguage.googleapis.com") ?: "https://generativelanguage.googleapis.com",
                tavilyBaseUrl = cloud?.optString("tavily_base_url", "https://api.tavily.com") ?: "https://api.tavily.com"
            ),
            offline = OfflineEndpoints(
                modelPath = offline?.optString("model_path", "models/qwen3.5-4b") ?: "models/qwen3.5-4b",
                sttModelPath = offline?.optString("stt_model_path", "models/whisper-tiny") ?: "models/whisper-tiny"
            )
        )
    }

    private fun defaultConfig(): EndpointConfig = EndpointConfig(
        localAi = EndpointGroup(
            host = "192.168.1.100",
            port = 8080,
            sttPath = "/v1/stt",
            ttsPath = "/v1/tts",
            llmPath = "/v1/chat/completions",
            visionPath = "/v1/vision",
            healthPath = "/health"
        ),
        cloud = CloudEndpoints(
            claudeBaseUrl = "https://api.anthropic.com",
            openaiBaseUrl = "https://api.openai.com",
            geminiBaseUrl = "https://generativelanguage.googleapis.com",
            tavilyBaseUrl = "https://api.tavily.com"
        ),
        offline = OfflineEndpoints(
            modelPath = "models/qwen3.5-4b",
            sttModelPath = "models/whisper-tiny"
        )
    )

    /**
     * Get the local AI host, applying runtime override from preferences if set.
     */
    fun getLocalAiHost(): String {
        val override = runBlocking { prefs.localAiHostOverride.first() }
        return if (override.isNotBlank()) override else config?.localAi?.host ?: "192.168.1.100"
    }

    /** Get the local AI port. */
    fun getLocalAiPort(): Int = config?.localAi?.port ?: 8080

    /** Get the full base URL for local AI server. */
    fun getLocalAiBaseUrl(): String = "http://${getLocalAiHost()}:${getLocalAiPort()}"

    /** Get the local AI LLM chat completions endpoint. */
    fun getLocalAiLlmEndpoint(): String = "${getLocalAiBaseUrl()}${config?.localAi?.llmPath ?: "/v1/chat/completions"}"

    /** Get the local AI STT endpoint. */
    fun getLocalAiSttEndpoint(): String = "${getLocalAiBaseUrl()}${config?.localAi?.sttPath ?: "/v1/stt"}"

    /** Get the local AI TTS endpoint. */
    fun getLocalAiTtsEndpoint(): String = "${getLocalAiBaseUrl()}${config?.localAi?.ttsPath ?: "/v1/tts"}"

    /** Get the local AI vision endpoint. */
    fun getLocalAiVisionEndpoint(): String = "${getLocalAiBaseUrl()}${config?.localAi?.visionPath ?: "/v1/vision"}"

    /** Get the local AI health check endpoint. */
    fun getLocalAiHealthEndpoint(): String = "${getLocalAiBaseUrl()}${config?.localAi?.healthPath ?: "/health"}"

    /** Get cloud endpoint base URLs. */
    fun getClaudeBaseUrl(): String = config?.cloud?.claudeBaseUrl ?: "https://api.anthropic.com"
    fun getOpenAiBaseUrl(): String = config?.cloud?.openaiBaseUrl ?: "https://api.openai.com"
    fun getGeminiBaseUrl(): String = config?.cloud?.geminiBaseUrl ?: "https://generativelanguage.googleapis.com"
    fun getTavilyBaseUrl(): String = config?.cloud?.tavilyBaseUrl ?: "https://api.tavily.com"

    /** Get offline model paths. */
    fun getOfflineModelPath(): String = config?.offline?.modelPath ?: "models/qwen3.5-4b"
    fun getOfflineSttModelPath(): String = config?.offline?.sttModelPath ?: "models/whisper-tiny"

    /** Reload configuration from assets. */
    fun reload() {
        loadFromAssets()
    }
}

/** Internal endpoint configuration model. */
data class EndpointConfig(
    val localAi: EndpointGroup,
    val cloud: CloudEndpoints,
    val offline: OfflineEndpoints
)

data class EndpointGroup(
    val host: String,
    val port: Int,
    val sttPath: String,
    val ttsPath: String,
    val llmPath: String,
    val visionPath: String,
    val healthPath: String
)

data class CloudEndpoints(
    val claudeBaseUrl: String,
    val openaiBaseUrl: String,
    val geminiBaseUrl: String,
    val tavilyBaseUrl: String
)

data class OfflineEndpoints(
    val modelPath: String,
    val sttModelPath: String
)

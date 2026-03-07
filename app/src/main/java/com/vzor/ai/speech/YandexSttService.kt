package com.vzor.ai.speech

import android.content.Context
import android.util.Log
import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.glasses.AudioStreamHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Настоящий streaming STT через Yandex SpeechKit v3 WebSocket API.
 *
 * Протокол: WebSocket wss://stt.api.cloud.yandex.net/stt/v3/recognizeStreaming
 * Формат: бинарные фреймы (PCM 16kHz 16-bit mono) + JSON управляющие сообщения.
 *
 * Sequence:
 * 1. Клиент → сервер: JSON config (recognize_spec: язык, формат, partial results)
 * 2. Клиент → сервер: бинарные PCM chunks (20ms = 640 bytes каждый)
 * 3. Сервер → клиент: JSON partial/final results
 * 4. Клиент закрывает WebSocket по окончании аудио.
 *
 * Преимущества vs REST v1:
 * - Настоящий streaming: partial results приходят по мере произнесения
 * - Нет O(n²) пересылки буфера
 * - Низкая латентность: каждый chunk отправляется мгновенно (20ms)
 * - Серверная VAD: Yandex определяет конец фразы
 */
@Singleton
class YandexSttService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager,
    private val audioStreamHandler: AudioStreamHandler,
    baseHttpClient: OkHttpClient
) : SttService {

    companion object {
        private const val TAG = "YandexSttService"

        /** Yandex SpeechKit v3 WebSocket streaming endpoint. */
        private const val WS_URL = "wss://stt.api.cloud.yandex.net/stt/v3/recognizeStreaming"

        /** Fallback REST v1 endpoint для batch recognition. */
        private const val REST_URL = "https://stt.api.cloud.yandex.net/speech/v1/stt:recognize"

        /** Max recording duration in milliseconds. */
        private const val MAX_RECORDING_DURATION_MS = 30_000L

        /** Timeout for WebSocket connection (ms). */
        private const val WS_CONNECT_TIMEOUT_MS = 10_000L
    }

    @Volatile
    private var _isListening = false

    override val isListening: Boolean
        get() = _isListening

    @Volatile
    private var activeWebSocket: WebSocket? = null

    /** OkHttpClient с таймаутами для WebSocket STT (наследует interceptors от baseHttpClient). */
    private val httpClient = baseHttpClient.newBuilder()
        .connectTimeout(WS_CONNECT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(MAX_RECORDING_DURATION_MS + 5000, java.util.concurrent.TimeUnit.MILLISECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun startListening(): Flow<SttResult> = callbackFlow {
        _isListening = true

        val apiKey = prefs.yandexApiKey.first()
        if (apiKey.isBlank()) {
            close(IllegalStateException("Yandex API ключ не указан"))
            return@callbackFlow
        }

        val request = Request.Builder()
            .url(WS_URL)
            .header("Authorization", "Api-Key $apiKey")
            .build()

        val listener = object : WebSocketListener() {
            private var configSent = false

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened, sending config")
                // Отправляем конфигурацию распознавания
                val config = buildRecognizeConfig()
                webSocket.send(config)
                configSent = true

                // Начинаем стриминг аудио
                launch(Dispatchers.IO) {
                    streamAudioToWebSocket(webSocket)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val result = parseStreamingResult(text)
                    if (result != null) {
                        trySend(result)
                        if (result.isFinal) {
                            Log.d(TAG, "Final result received: ${result.text.take(50)}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse WS message: ${text.take(200)}", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                Log.e(TAG, "WebSocket failure (HTTP $code): ${t.message}")

                // Fallback to REST v1 if WebSocket fails before config
                if (!configSent) {
                    Log.w(TAG, "WebSocket connection failed, falling back to REST v1")
                    launch(Dispatchers.IO) {
                        try {
                            val results = fallbackToRestV1(apiKey)
                            results.forEach { trySend(it) }
                        } catch (e: Exception) {
                            Log.e(TAG, "REST v1 fallback also failed", e)
                        } finally {
                            close()
                        }
                    }
                } else {
                    close(t)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                _isListening = false
                close()
            }
        }

        activeWebSocket = httpClient.newWebSocket(request, listener)

        awaitClose {
            _isListening = false
            activeWebSocket?.close(1000, "Client stopped listening")
            activeWebSocket = null
            audioStreamHandler.stop()
        }
    }.flowOn(Dispatchers.IO)

    override fun stopListening() {
        _isListening = false
        activeWebSocket?.close(1000, "User stopped")
        activeWebSocket = null
        audioStreamHandler.stop()
    }

    // ==================== WebSocket streaming ====================

    /**
     * Стримит PCM аудио чанки в WebSocket как бинарные фреймы.
     * Каждый чанк = 20ms (640 bytes) при 16kHz/16-bit/mono.
     */
    private suspend fun streamAudioToWebSocket(webSocket: WebSocket) {
        val startTime = System.currentTimeMillis()

        try {
            audioStreamHandler.streamAudio().collect { chunk ->
                if (!_isListening) return@collect

                // Отправляем PCM chunk как бинарный фрейм
                val sent = webSocket.send(chunk.toByteString())
                if (!sent) {
                    Log.w(TAG, "Failed to send audio chunk (WebSocket buffer full)")
                }

                // Проверка максимальной длительности
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= MAX_RECORDING_DURATION_MS) {
                    Log.d(TAG, "Max recording duration reached, stopping")
                    _isListening = false
                    audioStreamHandler.stop()
                    return@collect
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio streaming error", e)
        } finally {
            // Сигнализируем серверу о конце аудио через закрытие WebSocket
            Log.d(TAG, "Audio stream ended, closing WebSocket")
            webSocket.close(1000, "Audio stream complete")
        }
    }

    /**
     * Формирует JSON конфигурацию для Yandex STT v3 streaming.
     */
    private fun buildRecognizeConfig(): String {
        return JSONObject().apply {
            put("config", JSONObject().apply {
                put("specification", JSONObject().apply {
                    put("languageCode", "ru-RU")
                    put("model", "general")
                    put("profanityFilter", false)
                    put("partialResults", true)
                    put("audioEncoding", "LINEAR16_PCM")
                    put("sampleRateHertz", AudioStreamHandler.SAMPLE_RATE)
                    put("audioChannelCount", 1)
                })
            })
        }.toString()
    }

    /**
     * Парсит JSON ответ от Yandex STT v3 streaming.
     * Формат:
     * - Partial: {"chunks": [{"alternatives": [{"text": "..."}], "final": false}]}
     * - Final:   {"chunks": [{"alternatives": [{"text": "..."}], "final": true}]}
     */
    private fun parseStreamingResult(json: String): SttResult? {
        return try {
            val root = JSONObject(json)

            // v3 streaming format
            val chunks = root.optJSONArray("chunks")
            if (chunks != null && chunks.length() > 0) {
                val chunk = chunks.getJSONObject(0)
                val isFinal = chunk.optBoolean("final", false)
                val alternatives = chunk.optJSONArray("alternatives")
                if (alternatives != null && alternatives.length() > 0) {
                    val alt = alternatives.getJSONObject(0)
                    val text = alt.optString("text", "")
                    val confidence = alt.optDouble("confidence", if (isFinal) 1.0 else 0.8).toFloat()

                    if (text.isNotBlank()) {
                        return SttResult(
                            text = text,
                            isFinal = isFinal,
                            confidence = confidence,
                            language = "ru"
                        )
                    }
                }
            }

            // v3 alternative format (sessionUuid response, status messages)
            val sessionUuid = root.optJSONObject("sessionUuid")
            if (sessionUuid != null) {
                Log.d(TAG, "Session started: ${sessionUuid.optString("uuid", "")}")
                return null
            }

            null
        } catch (e: Exception) {
            Log.w(TAG, "Parse error: ${json.take(200)}", e)
            null
        }
    }

    // ==================== REST v1 fallback ====================

    /**
     * Fallback: если WebSocket v3 не доступен (старый API key, firewall),
     * используем REST v1 с оптимизированным скользящим окном.
     */
    private suspend fun fallbackToRestV1(apiKey: String): List<SttResult> {
        Log.w(TAG, "Using REST v1 fallback for STT")
        val results = mutableListOf<SttResult>()

        try {
            val audioBuffer = java.io.ByteArrayOutputStream()
            val startTime = System.currentTimeMillis()

            audioStreamHandler.streamAudio().collect { chunk ->
                if (!_isListening) return@collect
                audioBuffer.write(chunk)

                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= MAX_RECORDING_DURATION_MS) {
                    _isListening = false
                    audioStreamHandler.stop()
                }
            }

            val completeAudio = audioBuffer.toByteArray()
            if (completeAudio.isNotEmpty()) {
                val text = recognizeAudioRest(apiKey, completeAudio)
                if (text.isNotBlank()) {
                    results.add(SttResult(text = text, isFinal = true, confidence = 1.0f, language = "ru"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "REST v1 fallback failed", e)
        }

        return results
    }

    /**
     * REST v1 single-shot recognition.
     */
    private fun recognizeAudioRest(apiKey: String, pcmAudio: ByteArray): String {
        val requestBody = pcmAudio.toRequestBody(
            "audio/x-pcm;bit=16;rate=16000".toMediaTypeOrNull()
        )

        val request = Request.Builder()
            .url("$REST_URL?lang=ru-RU&topic=general&profanityFilter=false&format=lpcm&sampleRateHertz=${AudioStreamHandler.SAMPLE_RATE}")
            .addHeader("Authorization", "Api-Key $apiKey")
            .post(requestBody)
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Yandex STT REST error ${response.code}: $errorBody")
                throw RuntimeException("Yandex STT API error: ${response.code} - $errorBody")
            }

            val responseBody = response.body?.string() ?: return@use ""
            try {
                JSONObject(responseBody).optString("result", "")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse REST response", e)
                ""
            }
        }
    }

}

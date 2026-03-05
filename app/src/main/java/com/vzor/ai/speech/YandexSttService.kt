package com.vzor.ai.speech

import android.content.Context
import android.util.Log
import com.vzor.ai.data.local.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streaming STT via Yandex SpeechKit REST API.
 * Used for LTE connectivity scenarios.
 *
 * Sends audio chunks to Yandex Speech-to-Text REST API.
 * Returns partial results (isFinal=false) and final result (isFinal=true).
 * Uses REST API (not gRPC) for MVP simplicity.
 *
 * Endpoint: POST https://stt.api.cloud.yandex.net/speech/v1/stt:recognize
 * Audio format: audio/x-pcm;bit=16;rate=16000
 * API key from prefs.yandexApiKey
 */
@Singleton
class YandexSttService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesManager,
    private val audioStreamHandler: AudioStreamHandler
) : SttService {

    companion object {
        private const val TAG = "YandexSttService"
        private const val YANDEX_STT_URL =
            "https://stt.api.cloud.yandex.net/speech/v1/stt:recognize"

        /** Chunk duration for streaming simulation: ~500ms of 16kHz 16-bit mono = 16000 bytes */
        private const val CHUNK_SIZE_BYTES = 16000

        /** Max recording duration in milliseconds */
        private const val MAX_RECORDING_DURATION_MS = 30_000L
    }

    @Volatile
    private var _isListening = false

    override val isListening: Boolean
        get() = _isListening

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override fun startListening(): Flow<SttResult> = flow {
        _isListening = true

        val apiKey = prefs.yandexApiKey.first()
        if (apiKey.isBlank()) {
            throw IllegalStateException("Yandex API ключ не указан")
        }

        try {
            // Collect audio chunks and send for recognition in batches
            val audioBuffer = ByteArrayOutputStream()
            var startTime = System.currentTimeMillis()

            audioStreamHandler.streamAudio().collect { chunk ->
                if (!_isListening) {
                    return@collect
                }

                audioBuffer.write(chunk)

                // When we have enough audio data, send a recognition request
                // This simulates streaming by sending growing audio segments
                if (audioBuffer.size() >= CHUNK_SIZE_BYTES) {
                    val currentAudio = audioBuffer.toByteArray()

                    try {
                        val result = recognizeAudio(apiKey, currentAudio)
                        if (result.isNotBlank()) {
                            // Emit partial result while still recording
                            emit(
                                SttResult(
                                    text = result,
                                    isFinal = false,
                                    confidence = 0.8f,
                                    language = "ru"
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Partial recognition failed", e)
                    }
                }

                // Check for max recording duration
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= MAX_RECORDING_DURATION_MS) {
                    _isListening = false
                    audioStreamHandler.stop()
                }
            }

            // Final recognition with complete audio
            val completeAudio = audioBuffer.toByteArray()
            if (completeAudio.isNotEmpty()) {
                try {
                    val finalText = recognizeAudio(apiKey, completeAudio)
                    if (finalText.isNotBlank()) {
                        emit(
                            SttResult(
                                text = finalText,
                                isFinal = true,
                                confidence = 1.0f,
                                language = "ru"
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Final recognition failed", e)
                    throw e
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Yandex STT error", e)
            throw e
        } finally {
            _isListening = false
        }
    }.flowOn(Dispatchers.IO)

    override fun stopListening() {
        _isListening = false
        audioStreamHandler.stop()
    }

    /**
     * Send PCM audio to Yandex STT REST API for recognition.
     *
     * POST https://stt.api.cloud.yandex.net/speech/v1/stt:recognize
     * Content-Type: audio/x-pcm;bit=16;rate=16000
     * Authorization: Api-Key {apiKey}
     */
    private fun recognizeAudio(apiKey: String, pcmAudio: ByteArray): String {
        val requestBody = pcmAudio.toRequestBody(
            "audio/x-pcm;bit=16;rate=16000".toMediaTypeOrNull()
        )

        val request = Request.Builder()
            .url("$YANDEX_STT_URL?lang=ru-RU&topic=general&profanityFilter=false&format=lpcm&sampleRateHertz=${AudioStreamHandler.SAMPLE_RATE}")
            .addHeader("Authorization", "Api-Key $apiKey")
            .post(requestBody)
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                Log.e(TAG, "Yandex STT error ${response.code}: $errorBody")
                throw RuntimeException("Yandex STT API error: ${response.code} - $errorBody")
            }

            val responseBody = response.body?.string() ?: return@use ""

            try {
                val json = JSONObject(responseBody)
                json.optString("result", "")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse Yandex STT response", e)
                ""
            }
        }
    }
}

package com.vzor.ai.speech

import android.content.Context
import android.util.Log
import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.glasses.AudioStreamHandler
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

        /** Chunk duration for streaming simulation: ~1s of 16kHz 16-bit mono = 32000 bytes */
        private const val CHUNK_SIZE_BYTES = 32000

        /** Скользящее окно для partial recognition: последние N чанков (~5s аудио).
         *  Вместо O(n²) пересылки всего буфера, отправляем только окно. */
        private const val WINDOW_CHUNKS = 5

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
            // Sliding window: храним только последний обработанный фрагмент + новый
            // Вместо O(n²) пересылки всего буфера, отправляем скользящее окно
            val audioBuffer = ByteArrayOutputStream()
            var lastEmittedText = ""
            val startTime = System.currentTimeMillis()

            // Кольцевой буфер последних WINDOW_CHUNKS чанков для partial recognition
            val recentChunks = ArrayDeque<ByteArray>(WINDOW_CHUNKS + 1)
            var totalBytesReceived = 0

            audioStreamHandler.streamAudio().collect { chunk ->
                if (!_isListening) {
                    return@collect
                }

                // Полный буфер для финальной отправки
                audioBuffer.write(chunk)
                totalBytesReceived += chunk.size

                // Скользящее окно для partial recognition
                recentChunks.addLast(chunk)
                if (recentChunks.size > WINDOW_CHUNKS) {
                    recentChunks.removeFirst()
                }

                // Partial recognition по скользящему окну (O(1) по размеру данных)
                if (totalBytesReceived % CHUNK_SIZE_BYTES < chunk.size) {
                    try {
                        val windowAudio = mergeChunks(recentChunks)
                        val result = recognizeAudio(apiKey, windowAudio)
                        if (result.isNotBlank() && result != lastEmittedText) {
                            lastEmittedText = result
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

                // Проверка максимальной длительности записи
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed >= MAX_RECORDING_DURATION_MS) {
                    _isListening = false
                    audioStreamHandler.stop()
                }
            }

            // Финальная отправка полного буфера (один раз, O(n))
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

    /**
     * Объединяет чанки из скользящего окна в один ByteArray.
     */
    private fun mergeChunks(chunks: ArrayDeque<ByteArray>): ByteArray {
        val totalSize = chunks.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        return result
    }

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

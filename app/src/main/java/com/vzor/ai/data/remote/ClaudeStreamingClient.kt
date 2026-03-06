package com.vzor.ai.data.remote

import com.squareup.moshi.Moshi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SSE streaming клиент для Claude API.
 * Парсит server-sent events и возвращает Flow<String> с токенами.
 */
@Singleton
class ClaudeStreamingClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) {
    companion object {
        private const val BASE_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }

    /**
     * Стримит ответ от Claude API токен за токеном.
     *
     * @param apiKey API ключ Claude
     * @param request Запрос (stream поле будет принудительно установлено в true)
     * @return Flow токенов текста
     */
    fun stream(apiKey: String, request: ClaudeRequest): Flow<String> = callbackFlow {
        val streamRequest = request.copy(stream = true)

        val adapter = moshi.adapter(ClaudeRequest::class.java)
        val jsonBody = adapter.toJson(streamRequest)

        val httpRequest = Request.Builder()
            .url(BASE_URL)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Accept", "text/event-stream")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                when (type) {
                    "content_block_delta" -> {
                        // {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
                        val text = extractDeltaText(data)
                        if (text != null) {
                            trySend(text)
                        }
                    }
                    "message_stop" -> {
                        close()
                    }
                    "error" -> {
                        close(Exception("Claude SSE ошибка: $data"))
                    }
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                val error = t ?: Exception(
                    "Claude SSE ошибка: HTTP ${response?.code} ${response?.message}"
                )
                close(error)
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val factory = EventSources.createFactory(okHttpClient)
        val eventSource = factory.newEventSource(httpRequest, listener)

        awaitClose {
            eventSource.cancel()
        }
    }

    /**
     * Извлекает текст из content_block_delta JSON.
     * Формат: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"..."}}
     */
    private fun extractDeltaText(json: String): String? {
        return try {
            val map = moshi.adapter<Map<String, Any>>(
                Map::class.java
            ).fromJson(json) ?: return null

            @Suppress("UNCHECKED_CAST")
            val delta = map["delta"] as? Map<String, Any> ?: return null
            delta["text"] as? String
        } catch (_: Exception) {
            null
        }
    }
}

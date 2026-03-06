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
 * SSE streaming клиент для OpenAI-совместимых API (OpenAI, GLM-5).
 * Парсит server-sent events и возвращает Flow<String> с токенами.
 */
@Singleton
class OpenAiStreamingClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) {
    companion object {
        private const val OPENAI_BASE_URL = "https://api.openai.com/v1/chat/completions"
        private const val GLM_BASE_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
    }

    /**
     * Стримит ответ от OpenAI API токен за токеном.
     */
    fun stream(apiKey: String, request: OpenAiChatRequest): Flow<String> =
        streamFromUrl(OPENAI_BASE_URL, "Bearer $apiKey", request)

    /**
     * Стримит ответ от GLM-5 API (OpenAI-совместимый формат).
     */
    fun streamGlm(apiKey: String, request: OpenAiChatRequest): Flow<String> =
        streamFromUrl(GLM_BASE_URL, "Bearer $apiKey", request)

    private fun streamFromUrl(
        url: String,
        authHeader: String,
        request: OpenAiChatRequest
    ): Flow<String> = callbackFlow {
        val streamRequest = request.copy(stream = true)

        val adapter = moshi.adapter(OpenAiChatRequest::class.java)
        val jsonBody = adapter.toJson(streamRequest)

        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
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
                if (data == "[DONE]") {
                    close()
                    return
                }

                val text = extractDeltaContent(data)
                if (text != null) {
                    trySend(text)
                }
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                val error = t ?: Exception(
                    "OpenAI SSE ошибка: HTTP ${response?.code} ${response?.message}"
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
     * Извлекает content из SSE delta.
     * Формат: {"choices":[{"delta":{"content":"..."}}]}
     */
    private fun extractDeltaContent(json: String): String? {
        return try {
            val map = moshi.adapter<Map<String, Any>>(
                Map::class.java
            ).fromJson(json) ?: return null

            @Suppress("UNCHECKED_CAST")
            val choices = map["choices"] as? List<Map<String, Any>> ?: return null
            val firstChoice = choices.firstOrNull() ?: return null

            @Suppress("UNCHECKED_CAST")
            val delta = firstChoice["delta"] as? Map<String, Any> ?: return null
            delta["content"] as? String
        } catch (_: Exception) {
            null
        }
    }
}

package com.vzor.ai.data.remote

import android.util.Log
import com.squareup.moshi.Moshi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
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
 * Парсит server-sent events и возвращает Flow<StreamChunk> с токенами и tool calls.
 */
@Singleton
class ClaudeStreamingClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) {
    companion object {
        private const val TAG = "ClaudeStreaming"
        private const val BASE_URL = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }

    /**
     * Стримит ответ от Claude API токен за токеном.
     * Обратная совместимость — возвращает только текстовые токены.
     */
    fun stream(apiKey: String, request: ClaudeRequest): Flow<String> =
        streamChunks(apiKey, request)
            .let { flow ->
                kotlinx.coroutines.flow.flow {
                    flow.collect { chunk ->
                        if (chunk is StreamChunk.Text) emit(chunk.content)
                    }
                }
            }

    /**
     * Стримит ответ от Claude API с полной поддержкой tool calls.
     *
     * @return Flow<StreamChunk> — текст, tool calls и завершение.
     */
    fun streamChunks(apiKey: String, request: ClaudeRequest): Flow<StreamChunk> = callbackFlow {
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

        // Accumulate tool call data across multiple SSE events
        var currentToolId: String? = null
        var currentToolName: String? = null
        val toolInputBuffer = StringBuilder()

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                when (type) {
                    "content_block_start" -> {
                        // Начало нового content block — может быть text или tool_use
                        val blockType = extractString(data, listOf("content_block", "type"))
                        if (blockType == "tool_use") {
                            if (currentToolId != null) {
                                Log.w(TAG, "New tool_use block started before previous was closed: $currentToolId")
                            }
                            currentToolId = extractString(data, listOf("content_block", "id"))
                            currentToolName = extractString(data, listOf("content_block", "name"))
                            toolInputBuffer.clear()
                        }
                    }

                    "content_block_delta" -> {
                        val deltaType = extractString(data, listOf("delta", "type"))
                        when (deltaType) {
                            "text_delta" -> {
                                val text = extractString(data, listOf("delta", "text"))
                                if (text != null) {
                                    trySend(StreamChunk.Text(text))
                                }
                            }
                            "input_json_delta" -> {
                                val partial = extractString(data, listOf("delta", "partial_json"))
                                if (partial != null) {
                                    toolInputBuffer.append(partial)
                                }
                            }
                        }
                    }

                    "content_block_stop" -> {
                        // Если был tool_use block — отправляем ToolCall
                        val toolId = currentToolId
                        val toolName = currentToolName
                        if (toolId != null && toolName != null) {
                            val args = parseToolArguments(toolInputBuffer.toString())
                            trySend(StreamChunk.ToolCall(
                                id = toolId,
                                name = toolName,
                                arguments = args
                            ))
                            currentToolId = null
                            currentToolName = null
                            toolInputBuffer.clear()
                        }
                    }

                    "message_delta" -> {
                        val stopReason = extractString(data, listOf("delta", "stop_reason"))
                        if (stopReason != null) {
                            trySend(StreamChunk.Done(stopReason))
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
     * Извлекает строку по вложенному пути из JSON.
     * Пример: extractString(json, ["delta", "text"]) → delta.text
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractString(json: String, path: List<String>): String? {
        return try {
            var current: Any? = moshi.adapter<Map<String, Any>>(
                Map::class.java
            ).fromJson(json) ?: return null

            for (key in path) {
                current = (current as? Map<String, Any>)?.get(key) ?: return null
            }
            current as? String
        } catch (e: Exception) {
            Log.w(TAG, "JSON parse error for path $path", e)
            null
        }
    }

    /**
     * Парсит JSON-строку аргументов tool call в Map<String, String>.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseToolArguments(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return try {
            val anyAdapter = moshi.adapter(Any::class.java)
            val map = moshi.adapter<Map<String, Any>>(
                Map::class.java
            ).fromJson(json) ?: return emptyMap()
            map.mapValues { (_, value) ->
                when (value) {
                    is String -> value
                    else -> anyAdapter.toJson(value) ?: value.toString()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tool arguments parse error", e)
            emptyMap()
        }
    }
}

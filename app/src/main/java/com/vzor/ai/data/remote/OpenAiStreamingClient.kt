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
 * Поддержка function calling через streamChunks().
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
     * Стримит ответ от OpenAI API токен за токеном (обратная совместимость).
     */
    fun stream(apiKey: String, request: OpenAiChatRequest): Flow<String> =
        streamTextFromUrl(OPENAI_BASE_URL, "Bearer $apiKey", request)

    /**
     * Стримит ответ от GLM-5 API (OpenAI-совместимый формат).
     */
    fun streamGlm(apiKey: String, request: OpenAiChatRequest): Flow<String> =
        streamTextFromUrl(GLM_BASE_URL, "Bearer $apiKey", request)

    /**
     * Стримит ответ с полной поддержкой function calling → StreamChunk.
     */
    fun streamChunks(apiKey: String, request: OpenAiChatRequest): Flow<StreamChunk> =
        streamChunksFromUrl(OPENAI_BASE_URL, "Bearer $apiKey", request)

    /**
     * Text-only стрим (обратная совместимость).
     */
    private fun streamTextFromUrl(
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
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") { close(); return }
                val text = extractDeltaContent(data)
                if (text != null) trySend(text)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: Exception("OpenAI SSE ошибка: HTTP ${response?.code} ${response?.message}"))
            }

            override fun onClosed(eventSource: EventSource) { close() }
        }

        val factory = EventSources.createFactory(okHttpClient)
        val eventSource = factory.newEventSource(httpRequest, listener)
        awaitClose { eventSource.cancel() }
    }

    /**
     * StreamChunk стрим с поддержкой tool_calls.
     *
     * OpenAI SSE формат для function calling:
     * {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function",
     *   "function":{"name":"memory.get","arguments":""}}]},"finish_reason":null}]}
     * ... затем delta с "arguments" фрагментами ...
     * {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
     */
    private fun streamChunksFromUrl(
        url: String,
        authHeader: String,
        request: OpenAiChatRequest
    ): Flow<StreamChunk> = callbackFlow {
        val streamRequest = request.copy(stream = true)
        val adapter = moshi.adapter(OpenAiChatRequest::class.java)
        val jsonBody = adapter.toJson(streamRequest)

        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", authHeader)
            .header("Accept", "text/event-stream")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        // Аккумуляторы для tool call фрагментов
        val toolCallIds = mutableMapOf<Int, String>()
        val toolCallNames = mutableMapOf<Int, String>()
        val toolCallArgs = mutableMapOf<Int, StringBuilder>()

        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    // Отправить накопленные tool calls перед закрытием
                    emitAccumulatedToolCalls()
                    close()
                    return
                }

                val parsed = parseOpenAiDelta(data)

                // Текстовый контент
                parsed.content?.let { trySend(StreamChunk.Text(it)) }

                // Tool call delta
                for (tc in parsed.toolCallDeltas) {
                    tc.id?.let { toolCallIds[tc.index] = it }
                    tc.name?.let { toolCallNames[tc.index] = it }
                    tc.argumentsFragment?.let {
                        toolCallArgs.getOrPut(tc.index) { StringBuilder() }.append(it)
                    }
                }

                // finish_reason
                parsed.finishReason?.let { reason ->
                    if (reason == "tool_calls") {
                        emitAccumulatedToolCalls()
                        trySend(StreamChunk.Done("tool_use")) // normalize to Claude convention
                    } else {
                        trySend(StreamChunk.Done(reason))
                    }
                }
            }

            private fun emitAccumulatedToolCalls() {
                for ((index, name) in toolCallNames) {
                    val tcId = toolCallIds[index] ?: "call_$index"
                    val argsJson = toolCallArgs[index]?.toString() ?: "{}"
                    val args = parseJsonArgs(argsJson)
                    trySend(StreamChunk.ToolCall(id = tcId, name = name, arguments = args))
                }
                toolCallIds.clear()
                toolCallNames.clear()
                toolCallArgs.clear()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: Exception("OpenAI SSE ошибка: HTTP ${response?.code} ${response?.message}"))
            }

            override fun onClosed(eventSource: EventSource) { close() }
        }

        val factory = EventSources.createFactory(okHttpClient)
        val eventSource = factory.newEventSource(httpRequest, listener)
        awaitClose { eventSource.cancel() }
    }

    private fun extractDeltaContent(json: String): String? {
        return try {
            val parsed = parseOpenAiDelta(json)
            parsed.content
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Парсит OpenAI SSE delta JSON.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseOpenAiDelta(json: String): OpenAiDeltaParsed {
        val map = moshi.adapter<Map<String, Any>>(Map::class.java).fromJson(json)
            ?: return OpenAiDeltaParsed()

        val choices = map["choices"] as? List<Map<String, Any>> ?: return OpenAiDeltaParsed()
        val firstChoice = choices.firstOrNull() ?: return OpenAiDeltaParsed()
        val delta = firstChoice["delta"] as? Map<String, Any> ?: emptyMap()
        val finishReason = firstChoice["finish_reason"] as? String

        val content = delta["content"] as? String

        val toolCallDeltas = mutableListOf<ToolCallDelta>()
        val toolCalls = delta["tool_calls"] as? List<Map<String, Any>>
        toolCalls?.forEach { tc ->
            val index = (tc["index"] as? Double)?.toInt() ?: 0
            val tcId = tc["id"] as? String
            val function = tc["function"] as? Map<String, Any>
            val name = function?.get("name") as? String
            val args = function?.get("arguments") as? String

            toolCallDeltas.add(ToolCallDelta(
                index = index,
                id = tcId,
                name = name,
                argumentsFragment = args
            ))
        }

        return OpenAiDeltaParsed(
            content = content,
            toolCallDeltas = toolCallDeltas,
            finishReason = finishReason
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonArgs(json: String): Map<String, String> {
        if (json.isBlank() || json == "{}") return emptyMap()
        return try {
            val anyAdapter = moshi.adapter(Any::class.java)
            val map = moshi.adapter<Map<String, Any>>(Map::class.java).fromJson(json)
                ?: return emptyMap()
            map.mapValues { (_, value) ->
                when (value) {
                    is String -> value
                    else -> anyAdapter.toJson(value) ?: value.toString()
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("OpenAiStreamingClient", "Tool arguments parse error: ${e.message}")
            emptyMap()
        }
    }

    private data class OpenAiDeltaParsed(
        val content: String? = null,
        val toolCallDeltas: List<ToolCallDelta> = emptyList(),
        val finishReason: String? = null
    )

    private data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsFragment: String? = null
    )
}

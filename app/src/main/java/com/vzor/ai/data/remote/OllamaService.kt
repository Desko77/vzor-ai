package com.vzor.ai.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.squareup.moshi.Moshi

/**
 * Ollama API client for local AI inference on EVO X2 server.
 * API format: POST /api/chat (streaming)
 * Default host: 192.168.1.100:11434
 */
class OllamaService(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi,
    private var baseUrl: String = "http://192.168.1.100:11434"
) {
    fun updateHost(host: String) {
        baseUrl = "http://$host:11434"
    }

    suspend fun sendMessage(
        model: String = "qwen3.5:9b",
        messages: List<OllamaMessage>,
        stream: Boolean = false,
        keepAlive: String? = null
    ): OllamaResponse {
        val request = OllamaChatRequest(
            model = model,
            messages = messages,
            stream = false,
            keepAlive = keepAlive ?: defaultKeepAlive(model)
        )
        val json = moshi.adapter(OllamaChatRequest::class.java).toJson(request)

        val httpRequest = Request.Builder()
            .url("$baseUrl/api/chat")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        return okHttpClient.newCall(httpRequest).execute().use { response ->
            val body = response.body?.string() ?: throw Exception("Empty response from Ollama")
            moshi.adapter(OllamaResponse::class.java).fromJson(body)
                ?: throw Exception("Failed to parse Ollama response")
        }
    }

    fun streamMessage(
        model: String = "qwen3.5:9b",
        messages: List<OllamaMessage>,
        keepAlive: String? = null
    ): Flow<String> = flow {
        val request = OllamaChatRequest(
            model = model,
            messages = messages,
            stream = true,
            keepAlive = keepAlive ?: defaultKeepAlive(model)
        )
        val json = moshi.adapter(OllamaChatRequest::class.java).toJson(request)

        val httpRequest = Request.Builder()
            .url("$baseUrl/api/chat")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        okHttpClient.newCall(httpRequest).execute().use { response ->
            val source = response.body?.source() ?: throw Exception("Empty stream from Ollama")

            val adapter = moshi.adapter(OllamaStreamChunk::class.java)

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue

                val chunk = adapter.fromJson(line) ?: continue
                val content = chunk.message?.content
                if (!content.isNullOrEmpty()) {
                    emit(content)
                }

                if (chunk.done == true) break
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Определяет keep_alive в зависимости от размера модели.
     * Большие модели дольше держатся в RAM для переиспользования.
     */
    private fun defaultKeepAlive(model: String): String = when {
        model.contains("9b") || model.contains("14b") -> "10m"
        model.contains("4b") || model.contains("1b") -> "3m"
        else -> "5m"
    }

    suspend fun isHealthy(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/tags")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}

@JsonClass(generateAdapter = true)
data class OllamaChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = false,
    val options: OllamaOptions? = OllamaOptions(),
    @Json(name = "keep_alive") val keepAlive: String? = "5m"
)

@JsonClass(generateAdapter = true)
data class OllamaMessage(
    val role: String,
    val content: String,
    val images: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class OllamaOptions(
    val temperature: Float = 0.3f,
    @Json(name = "num_predict") val numPredict: Int = 200
)

@JsonClass(generateAdapter = true)
data class OllamaResponse(
    val model: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean? = null,
    @Json(name = "total_duration") val totalDuration: Long? = null
)

@JsonClass(generateAdapter = true)
data class OllamaStreamChunk(
    val model: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean? = null
)

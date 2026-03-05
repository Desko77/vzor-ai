package com.vzor.ai.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface ClaudeApiService {

    @POST("v1/messages")
    suspend fun sendMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: ClaudeRequest
    ): ClaudeResponse
}

@JsonClass(generateAdapter = true)
data class ClaudeRequest(
    val model: String = "claude-sonnet-4-20250514",
    @Json(name = "max_tokens") val maxTokens: Int = 4096,
    val system: String? = null,
    val messages: List<ClaudeMessage>
)

@JsonClass(generateAdapter = true)
data class ClaudeMessage(
    val role: String,
    val content: Any // String or List<ClaudeContent>
)

@JsonClass(generateAdapter = true)
data class ClaudeContentBlock(
    val type: String,
    val text: String? = null,
    val source: ClaudeImageSource? = null
)

@JsonClass(generateAdapter = true)
data class ClaudeImageSource(
    val type: String = "base64",
    @Json(name = "media_type") val mediaType: String = "image/jpeg",
    val data: String
)

@JsonClass(generateAdapter = true)
data class ClaudeResponse(
    val id: String,
    val content: List<ClaudeResponseContent>,
    val model: String,
    @Json(name = "stop_reason") val stopReason: String?
)

@JsonClass(generateAdapter = true)
data class ClaudeResponseContent(
    val type: String,
    val text: String?
)

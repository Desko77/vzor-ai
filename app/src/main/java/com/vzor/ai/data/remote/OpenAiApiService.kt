package com.vzor.ai.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface OpenAiApiService {

    @POST("v1/chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") auth: String,
        @Body request: OpenAiChatRequest
    ): OpenAiChatResponse

    @Multipart
    @POST("v1/audio/transcriptions")
    suspend fun transcribeAudio(
        @Header("Authorization") auth: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language") language: RequestBody
    ): WhisperResponse
}

@JsonClass(generateAdapter = true)
data class OpenAiChatRequest(
    val model: String = "gpt-4o",
    val messages: List<OpenAiMessage>,
    @Json(name = "max_tokens") val maxTokens: Int = 4096,
    val stream: Boolean = false
)

@JsonClass(generateAdapter = true)
data class OpenAiMessage(
    val role: String,
    val content: Any // String or List<OpenAiContent>
)

@JsonClass(generateAdapter = true)
data class OpenAiContentPart(
    val type: String,
    val text: String? = null,
    @Json(name = "image_url") val imageUrl: OpenAiImageUrl? = null
)

@JsonClass(generateAdapter = true)
data class OpenAiImageUrl(
    val url: String // data:image/jpeg;base64,{data}
)

@JsonClass(generateAdapter = true)
data class OpenAiChatResponse(
    val id: String,
    val choices: List<OpenAiChoice>
)

@JsonClass(generateAdapter = true)
data class OpenAiChoice(
    val message: OpenAiResponseMessage,
    @Json(name = "finish_reason") val finishReason: String?
)

@JsonClass(generateAdapter = true)
data class OpenAiResponseMessage(
    val role: String,
    val content: String?
)

@JsonClass(generateAdapter = true)
data class WhisperResponse(
    val text: String
)

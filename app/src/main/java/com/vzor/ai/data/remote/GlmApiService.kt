package com.vzor.ai.data.remote

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Zhipu AI (Z.ai) API — OpenAI-compatible format.
 * Base URL: https://open.bigmodel.cn/api/paas/
 * Models: glm-5 (text), glm-4.6v (vision)
 */
interface GlmApiService {

    @POST("v4/chat/completions")
    suspend fun chatCompletion(
        @Header("Authorization") auth: String,
        @Body request: OpenAiChatRequest
    ): OpenAiChatResponse
}

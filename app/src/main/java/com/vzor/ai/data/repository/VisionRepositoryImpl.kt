package com.vzor.ai.data.repository

import android.util.Base64
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.data.remote.*
import com.vzor.ai.domain.model.AiProvider
import com.vzor.ai.domain.repository.VisionRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VisionRepositoryImpl @Inject constructor(
    private val claudeApi: ClaudeApiService,
    private val openAiApi: OpenAiApiService,
    private val prefs: PreferencesManager
) : VisionRepository {

    override suspend fun analyzeImage(imageBytes: ByteArray, prompt: String): Result<String> {
        return when (prefs.aiProvider.first()) {
            AiProvider.GEMINI -> analyzeWithGemini(imageBytes, prompt)
            AiProvider.CLAUDE -> analyzeWithClaude(imageBytes, prompt)
            AiProvider.OPENAI -> analyzeWithOpenAi(imageBytes, prompt)
        }
    }

    private suspend fun analyzeWithGemini(imageBytes: ByteArray, prompt: String): Result<String> =
        runCatching {
            val apiKey = prefs.geminiApiKey.first()
            require(apiKey.isNotBlank()) { "API ключ Gemini не указан" }

            val model = GenerativeModel(
                modelName = "gemini-2.0-flash",
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0.4f
                    maxOutputTokens = 2048
                }
            )

            val inputContent = content {
                image(android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size))
                text(prompt)
            }

            val response = model.generateContent(inputContent)
            response.text ?: throw Exception("Пустой ответ от Gemini Vision")
        }

    private suspend fun analyzeWithClaude(imageBytes: ByteArray, prompt: String): Result<String> =
        runCatching {
            val apiKey = prefs.claudeApiKey.first()
            require(apiKey.isNotBlank()) { "API ключ Claude не указан" }

            val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val response = claudeApi.sendMessage(
                apiKey = apiKey,
                request = ClaudeRequest(
                    messages = listOf(
                        ClaudeMessage(
                            role = "user",
                            content = listOf(
                                ClaudeContentBlock(
                                    type = "image",
                                    source = ClaudeImageSource(data = base64)
                                ),
                                ClaudeContentBlock(type = "text", text = prompt)
                            )
                        )
                    )
                )
            )

            response.content.firstOrNull { it.type == "text" }?.text
                ?: throw Exception("Пустой ответ от Claude Vision")
        }

    private suspend fun analyzeWithOpenAi(imageBytes: ByteArray, prompt: String): Result<String> =
        runCatching {
            val apiKey = prefs.openAiApiKey.first()
            require(apiKey.isNotBlank()) { "API ключ OpenAI не указан" }

            val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val response = openAiApi.chatCompletion(
                auth = "Bearer $apiKey",
                request = OpenAiChatRequest(
                    messages = listOf(
                        OpenAiMessage(
                            role = "user",
                            content = listOf(
                                OpenAiContentPart(type = "text", text = prompt),
                                OpenAiContentPart(
                                    type = "image_url",
                                    imageUrl = OpenAiImageUrl("data:image/jpeg;base64,$base64")
                                )
                            )
                        )
                    )
                )
            )

            response.choices.firstOrNull()?.message?.content
                ?: throw Exception("Пустой ответ от OpenAI Vision")
        }
}

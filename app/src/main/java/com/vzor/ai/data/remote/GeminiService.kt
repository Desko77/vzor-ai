package com.vzor.ai.data.remote

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import com.vzor.ai.domain.model.Message
import com.vzor.ai.domain.model.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class GeminiService(private val apiKey: String) {

    private val model by lazy {
        GenerativeModel(
            modelName = "gemini-2.0-flash",
            apiKey = apiKey,
            generationConfig = generationConfig {
                temperature = 0.7f
                maxOutputTokens = 4096
            }
        )
    }

    suspend fun sendMessage(messages: List<Message>): Result<String> = runCatching {
        val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        val chatMessages = messages.filter { it.role != MessageRole.SYSTEM }

        val history = chatMessages.dropLast(1).map { msg ->
            content(role = if (msg.role == MessageRole.USER) "user" else "model") {
                text(msg.content)
            }
        }

        val chat = model.startChat(history = history)
        val lastMessage = chatMessages.last()
        val prompt = if (systemMessage != null) {
            "${systemMessage.content}\n\n${lastMessage.content}"
        } else {
            lastMessage.content
        }

        val response = chat.sendMessage(prompt)
        response.text ?: throw Exception("Пустой ответ от Gemini")
    }

    fun streamMessage(messages: List<Message>): Flow<String> = flow {
        val systemMessage = messages.firstOrNull { it.role == MessageRole.SYSTEM }
        val chatMessages = messages.filter { it.role != MessageRole.SYSTEM }

        val history = chatMessages.dropLast(1).map { msg ->
            content(role = if (msg.role == MessageRole.USER) "user" else "model") {
                text(msg.content)
            }
        }

        val chat = model.startChat(history = history)
        val lastMessage = chatMessages.last()
        val prompt = if (systemMessage != null) {
            "${systemMessage.content}\n\n${lastMessage.content}"
        } else {
            lastMessage.content
        }

        chat.sendMessageStream(prompt).collect { chunk ->
            chunk.text?.let { emit(it) }
        }
    }
}

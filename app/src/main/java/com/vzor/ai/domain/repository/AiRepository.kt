package com.vzor.ai.domain.repository

import com.vzor.ai.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface AiRepository {
    suspend fun sendMessage(messages: List<Message>): Result<String>
    fun streamMessage(messages: List<Message>): Flow<String>

    /**
     * Стриминг с поддержкой tool calls (Claude API).
     * Для провайдеров без tool use — делегирует в streamMessage().
     */
    fun streamWithTools(
        messages: List<Message>,
        tools: List<com.vzor.ai.data.remote.ClaudeTool> = emptyList()
    ): Flow<com.vzor.ai.data.remote.StreamChunk>
}

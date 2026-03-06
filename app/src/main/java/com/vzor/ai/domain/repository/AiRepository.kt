package com.vzor.ai.domain.repository

import com.vzor.ai.domain.model.Message
import com.vzor.ai.domain.model.StreamChunk
import com.vzor.ai.domain.model.ToolDefinition
import kotlinx.coroutines.flow.Flow

interface AiRepository {
    suspend fun sendMessage(messages: List<Message>): Result<String>
    fun streamMessage(messages: List<Message>): Flow<String>

    /**
     * Стриминг с поддержкой tool calls.
     * Для провайдеров без tool use — делегирует в streamMessage().
     */
    fun streamWithTools(
        messages: List<Message>,
        tools: List<ToolDefinition> = emptyList()
    ): Flow<StreamChunk>

    /**
     * Продолжение стриминга после tool_result (multi-turn tool use).
     * Добавляет assistant tool_use + user tool_result к сообщениям и стримит заново.
     */
    fun streamToolContinuation(
        messages: List<Message>,
        tools: List<ToolDefinition>,
        toolResults: List<com.vzor.ai.orchestrator.ToolCallWithResult>
    ): Flow<StreamChunk>
}

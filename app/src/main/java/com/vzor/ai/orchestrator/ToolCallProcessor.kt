package com.vzor.ai.orchestrator

import android.util.Log
import com.vzor.ai.data.remote.*
import com.vzor.ai.domain.model.Message
import com.vzor.ai.domain.model.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ToolCallProcessor — обрабатывает tool calls из LLM стриминга.
 *
 * Текущая реализация (v1): tool results инлайнятся в текстовый стрим.
 * TODO: v2 — цикл tool use: выполнить tool → отправить tool_result обратно LLM →
 *       LLM генерирует финальный ответ на основе результатов.
 */
@Singleton
class ToolCallProcessor @Inject constructor(
    private val toolRegistry: ToolRegistry
) {
    companion object {
        private const val TAG = "ToolCallProcessor"
    }

    /**
     * Конвертирует ToolDescription в формат Claude API tools.
     */
    fun buildClaudeTools(): List<ClaudeTool> {
        return toolRegistry.toolDescriptions.map { desc ->
            val properties = desc.parameters.mapValues { (_, typeDesc) ->
                val type = when {
                    typeDesc.startsWith("int") -> "integer"
                    typeDesc.startsWith("bool") -> "boolean"
                    else -> "string"
                }
                ClaudeToolProperty(type = type, description = typeDesc)
            }
            val required = desc.parameters.keys.toList()

            ClaudeTool(
                name = desc.name,
                description = desc.description,
                inputSchema = ClaudeToolSchema(
                    properties = properties,
                    required = required
                )
            )
        }
    }

    /**
     * Обрабатывает стриминговый ответ — текст пропускает насквозь,
     * tool calls выполняет и возвращает результат как текст.
     *
     * @param chunks Flow от ClaudeStreamingClient.streamChunks()
     * @return Flow<String> — только текстовые токены (tool results inline)
     */
    fun processStream(chunks: Flow<StreamChunk>): Flow<String> = flow {
        val pendingToolCalls = mutableListOf<StreamChunk.ToolCall>()
        var stopReason: String? = null

        chunks.collect { chunk ->
            when (chunk) {
                is StreamChunk.Text -> emit(chunk.content)
                is StreamChunk.ToolCall -> pendingToolCalls.add(chunk)
                is StreamChunk.Done -> stopReason = chunk.stopReason
            }
        }

        // Если LLM остановился для tool use — выполняем
        if (stopReason == "tool_use" && pendingToolCalls.isNotEmpty()) {
            Log.d(TAG, "Executing ${pendingToolCalls.size} tool call(s)")

            for (toolCall in pendingToolCalls) {
                Log.d(TAG, "Tool: ${toolCall.name}, args: ${toolCall.arguments.keys}")
                val result = toolRegistry.executeTool(toolCall.name, toolCall.arguments)

                if (result.success) {
                    emit("\n\n[${toolCall.name}]: ${result.output}")
                } else {
                    emit("\n\n[${toolCall.name} ошибка]: ${result.output}")
                }
            }
        }
    }
}

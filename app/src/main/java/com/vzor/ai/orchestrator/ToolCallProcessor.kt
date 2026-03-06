package com.vzor.ai.orchestrator

import android.util.Log
import com.vzor.ai.domain.model.StreamChunk
import com.vzor.ai.domain.model.ToolDefinition
import com.vzor.ai.domain.model.ToolParameter
import com.vzor.ai.domain.model.ToolParamType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ToolCallProcessor — обрабатывает tool calls из LLM стриминга.
 *
 * Цикл tool use (multi-turn):
 * 1. LLM отвечает с tool_use → парсим StreamChunk.ToolCall
 * 2. Выполняем инструмент через ToolRegistry
 * 3. Вызываем continuation — отправляем tool_result обратно LLM
 * 4. LLM генерирует следующий ответ (текст или ещё tool calls)
 * 5. Повторяем до MAX_TOOL_ITERATIONS или end_turn
 */
@Singleton
class ToolCallProcessor @Inject constructor(
    private val toolRegistry: ToolRegistry
) {
    companion object {
        private const val TAG = "ToolCallProcessor"
        private const val MAX_TOOL_ITERATIONS = 5
    }

    /**
     * Конвертирует ToolDescription → ToolDefinition (domain model).
     * AiRepositoryImpl конвертирует ToolDefinition → ClaudeTool/OpenAiToolDef.
     */
    fun buildToolDefinitions(): List<ToolDefinition> {
        return toolRegistry.toolDescriptions.map { desc ->
            ToolDefinition(
                name = desc.name,
                description = desc.description,
                parameters = desc.parameters.map { (name, typeDesc) ->
                    val type = when {
                        typeDesc.startsWith("int") -> ToolParamType.INTEGER
                        typeDesc.startsWith("bool") -> ToolParamType.BOOLEAN
                        else -> ToolParamType.STRING
                    }
                    ToolParameter(name = name, type = type, description = typeDesc)
                }
            )
        }
    }

    /**
     * Простой однопроходный processStream — текст пропускает насквозь,
     * tool calls выполняет и возвращает результат inline.
     * Используется когда continuation невозможна (не-Claude провайдеры).
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

        if (stopReason == "tool_use" && pendingToolCalls.isNotEmpty()) {
            Log.d(TAG, "Executing ${pendingToolCalls.size} tool call(s) [single-pass]")
            for (toolCall in pendingToolCalls) {
                val result = toolRegistry.executeTool(toolCall.name, toolCall.arguments)
                if (result.success) {
                    emit("\n\n[${toolCall.name}]: ${result.output}")
                } else {
                    emit("\n\n[${toolCall.name} ошибка]: ${result.output}")
                }
            }
        }
    }

    /**
     * Multi-turn tool loop с continuation.
     *
     * @param initialChunks Первый стрим от LLM
     * @param continuation Лямбда для отправки tool_result обратно LLM.
     *   Принимает список ToolCallWithResult, возвращает новый Flow<StreamChunk>.
     * @return Flow<String> — текстовые токены (tool results прозрачны для UI)
     */
    fun processStreamMultiTurn(
        initialChunks: Flow<StreamChunk>,
        continuation: suspend (List<ToolCallWithResult>) -> Flow<StreamChunk>
    ): Flow<String> = flow {
        var currentStream = initialChunks
        var iteration = 0

        while (iteration < MAX_TOOL_ITERATIONS) {
            val pendingToolCalls = mutableListOf<StreamChunk.ToolCall>()
            var stopReason: String? = null

            currentStream.collect { chunk ->
                when (chunk) {
                    is StreamChunk.Text -> emit(chunk.content)
                    is StreamChunk.ToolCall -> pendingToolCalls.add(chunk)
                    is StreamChunk.Done -> stopReason = chunk.stopReason
                }
            }

            // Если LLM не запросил tool use — выходим из цикла
            if (stopReason != "tool_use" || pendingToolCalls.isEmpty()) {
                break
            }

            Log.d(TAG, "Tool iteration ${iteration + 1}: ${pendingToolCalls.size} tool call(s)")

            // Выполняем все tool calls
            val results = pendingToolCalls.map { toolCall ->
                Log.d(TAG, "  Executing: ${toolCall.name}")
                val result = toolRegistry.executeTool(toolCall.name, toolCall.arguments)
                ToolCallWithResult(
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    arguments = toolCall.arguments,
                    result = result
                )
            }

            // Отправляем tool_result обратно LLM через continuation
            currentStream = continuation(results)
            iteration++
        }

        if (iteration >= MAX_TOOL_ITERATIONS) {
            Log.w(TAG, "Max tool iterations ($MAX_TOOL_ITERATIONS) reached")
            emit("\n\n[Предел итераций tool use достигнут]")
        }
    }
}

/**
 * Результат выполнения tool call — для передачи в continuation.
 */
data class ToolCallWithResult(
    val toolCallId: String,
    val toolName: String,
    val arguments: Map<String, String>,
    val result: ToolResult
)

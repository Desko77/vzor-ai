package com.vzor.ai.data.remote

/**
 * Результат SSE стриминга — либо текстовый токен, либо tool call.
 *
 * Streaming клиенты (Claude, OpenAI) генерируют последовательность StreamChunk:
 * - Text chunks накапливаются в UI
 * - ToolCall chunks перехватываются ToolCallProcessor для выполнения
 */
sealed class StreamChunk {
    /** Текстовый токен от LLM. */
    data class Text(val content: String) : StreamChunk()

    /** LLM запросил вызов инструмента. */
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: Map<String, String>
    ) : StreamChunk()

    /** Стрим завершён с указанием причины остановки. */
    data class Done(val stopReason: String?) : StreamChunk()
}

package com.vzor.ai.domain.model

/**
 * Результат SSE стриминга — либо текстовый токен, либо tool call.
 *
 * Domain-level модель, используемая в AiRepository и ToolCallProcessor.
 * Streaming клиенты (Claude, OpenAI) маппят свои форматы в StreamChunk.
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

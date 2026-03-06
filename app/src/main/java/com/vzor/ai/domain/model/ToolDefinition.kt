package com.vzor.ai.domain.model

/**
 * Provider-agnostic описание инструмента для LLM function calling.
 *
 * Data layer конвертирует ToolDefinition → ClaudeTool / OpenAiToolDef
 * в зависимости от активного провайдера.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter> = emptyList()
)

data class ToolParameter(
    val name: String,
    val type: ToolParamType = ToolParamType.STRING,
    val description: String = "",
    val required: Boolean = true
)

enum class ToolParamType {
    STRING, INTEGER, BOOLEAN
}

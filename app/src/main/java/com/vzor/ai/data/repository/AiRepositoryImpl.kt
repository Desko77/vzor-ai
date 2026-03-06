package com.vzor.ai.data.repository

import android.util.Base64
import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.data.remote.*
import com.vzor.ai.domain.model.AiProvider
import com.vzor.ai.domain.model.Message
import com.vzor.ai.domain.model.MessageRole
import com.vzor.ai.domain.model.StreamChunk
import com.vzor.ai.domain.model.ToolDefinition
import com.vzor.ai.domain.model.ToolParamType
import com.vzor.ai.domain.repository.AiRepository
import com.vzor.ai.orchestrator.ToolCallWithResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiRepositoryImpl @Inject constructor(
    private val claudeApi: ClaudeApiService,
    private val openAiApi: OpenAiApiService,
    private val glmApi: GlmApiService,
    private val ollamaService: OllamaService,
    private val claudeStreamingClient: ClaudeStreamingClient,
    private val openAiStreamingClient: OpenAiStreamingClient,
    private val prefs: PreferencesManager
) : AiRepository {

    private var geminiService: GeminiService? = null
    private var geminiServiceKey: String = ""

    private suspend fun getGeminiService(): GeminiService {
        val key = prefs.geminiApiKey.first()
        val existing = geminiService
        if (existing != null && key == geminiServiceKey) {
            return existing
        }
        return GeminiService(key).also {
            geminiService = it
            geminiServiceKey = key
        }
    }

    // ==================== Shared message builders ====================

    /**
     * Конвертирует Message → ClaudeMessage (с поддержкой изображений).
     * Вызывается из streamClaude, streamClaudeWithTools, streamToolContinuation, sendClaude.
     */
    private fun buildClaudeMessages(messages: List<Message>): List<ClaudeMessage> {
        return messages.filter { it.role != MessageRole.SYSTEM }.map { msg ->
            if (msg.imageData != null) {
                ClaudeMessage(
                    role = if (msg.role == MessageRole.USER) "user" else "assistant",
                    content = listOf(
                        ClaudeContentBlock(
                            type = "image",
                            source = ClaudeImageSource(
                                data = Base64.encodeToString(msg.imageData, Base64.NO_WRAP)
                            )
                        ),
                        ClaudeContentBlock(type = "text", text = msg.content)
                    )
                )
            } else {
                ClaudeMessage(
                    role = if (msg.role == MessageRole.USER) "user" else "assistant",
                    content = msg.content
                )
            }
        }
    }

    private fun buildOpenAiMessages(messages: List<Message>): List<OpenAiMessage> {
        return messages.map { msg ->
            val role = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
            }

            if (msg.imageData != null && msg.role == MessageRole.USER) {
                val base64 = Base64.encodeToString(msg.imageData, Base64.NO_WRAP)
                OpenAiMessage(
                    role = role,
                    content = listOf(
                        OpenAiContentPart(type = "text", text = msg.content),
                        OpenAiContentPart(
                            type = "image_url",
                            imageUrl = OpenAiImageUrl("data:image/jpeg;base64,$base64")
                        )
                    )
                )
            } else {
                OpenAiMessage(role = role, content = msg.content)
            }
        }
    }

    // ==================== ToolDefinition → provider-specific ====================

    private fun toClaudeTools(tools: List<ToolDefinition>): List<ClaudeTool> {
        return tools.map { def ->
            ClaudeTool(
                name = def.name,
                description = def.description,
                inputSchema = ClaudeToolSchema(
                    properties = def.parameters.associate { p ->
                        p.name to ClaudeToolProperty(
                            type = when (p.type) {
                                ToolParamType.INTEGER -> "integer"
                                ToolParamType.BOOLEAN -> "boolean"
                                ToolParamType.STRING -> "string"
                            },
                            description = p.description
                        )
                    },
                    required = def.parameters.filter { it.required }.map { it.name }
                )
            )
        }
    }

    private fun toOpenAiTools(tools: List<ToolDefinition>): List<OpenAiToolDef> {
        return tools.map { def ->
            OpenAiToolDef(
                function = OpenAiFunctionDef(
                    name = def.name,
                    description = def.description,
                    parameters = OpenAiFunctionParams(
                        properties = def.parameters.associate { p ->
                            p.name to OpenAiFunctionProp(
                                type = when (p.type) {
                                    ToolParamType.INTEGER -> "integer"
                                    ToolParamType.BOOLEAN -> "boolean"
                                    ToolParamType.STRING -> "string"
                                },
                                description = p.description
                            )
                        },
                        required = def.parameters.filter { it.required }.map { it.name }
                    )
                )
            )
        }
    }

    // ==================== Public API ====================

    override suspend fun sendMessage(messages: List<Message>): Result<String> {
        return when (prefs.aiProvider.first()) {
            AiProvider.GEMINI -> getGeminiService().sendMessage(messages)
            AiProvider.CLAUDE -> sendClaude(messages)
            AiProvider.OPENAI -> sendOpenAi(messages)
            AiProvider.GLM_5 -> sendGlm(messages)
            AiProvider.LOCAL_QWEN -> sendOllama(messages)
            AiProvider.OFFLINE_QWEN -> sendOllama(messages)
        }
    }

    override fun streamWithTools(
        messages: List<Message>,
        tools: List<ToolDefinition>
    ): Flow<StreamChunk> = flow {
        when (prefs.aiProvider.first()) {
            AiProvider.CLAUDE -> {
                streamClaudeWithTools(messages, tools).collect { emit(it) }
            }
            AiProvider.OPENAI -> {
                streamOpenAiWithTools(messages, tools).collect { emit(it) }
            }
            else -> {
                streamMessage(messages).collect { emit(StreamChunk.Text(it)) }
                emit(StreamChunk.Done("end_turn"))
            }
        }
    }

    override fun streamToolContinuation(
        messages: List<Message>,
        tools: List<ToolDefinition>,
        toolResults: List<ToolCallWithResult>
    ): Flow<StreamChunk> = flow {
        val apiKey = prefs.claudeApiKey.first()
        require(apiKey.isNotBlank()) { "API ключ Claude не указан" }

        val systemPrompt = messages.firstOrNull { it.role == MessageRole.SYSTEM }?.content
        val claudeMessages = buildClaudeMessages(messages).toMutableList()

        // assistant message с tool_use блоками
        val toolUseBlocks = toolResults.map { tr ->
            mapOf(
                "type" to "tool_use",
                "id" to tr.toolCallId,
                "name" to tr.toolName,
                "input" to tr.arguments
            )
        }
        claudeMessages.add(ClaudeMessage(role = "assistant", content = toolUseBlocks))

        // user message с tool_result блоками
        val toolResultBlocks = toolResults.map { tr ->
            mapOf(
                "type" to "tool_result",
                "tool_use_id" to tr.toolCallId,
                "content" to tr.result.output
            )
        }
        claudeMessages.add(ClaudeMessage(role = "user", content = toolResultBlocks))

        val claudeTools = toClaudeTools(tools)
        claudeStreamingClient.streamChunks(
            apiKey = apiKey,
            request = ClaudeRequest(
                system = systemPrompt,
                messages = claudeMessages,
                tools = claudeTools.ifEmpty { null }
            )
        ).collect { emit(it) }
    }

    override fun streamMessage(messages: List<Message>): Flow<String> = flow {
        when (prefs.aiProvider.first()) {
            AiProvider.GEMINI -> {
                getGeminiService().streamMessage(messages).collect { emit(it) }
            }
            AiProvider.CLAUDE -> {
                streamClaude(messages).collect { emit(it) }
            }
            AiProvider.OPENAI -> {
                streamOpenAi(messages).collect { emit(it) }
            }
            AiProvider.GLM_5 -> {
                streamGlm(messages).collect { emit(it) }
            }
            AiProvider.LOCAL_QWEN, AiProvider.OFFLINE_QWEN -> {
                val ollamaMessages = messages.map { msg ->
                    OllamaMessage(
                        role = when (msg.role) {
                            MessageRole.USER -> "user"
                            MessageRole.ASSISTANT -> "assistant"
                            MessageRole.SYSTEM -> "system"
                        },
                        content = msg.content
                    )
                }
                ollamaService.streamMessage(messages = ollamaMessages).collect { emit(it) }
            }
        }
    }

    // ==================== Provider-specific streaming ====================

    private suspend fun streamClaudeWithTools(
        messages: List<Message>,
        tools: List<ToolDefinition>
    ): Flow<StreamChunk> {
        val apiKey = prefs.claudeApiKey.first()
        require(apiKey.isNotBlank()) { "API ключ Claude не указан" }

        val systemPrompt = messages.firstOrNull { it.role == MessageRole.SYSTEM }?.content
        val claudeTools = toClaudeTools(tools)

        return claudeStreamingClient.streamChunks(
            apiKey = apiKey,
            request = ClaudeRequest(
                system = systemPrompt,
                messages = buildClaudeMessages(messages),
                tools = claudeTools.ifEmpty { null }
            )
        )
    }

    private suspend fun streamOpenAiWithTools(
        messages: List<Message>,
        tools: List<ToolDefinition>
    ): Flow<StreamChunk> {
        val apiKey = prefs.openAiApiKey.first()
        require(apiKey.isNotBlank()) { "API ключ OpenAI не указан" }

        val openAiTools = toOpenAiTools(tools)

        return openAiStreamingClient.streamChunks(
            apiKey = apiKey,
            request = OpenAiChatRequest(
                messages = buildOpenAiMessages(messages),
                tools = openAiTools.ifEmpty { null }
            )
        )
    }

    private suspend fun streamClaude(messages: List<Message>): Flow<String> {
        val apiKey = prefs.claudeApiKey.first()
        require(apiKey.isNotBlank()) { "API ключ Claude не указан" }

        val systemPrompt = messages.firstOrNull { it.role == MessageRole.SYSTEM }?.content
        return claudeStreamingClient.stream(
            apiKey = apiKey,
            request = ClaudeRequest(system = systemPrompt, messages = buildClaudeMessages(messages))
        )
    }

    private suspend fun streamOpenAi(messages: List<Message>): Flow<String> {
        val apiKey = prefs.openAiApiKey.first()
        require(apiKey.isNotBlank()) { "API ключ OpenAI не указан" }
        return openAiStreamingClient.stream(
            apiKey = apiKey,
            request = OpenAiChatRequest(messages = buildOpenAiMessages(messages))
        )
    }

    private suspend fun streamGlm(messages: List<Message>): Flow<String> {
        val apiKey = prefs.glmApiKey.first()
        require(apiKey.isNotBlank()) { "API ключ GLM-5 не указан" }
        return openAiStreamingClient.streamGlm(
            apiKey = apiKey,
            request = OpenAiChatRequest(model = "glm-5", messages = buildOpenAiMessages(messages))
        )
    }

    // ==================== Non-streaming send ====================

    private suspend fun sendClaude(messages: List<Message>): Result<String> = runCatching {
        val apiKey = prefs.claudeApiKey.first()
        require(apiKey.isNotBlank()) { "API ключ Claude не указан" }

        val systemPrompt = messages.firstOrNull { it.role == MessageRole.SYSTEM }?.content
        val response = claudeApi.sendMessage(
            apiKey = apiKey,
            request = ClaudeRequest(system = systemPrompt, messages = buildClaudeMessages(messages))
        )

        response.content.firstOrNull { it.type == "text" }?.text
            ?: throw Exception("Пустой ответ от Claude")
    }

    private suspend fun sendGlm(messages: List<Message>): Result<String> = runCatching {
        val apiKey = prefs.glmApiKey.first()
        require(apiKey.isNotBlank()) { "API ключ GLM-5 не указан" }

        val openAiMessages = messages.map { msg ->
            val role = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
            }
            OpenAiMessage(role = role, content = msg.content)
        }

        val response = glmApi.chatCompletion(
            auth = "Bearer $apiKey",
            request = OpenAiChatRequest(model = "glm-5", messages = openAiMessages)
        )

        response.choices.firstOrNull()?.message?.content
            ?: throw Exception("Пустой ответ от GLM-5")
    }

    private suspend fun sendOllama(messages: List<Message>): Result<String> = runCatching {
        val hostOverride = prefs.localAiHostOverride.first()
        if (hostOverride.isNotBlank()) {
            ollamaService.updateHost(hostOverride)
        }

        val ollamaMessages = messages.map { msg ->
            OllamaMessage(
                role = when (msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    MessageRole.SYSTEM -> "system"
                },
                content = msg.content
            )
        }

        val response = ollamaService.sendMessage(messages = ollamaMessages)
        response.message?.content ?: throw Exception("Пустой ответ от Local AI")
    }

    private suspend fun sendOpenAi(messages: List<Message>): Result<String> = runCatching {
        val apiKey = prefs.openAiApiKey.first()
        require(apiKey.isNotBlank()) { "API ключ OpenAI не указан" }

        val response = openAiApi.chatCompletion(
            auth = "Bearer $apiKey",
            request = OpenAiChatRequest(messages = buildOpenAiMessages(messages))
        )

        response.choices.firstOrNull()?.message?.content
            ?: throw Exception("Пустой ответ от OpenAI")
    }
}

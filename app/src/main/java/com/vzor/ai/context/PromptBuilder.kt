package com.vzor.ai.context

import android.content.Context
import android.util.Log
import com.vzor.ai.data.local.EndpointRegistry
import com.vzor.ai.domain.model.AiProvider
import com.vzor.ai.domain.model.MessageRole
import com.vzor.ai.domain.model.SceneData
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the full system prompt sent to the AI backend by loading a template
 * from assets and filling in context blocks (scene, memory, tools, history).
 */
@Singleton
class PromptBuilder @Inject constructor(
    private val contextManager: ContextManager,
    private val endpointRegistry: EndpointRegistry,
    @ApplicationContext private val appContext: Context
) {
    companion object {
        private const val TAG = "PromptBuilder"
        private const val PROMPTS_DIR = "prompts"
        private const val TEMPLATE_QWEN = "system_qwen.txt"
        private const val TEMPLATE_CLAUDE = "system_claude.txt"

        private const val PLACEHOLDER_SCENE = "{{scene_block}}"
        private const val PLACEHOLDER_MEMORY = "{{memory_block}}"
        private const val PLACEHOLDER_TOOLS = "{{tools_block}}"
        private const val PLACEHOLDER_HISTORY = "{{history_block}}"
    }

    /**
     * Builds the complete prompt for the given user message, optional scene data,
     * and AI provider. Loads the appropriate template, fills in all placeholder blocks.
     */
    suspend fun buildPrompt(
        userMessage: String,
        sceneData: SceneData?,
        provider: AiProvider
    ): String {
        val template = loadTemplate(provider)
        val sceneBlock = buildSceneBlock(sceneData)
        val memoryBlock = buildMemoryBlock(userMessage)
        val toolsBlock = buildToolsBlock()
        val historyBlock = buildHistoryBlock()

        return template
            .replace(PLACEHOLDER_SCENE, sceneBlock)
            .replace(PLACEHOLDER_MEMORY, memoryBlock)
            .replace(PLACEHOLDER_TOOLS, toolsBlock)
            .replace(PLACEHOLDER_HISTORY, historyBlock)
    }

    /**
     * Loads the prompt template from assets based on the AI provider.
     * Uses the Qwen template for local/offline providers, Claude template for cloud.
     */
    private fun loadTemplate(provider: AiProvider): String {
        val fileName = when (provider) {
            AiProvider.LOCAL_QWEN, AiProvider.OFFLINE_QWEN -> TEMPLATE_QWEN
            else -> TEMPLATE_CLAUDE
        }
        return try {
            appContext.assets.open("$PROMPTS_DIR/$fileName")
                .bufferedReader()
                .use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load template $fileName, using fallback", e)
            buildFallbackTemplate()
        }
    }

    private fun buildFallbackTemplate(): String {
        return """You are Vzor AI, a helpful assistant integrated with smart glasses.

$PLACEHOLDER_SCENE

$PLACEHOLDER_MEMORY

$PLACEHOLDER_TOOLS

$PLACEHOLDER_HISTORY"""
    }

    /**
     * Builds the scene context block from SceneData as a JSON string.
     * Returns empty string if no scene data or if it is expired.
     */
    private fun buildSceneBlock(sceneData: SceneData?): String {
        if (sceneData == null || sceneData.isExpired()) return ""

        return try {
            val json = JSONObject().apply {
                put("scene_id", sceneData.sceneId)
                put("summary", sceneData.sceneSummary)
                put("objects", JSONArray().apply {
                    sceneData.objects.forEach { obj ->
                        put(JSONObject().apply {
                            put("label", obj.label)
                            put("confidence", obj.confidence)
                        })
                    }
                })
                if (sceneData.text.isNotEmpty()) {
                    put("ocr_text", JSONArray(sceneData.text))
                }
                put("stability", sceneData.stability)
            }
            "Current scene context:\n$json"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build scene block", e)
            ""
        }
    }

    /**
     * Builds the memory block from persistent facts relevant to the user message.
     * Facts are formatted as bullet points.
     */
    private suspend fun buildMemoryBlock(userMessage: String): String {
        val facts = contextManager.getPersistentFacts(userMessage, limit = 10)
        if (facts.isEmpty()) return ""

        val bullets = facts.joinToString("\n") { fact ->
            "- [${fact.category.name}] ${fact.fact}"
        }
        return "Known facts about the user:\n$bullets"
    }

    /**
     * Builds the tools block listing available actions.
     * This is a static list of capabilities the AI can reference.
     */
    private fun buildToolsBlock(): String {
        val actions = listOf(
            "take_photo - Capture a photo from glasses camera",
            "start_recording - Start audio/video recording",
            "stop_recording - Stop current recording",
            "navigate - Open navigation to a location",
            "search_web - Search the internet for information",
            "set_reminder - Set a timed reminder",
            "read_screen - Read text currently visible on glasses display",
            "translate - Translate text between languages",
            "identify_object - Identify an object in the current scene"
        )
        return "Available actions:\n${actions.joinToString("\n") { "- $it" }}"
    }

    /**
     * Builds the history block from recent session messages,
     * formatted as "Role: content" lines.
     */
    private fun buildHistoryBlock(): String {
        val messages = contextManager.getSessionContext()
        if (messages.isEmpty()) return ""

        val formatted = messages.joinToString("\n") { msg ->
            val role = when (msg.role) {
                MessageRole.USER -> "User"
                MessageRole.ASSISTANT -> "Assistant"
                MessageRole.SYSTEM -> "System"
            }
            "$role: ${msg.content}"
        }
        return "Recent conversation:\n$formatted"
    }
}

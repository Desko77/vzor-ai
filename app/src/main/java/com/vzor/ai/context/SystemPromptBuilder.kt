package com.vzor.ai.context

import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.domain.model.MemoryFact
import com.vzor.ai.domain.model.SceneData
import com.vzor.ai.orchestrator.BackendRouter
import com.vzor.ai.orchestrator.ConnectionProfile
import com.vzor.ai.speech.AudioContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Строит контекстный системный промпт для LLM на основе:
 * - Persistent memory (факты о пользователе)
 * - Текущая сцена (SceneData с камеры очков)
 * - Аудио-контекст (тишина/речь/музыка/шум)
 * - Профиль подключения (HOME_WIFI, LTE, OFFLINE)
 * - Язык пользователя
 *
 * Промпт включается как system message перед каждым запросом к LLM.
 */
@Singleton
class SystemPromptBuilder @Inject constructor(
    private val contextManager: ContextManager,
    private val backendRouter: BackendRouter,
    private val prefs: PreferencesManager
) {
    companion object {
        private const val BASE_PROMPT = """Ты — Взор, русскоязычный AI-ассистент для умных очков Ray-Ban Meta. Ты помогаешь пользователю голосом. Отвечай кратко (1-3 предложения), по-русски, дружелюбно. Если нужен подробный ответ — дай краткий сначала, потом детали. Не используй markdown — твой ответ будет озвучен через TTS."""

        private const val VISION_CONTEXT_HEADER = "\n\nТекущая сцена (с камеры очков):"
        private const val MEMORY_HEADER = "\n\nЧто ты знаешь о пользователе:"
        private const val AUDIO_CONTEXT_HEADER = "\n\nАудио-контекст:"
        private const val ENVIRONMENT_HEADER = "\n\nОкружение:"
    }

    /**
     * Собирает полный системный промпт.
     *
     * @param sceneData текущая сцена (может быть null)
     * @param userQuery запрос пользователя (для поиска релевантных фактов)
     */
    suspend fun build(
        sceneData: SceneData? = null,
        userQuery: String = ""
    ): String {
        val sb = StringBuilder(BASE_PROMPT)

        // 1. Persistent memory — факты о пользователе
        appendMemoryContext(sb, userQuery)

        // 2. Vision context — что видит камера
        appendVisionContext(sb, sceneData)

        // 3. Audio context
        appendAudioContext(sb)

        // 4. Environment info
        appendEnvironment(sb)

        return sb.toString()
    }

    /**
     * Строит лёгкий системный промпт без vision/audio.
     * Используется для text-only запросов.
     */
    suspend fun buildLightweight(userQuery: String = ""): String {
        val sb = StringBuilder(BASE_PROMPT)
        appendMemoryContext(sb, userQuery)
        return sb.toString()
    }

    private suspend fun appendMemoryContext(sb: StringBuilder, userQuery: String) {
        val facts = contextManager.getPersistentFacts(userQuery, 5)
        if (facts.isNotEmpty()) {
            sb.append(MEMORY_HEADER)
            for (fact in facts) {
                sb.append("\n- ${fact.fact}")
            }
        }
    }

    private fun appendVisionContext(sb: StringBuilder, sceneData: SceneData?) {
        sceneData ?: return

        sb.append(VISION_CONTEXT_HEADER)
        if (sceneData.sceneSummary.isNotBlank()) {
            sb.append("\n${sceneData.sceneSummary}")
        }
        if (sceneData.objects.isNotEmpty()) {
            val objList = sceneData.objects.take(5).joinToString(", ") { it.label }
            sb.append("\nОбъекты: $objList")
        }
        if (sceneData.text.isNotEmpty()) {
            val textStr = sceneData.text.take(3).joinToString("; ")
            sb.append("\nТекст: $textStr")
        }
        if (sceneData.faceCount > 0) {
            sb.append("\nЛиц: ${sceneData.faceCount}")
        }
        if (sceneData.gestures.isNotEmpty()) {
            sb.append("\nЖесты: ${sceneData.gestures.joinToString(", ")}")
        }
    }

    private fun appendAudioContext(sb: StringBuilder) {
        val audio = backendRouter.audioContext
        if (audio != AudioContext.SILENCE) {
            sb.append(AUDIO_CONTEXT_HEADER)
            sb.append(" ${audio.label}")
        }
    }

    private fun appendEnvironment(sb: StringBuilder) {
        val profile = backendRouter.currentProfile
        if (profile != ConnectionProfile.OFFLINE) {
            sb.append(ENVIRONMENT_HEADER)
            sb.append(" ${profile.name.lowercase().replace('_', ' ')}")
        }
    }
}

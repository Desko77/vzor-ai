package com.vzor.ai.context

import android.util.Log
import com.vzor.ai.domain.model.Message
import com.vzor.ai.domain.model.MessageRole
import com.vzor.ai.domain.repository.AiRepository
import com.vzor.ai.domain.repository.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts key facts about the user from conversations using the LLM,
 * then persists them to the MemoryRepository for long-term recall.
 */
@Singleton
class MemoryExtractor @Inject constructor(
    private val aiRepository: AiRepository,
    private val memoryRepository: MemoryRepository
) {
    companion object {
        private const val TAG = "MemoryExtractor"

        private const val EXTRACTION_PROMPT = """Extract key facts about the user from this conversation. Return ONLY a JSON array of objects with these fields:
- "fact": a concise statement about the user
- "category": one of PREFERENCE, PERSONAL, LOCATION, CONTACT, HABIT, OTHER
- "importance": integer from 1 (low) to 5 (high)

Example response:
[{"fact":"User lives in Moscow","category":"LOCATION","importance":4},{"fact":"User prefers dark mode","category":"PREFERENCE","importance":2}]

If no facts can be extracted, return an empty array: []

Conversation:
"""

        private val VALID_CATEGORIES = setOf(
            "PREFERENCE", "PERSONAL", "LOCATION", "CONTACT", "HABIT", "OTHER"
        )
    }

    /**
     * Sends the conversation to the LLM to extract user facts, parses the
     * returned JSON, and saves valid facts to the MemoryRepository.
     */
    suspend fun extractFacts(conversation: List<Message>) {
        if (conversation.isEmpty()) return

        val conversationText = conversation.joinToString("\n") { msg ->
            val role = when (msg.role) {
                MessageRole.USER -> "User"
                MessageRole.ASSISTANT -> "Assistant"
                MessageRole.SYSTEM -> "System"
            }
            "$role: ${msg.content}"
        }

        val prompt = EXTRACTION_PROMPT + conversationText

        val extractionMessage = Message(
            role = MessageRole.USER,
            content = prompt
        )

        try {
            val result = aiRepository.sendMessage(listOf(extractionMessage))
            result.onSuccess { responseText ->
                val facts = parseFactsJson(responseText)
                for (fact in facts) {
                    memoryRepository.saveFact(
                        fact = fact.fact,
                        category = fact.category,
                        importance = fact.importance
                    )
                }
                // Cleanup to keep memory bounded
                memoryRepository.cleanup()
                Log.d(TAG, "Extracted and saved ${facts.size} facts from conversation")
            }.onFailure { e ->
                Log.e(TAG, "Failed to extract facts from LLM", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during fact extraction", e)
        }
    }

    /**
     * Parses a JSON array of fact objects from the LLM response.
     * Tolerant of extra text around the JSON array.
     */
    private fun parseFactsJson(response: String): List<ExtractedFact> {
        val facts = mutableListOf<ExtractedFact>()
        try {
            // Find the JSON array in the response (LLM may wrap it in markdown etc.)
            val jsonStart = response.indexOf('[')
            val jsonEnd = response.lastIndexOf(']')
            if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
                Log.w(TAG, "No JSON array found in LLM response")
                return emptyList()
            }

            val jsonStr = response.substring(jsonStart, jsonEnd + 1)
            val jsonArray = JSONArray(jsonStr)

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val fact = obj.optString("fact", "").trim()
                val category = obj.optString("category", "OTHER").uppercase().trim()
                val importance = obj.optInt("importance", 3).coerceIn(1, 5)

                if (fact.isNotBlank()) {
                    val validCategory = if (category in VALID_CATEGORIES) category else "OTHER"
                    facts.add(ExtractedFact(fact, validCategory, importance))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse facts JSON", e)
        }
        return facts
    }

    private data class ExtractedFact(
        val fact: String,
        val category: String,
        val importance: Int
    )
}

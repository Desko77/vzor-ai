package com.vzor.ai.context

import android.util.Log
import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.domain.model.MemoryFact
import com.vzor.ai.domain.model.Message
import com.vzor.ai.domain.repository.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages two memory layers for the AI conversation context:
 * - Session Memory (RAM): current conversation messages with a sliding window budget
 * - Persistent Memory: long-term user facts stored in Room via MemoryRepository
 */
@Singleton
class ContextManager @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val prefs: PreferencesManager
) {
    companion object {
        private const val TAG = "ContextManager"
        /** Maximum token budget for session context window. */
        private const val MAX_SESSION_TOKENS = 2048
        /** Maximum number of messages in session memory (architecture spec: 20). */
        private const val MAX_SESSION_MESSAGES = 20
        /** Maximum persistent memory facts before cleanup. */
        private const val MAX_PERSISTENT_FACTS = 100
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMessages = ConcurrentLinkedDeque<Message>()

    /**
     * Returns the trimmed message history that fits within the token budget.
     * Messages are kept in chronological order, oldest are dropped first when over budget.
     */
    fun getSessionContext(): List<Message> {
        val messages = sessionMessages.toList()
        return trimToTokenBudget(messages)
    }

    /**
     * Adds a message to the session memory. If the total token count exceeds
     * the budget after adding, the oldest messages are evicted.
     */
    fun addToSession(message: Message) {
        sessionMessages.addLast(message)
        evictIfOverBudget()
    }

    /**
     * Retrieves persistent facts relevant to the given query.
     */
    suspend fun getPersistentFacts(query: String, limit: Int = 10): List<MemoryFact> {
        return if (query.isBlank()) {
            memoryRepository.getTopFacts(limit)
        } else {
            val searchResults = memoryRepository.searchFacts(query, limit)
            if (searchResults.isEmpty()) {
                memoryRepository.getTopFacts(limit)
            } else {
                searchResults
            }
        }
    }

    /** Clears all session messages from RAM and triggers persistent memory cleanup. */
    fun clearSession() {
        sessionMessages.clear()
        scope.launch {
            try {
                memoryRepository.cleanup(MAX_PERSISTENT_FACTS)
            } catch (e: Exception) {
                Log.w(TAG, "Persistent memory cleanup failed", e)
            }
        }
    }

    /**
     * Rough token estimate: approximately 1 token per 4 characters.
     */
    fun getTokenEstimate(text: String): Int = text.length / 4

    private fun evictIfOverBudget() {
        while (totalTokens() > MAX_SESSION_TOKENS && sessionMessages.size > 1) {
            sessionMessages.pollFirst()
        }
        while (sessionMessages.size > MAX_SESSION_MESSAGES) {
            sessionMessages.pollFirst()
        }
    }

    private fun trimToTokenBudget(messages: List<Message>): List<Message> {
        var tokenCount = 0
        val result = mutableListOf<Message>()
        // Iterate from newest to oldest, keep as many recent messages as fit
        for (message in messages.reversed()) {
            val msgTokens = getTokenEstimate(message.content)
            if (tokenCount + msgTokens > MAX_SESSION_TOKENS && result.isNotEmpty()) {
                break
            }
            tokenCount += msgTokens
            result.add(0, message)
        }
        return result
    }

    private fun totalTokens(): Int {
        return sessionMessages.sumOf { getTokenEstimate(it.content) }
    }
}

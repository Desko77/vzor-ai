package com.vzor.ai.domain.repository

import com.vzor.ai.domain.model.MemoryFact

interface MemoryRepository {

    suspend fun saveFact(fact: String, category: String, importance: Int): Long

    suspend fun searchFacts(query: String, limit: Int): List<MemoryFact>

    suspend fun getTopFacts(limit: Int): List<MemoryFact>

    suspend fun deleteFact(id: Long)

    /**
     * Removes the least important and oldest facts when total count exceeds [maxFacts].
     */
    suspend fun cleanup(maxFacts: Int = 100)
}

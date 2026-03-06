package com.vzor.ai.data.repository

import com.vzor.ai.data.local.MemoryFactDao
import com.vzor.ai.data.local.MemoryFactEntity
import com.vzor.ai.domain.model.MemoryCategory
import com.vzor.ai.domain.model.MemoryFact
import com.vzor.ai.domain.repository.MemoryRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepositoryImpl @Inject constructor(
    private val memoryFactDao: MemoryFactDao
) : MemoryRepository {

    override suspend fun saveFact(fact: String, category: String, importance: Int): Long {
        val now = System.currentTimeMillis()
        val entity = MemoryFactEntity(
            fact = fact,
            category = category,
            importance = importance.coerceIn(1, 5),
            createdAt = now,
            lastAccessedAt = now,
            accessCount = 0
        )
        return memoryFactDao.insert(entity)
    }

    override suspend fun searchFacts(query: String, limit: Int): List<MemoryFact> {
        val results = memoryFactDao.searchByKeyword(query)
        val now = System.currentTimeMillis()
        val limited = results.take(limit)
        // Update access time for returned facts
        limited.forEach { entity ->
            memoryFactDao.updateAccessTime(entity.id, now)
        }
        return limited.map { it.toDomain() }
    }

    override suspend fun getTopFacts(limit: Int): List<MemoryFact> {
        return memoryFactDao.getTopFacts(limit).map { it.toDomain() }
    }

    override suspend fun deleteFact(id: Long) {
        memoryFactDao.deleteById(id)
    }

    override suspend fun cleanup(maxFacts: Int) {
        // Атомарная очистка — один SQL запрос, нет race condition
        memoryFactDao.deleteExceptTop(maxFacts)
    }
}

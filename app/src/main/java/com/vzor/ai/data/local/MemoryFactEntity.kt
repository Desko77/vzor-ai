package com.vzor.ai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.vzor.ai.domain.model.MemoryCategory
import com.vzor.ai.domain.model.MemoryFact

@Entity(tableName = "memory_facts")
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fact: String,
    val category: String,
    val importance: Int,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val accessCount: Int = 0
) {
    fun toDomain() = MemoryFact(
        id = id,
        fact = fact,
        category = MemoryCategory.fromString(category),
        importance = importance,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(memoryFact: MemoryFact) = MemoryFactEntity(
            id = memoryFact.id,
            fact = memoryFact.fact,
            category = memoryFact.category.name,
            importance = memoryFact.importance,
            createdAt = memoryFact.createdAt,
            lastAccessedAt = System.currentTimeMillis(),
            accessCount = 0
        )
    }
}

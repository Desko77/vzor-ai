package com.vzor.ai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemoryFactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: MemoryFactEntity): Long

    @Query("SELECT * FROM memory_facts ORDER BY importance DESC, lastAccessedAt DESC")
    suspend fun getAll(): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE fact LIKE '%' || :query || '%' ORDER BY importance DESC, lastAccessedAt DESC")
    suspend fun searchByKeyword(query: String): List<MemoryFactEntity>

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM memory_facts ORDER BY importance DESC, lastAccessedAt DESC LIMIT :limit")
    suspend fun getTopFacts(limit: Int): List<MemoryFactEntity>

    @Query("UPDATE memory_facts SET lastAccessedAt = :timestamp, accessCount = accessCount + 1 WHERE id = :id")
    suspend fun updateAccessTime(id: Long, timestamp: Long)

    @Query("DELETE FROM memory_facts WHERE createdAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    @Query("SELECT COUNT(*) FROM memory_facts")
    suspend fun getCount(): Int
}
